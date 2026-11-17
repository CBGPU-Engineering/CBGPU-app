package com.cbgpu.cbgpu_app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

object NativeHttp {
    const val BASE = "https://cbgpu.com/backend"
    // Public project key; authorization is enforced by Auth and RLS, never this value.
    const val ANON = "sb_publishable_fWE68mJ0qDMA0CdgU2D5nK_7jWO2mVR"

    fun json(
        url: String,
        method: String = "GET",
        token: String? = null,
        body: JSONObject? = null,
    ): String {
        require(url.startsWith("https://cbgpu.com/"))
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.requestMethod = method
        c.setRequestProperty("apikey", ANON)
        c.setRequestProperty("Accept", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Prefer", "resolution=merge-duplicates")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(c.responseCode in 200..299) { "요청을 완료하지 못했습니다 (${c.responseCode})" }
            return c.inputStream.use {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    check(out.size() + n <= 2 * 1024 * 1024) { "응답이 너무 큽니다" }
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }
}

class SessionVault(private val context: Context) {
    companion object {
        private val lock = Any()
        private const val ALIAS = "cbgpu.native.session.v1"
    }

    private val prefs
        get() = context.getSharedPreferences("cbgpu_native_session", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun write(value: JSONObject) {
        // Persist refreshes into the system WebView too; otherwise background widget refreshes
        // could leave its parent cookie several rotations behind the native session.
        val expires =
            try {
                JSONObject(
                        String(
                            Base64.decode(
                                value.getString("access_token").split('.')[1],
                                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                            ),
                        ),
                    )
                    .getLong("exp")
            } catch (_: Exception) {
                0
            }
        value
            .put("token_type", "bearer")
            .put("expires_at", expires)
            .put("expires_in", maxOf(0, expires - System.currentTimeMillis() / 1000))

        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val bytes = c.iv + c.doFinal(value.toString().toByteArray())
        check(
            prefs
                .edit()
                .putString("session", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .putBoolean("migrated", true)
                .commit(),
        )
        context
            .getSharedPreferences("FlutterSharedPreferences", 0)
            .edit()
            .remove("flutter.sb-cbgpu-auth-token")
            .commit()
        NativeCookieSession.verified(value)
    }

    private fun read(): JSONObject? {
        val stored = prefs.getString("session", null)
        if (stored != null)
            return try {
                val bytes = Base64.decode(stored, Base64.DEFAULT)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                JSONObject(String(c.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        if (prefs.getBoolean("migrated", false)) return null
        val old =
            context
                .getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                .getString("flutter.sb-cbgpu-auth-token", null) ?: return null
        return try {
            val raw = JSONObject(old)
            (raw.optJSONObject("currentSession") ?: raw).takeIf {
                it.has("refresh_token") && it.has("access_token")
            }
        } catch (_: Exception) {
            null
        }
    }

    fun sync(access: String, refresh: String): JSONObject =
        synchronized(lock) {
            require(access.length in 20..16384 && refresh.length in 10..4096)
            val user =
                JSONObject(NativeHttp.json(NativeHttp.BASE + "/auth/v1/user", token = access))
            require(user.optString("id").isNotEmpty())
            val prior = read()
            if (prior?.optJSONObject("user")?.optString("id") != user.optString("id")) {
                WidgetRefreshWorker.clearPrivate(context)
                NativeAlarms.clear(context)
                retirePush(prior)
            }
            val session =
                JSONObject()
                    .put("access_token", access)
                    .put("refresh_token", refresh)
                    .put("user", user)
            write(session)
            session
        }

    fun session(): JSONObject? =
        synchronized(lock) {
            val current = read() ?: return@synchronized null
            if (NativeCookieSession.signedOut()) return@synchronized null
            val shared = NativeCookieSession.currentOwner()
            val currentId = current.optJSONObject("user")?.optString("id")
            if (shared != null && shared != currentId) return@synchronized null
            if (!prefs.contains("session")) {
                try {
                    val user =
                        JSONObject(
                            NativeHttp.json(
                                NativeHttp.BASE + "/auth/v1/user",
                                token = current.optString("access_token"),
                            ),
                        )
                    require(user.optString("id") == currentId)
                    current.put("user", user)
                    write(current)
                } catch (_: Exception) {
                    /* Expired legacy access falls through to the verified refresh below. */
                }
            }
            val access = current.optString("access_token")
            val exp =
                try {
                    JSONObject(
                            String(
                                Base64.decode(
                                    access.split('.')[1],
                                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                                ),
                            ),
                        )
                        .optLong("exp")
                } catch (_: Exception) {
                    0
                }
            if (exp > System.currentTimeMillis() / 1000 + 60) return@synchronized current
            try {
                val next =
                    JSONObject(
                        NativeHttp.json(
                            NativeHttp.BASE + "/auth/v1/token?grant_type=refresh_token",
                            "POST",
                            body =
                                JSONObject()
                                    .put("refresh_token", current.getString("refresh_token")),
                        ),
                    )
                require(next.has("user") && next.has("refresh_token"))
                write(next)
                next
            } catch (_: Exception) {
                current
            }
        }

    fun ownerSnapshot(): String? = read()?.optJSONObject("user")?.optString("id")

    fun notificationOwner(): String? {
        // Snapshot read only: messaging must not wait on a network refresh holding the vault lock.
        if (!prefs.contains("session")) return null
        val s = read() ?: return null
        return try {
            val claims =
                JSONObject(
                    String(
                        Base64.decode(
                            s.getString("access_token").split('.')[1],
                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                        ),
                    ),
                )
            val id = s.getJSONObject("user").getString("id")
            if (
                claims.getString("sub") == id &&
                    claims.getLong("exp") > System.currentTimeMillis() / 1000
            )
                id
            else null
        } catch (_: Exception) {
            null
        }
    }

    private fun retirePush(prior: JSONObject?) {
        val push = context.getSharedPreferences("cbgpu_push", 0)
        push.edit().putBoolean("retirement_required", true).commit()
        val token = push.getString("token", null)
        val access = prior?.optString("access_token")
        if (!access.isNullOrEmpty() && !token.isNullOrEmpty())
            try {
                NativeHttp.json(
                    NativeHttp.BASE +
                        "/rest/v1/device_tokens?token=eq." +
                        java.net.URLEncoder.encode(token, "UTF-8"),
                    "DELETE",
                    access,
                )
            } catch (_: Exception) {}
        try {
            val messaging = com.google.firebase.messaging.FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = false
            com.google.android.gms.tasks.Tasks.await(
                messaging.deleteToken(),
                15,
                java.util.concurrent.TimeUnit.SECONDS,
            )
            push.edit().remove("token").putBoolean("retirement_required", false).commit()
        } catch (_: Exception) {
            /* Never register a foreign installation token after failed retirement. */
        }
    }

    fun pushReady(): Boolean =
        synchronized(lock) {
            if (prefs.getString("session", null) == null) return@synchronized false
            if (
                context
                    .getSharedPreferences("cbgpu_push", 0)
                    .getBoolean("retirement_required", false)
            )
                retirePush(null)
            !context.getSharedPreferences("cbgpu_push", 0).getBoolean("retirement_required", false)
        }

    fun currentOwner(): String? =
        synchronized(lock) { read()?.optJSONObject("user")?.optString("id") }

    fun commitForOwner(expected: String?, action: () -> Unit): Boolean =
        synchronized(lock) {
            if (read()?.optJSONObject("user")?.optString("id") != expected)
                return@synchronized false
            if (NativeCookieSession.signedOut()) return@synchronized false
            val shared = NativeCookieSession.currentOwner()
            if (shared != null && shared != expected) return@synchronized false
            action()
            true
        }

    fun clear() =
        synchronized(lock) {
            val current = read()
            val token = current?.optString("access_token")
            retirePush(current)
            if (!token.isNullOrEmpty())
                try {
                    NativeHttp.json(NativeHttp.BASE + "/auth/v1/logout?scope=local", "POST", token)
                } catch (_: Exception) {}
            NativeCookieSession.clear(current)
            prefs.edit().remove("session").putBoolean("migrated", true).commit()
            context
                .getSharedPreferences("FlutterSharedPreferences", 0)
                .edit()
                .remove("flutter.sb-cbgpu-auth-token")
                .commit()
            WidgetRefreshWorker.clearPrivate(context)
            NativeAlarms.clear(context)
        }

    fun registerPush(token: String) =
        synchronized(lock) {
            if (
                context
                    .getSharedPreferences("cbgpu_push", 0)
                    .getBoolean("retirement_required", false)
            )
                return@synchronized
            val s = session() ?: return@synchronized
            val user = s.optJSONObject("user")?.optString("id").orEmpty()
            if (user.isEmpty()) return@synchronized
            NativeHttp.json(
                NativeHttp.BASE + "/rest/v1/device_tokens?on_conflict=token",
                "POST",
                s.optString("access_token"),
                JSONObject()
                    .put("user_id", user)
                    .put("token", token)
                    .put("platform", "android")
                    .put("native_kind", "android-native-v1"),
            )
        }
}
