package com.cbgpu.cbgpu_app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Native Auth refresh and the WebView share one HttpOnly session, without JavaScript cookie access.
 */
object NativeCookieSession {
    private const val URL = "https://app.cbgpu.com/"
    private const val KEY = "sb-cbgpu-auth-token"
    private const val STATE = "cbgpu-shared-session-state"
    private const val ATTR = "; Domain=.cbgpu.com; Path=/; Secure; HttpOnly; SameSite=Lax"

    private fun identity(value: JSONObject): String? =
        try {
            val token = value.getString("access_token")
            val claims =
                JSONObject(
                    String(
                        android.util.Base64.decode(
                            token.split('.')[1],
                            android.util.Base64.URL_SAFE or
                                android.util.Base64.NO_WRAP or
                                android.util.Base64.NO_PADDING,
                        ),
                    ),
                )
            val sub = claims.getString("sub")
            val sid = claims.getString("session_id")
            require(sub.isNotEmpty() && sid.isNotEmpty())
            "$sub:$sid"
        } catch (_: Exception) {
            null
        }

    private fun cookies(manager: CookieManager): Map<String, String> =
        manager
            .getCookie(URL)
            .orEmpty()
            .split(';')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i < 1) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }
            .toMap()

    private fun shared(cookies: Map<String, String>): JSONObject? =
        try {
            val text = StringBuilder()
            var i = 0
            while (i < 32 && cookies.containsKey("$KEY.$i")) {
                text.append(Uri.decode(cookies.getValue("$KEY.$i")))
                require(text.length <= 65536)
                i++
            }
            if (text.isEmpty()) text.append(Uri.decode(cookies[KEY].orEmpty()))
            if (text.isEmpty()) null else JSONObject(text.toString())
        } catch (_: Exception) {
            null
        }

    private fun chunks(value: String): List<String> {
        val out = mutableListOf<String>()
        var part = StringBuilder()
        var offset = 0
        while (offset < value.length) {
            val point = Character.codePointAt(value, offset)
            val encoded = Uri.encode(String(Character.toChars(point)))
            if (part.length + encoded.length > 2800) {
                out.add(part.toString())
                part = StringBuilder()
            }
            part.append(encoded)
            offset += Character.charCount(point)
        }
        if (part.isNotEmpty()) out.add(part.toString())
        require(out.size <= 32)
        return out
    }

    private fun onMain(action: (CookieManager) -> List<String>) {
        val done = CountDownLatch(1)
        val operation = Runnable {
            try {
                val manager = CookieManager.getInstance()
                val values = action(manager)
                if (values.isEmpty()) {
                    done.countDown()
                    return@Runnable
                }
                var remaining = values.size
                for (value in values) manager.setCookie(URL, value) {
                    remaining--
                    if (remaining == 0) {
                        manager.flush()
                        done.countDown()
                    }
                }
            } catch (_: Exception) {
                done.countDown()
            }
        }
        // SessionVault calls from worker threads; never block the Android UI on a cookie callback.
        if (Looper.myLooper() == Looper.getMainLooper()) operation.run()
        else {
            Handler(Looper.getMainLooper()).post(operation)
            done.await(10, TimeUnit.SECONDS)
        }
    }

    fun currentOwner(): String? =
        try {
            val all = cookies(CookieManager.getInstance())
            if (all[STATE] == "signed-out") null
            else shared(all)?.let { identity(it)?.substringBefore(':') }
        } catch (_: Exception) {
            null
        }

    fun verified(value: JSONObject) {
        val incoming = identity(value) ?: return
        val encoded = chunks(value.toString())
        onMain { manager ->
            val all = cookies(manager)
            if (all[STATE] == "signed-out") return@onMain emptyList()
            val current = shared(all)
            // The website owns identity changes. A delayed native refresh must not replace a newer
            // login.
            if (current != null && identity(current) != incoming) return@onMain emptyList()
            val result = mutableListOf<String>()
            for ((i, chunk) in encoded.withIndex()) result.add(
                "$KEY.$i=$chunk$ATTR; Max-Age=31536000",
            )
            val prior =
                (all.keys.filter {
                        it == KEY || it.matches(Regex("${Regex.escape(KEY)}\\.[0-9]+"))
                    } + (0 until 32).map { "$KEY.$it" } + KEY)
                    .toSet()
            for (name in prior) if (
                name == KEY || name.substringAfterLast('.').toIntOrNull() !in encoded.indices
            )
                result.add("$name=$ATTR; Max-Age=0")
            result.add("$STATE=active$ATTR; Max-Age=31536000")
            result
        }
    }

    fun clear(expected: JSONObject?) {
        val owner = expected?.let(::identity)
        onMain { manager ->
            val all = cookies(manager)
            val current = shared(all)
            if (current != null && (owner == null || identity(current) != owner))
                return@onMain emptyList()
            val names =
                (all.keys.filter {
                        it == KEY || it.matches(Regex("${Regex.escape(KEY)}\\.[0-9]+"))
                    } + (0 until 32).map { "$KEY.$it" } + KEY)
                    .toSet()
            names.map { "$it=$ATTR; Max-Age=0" } + "$STATE=signed-out$ATTR; Max-Age=31536000"
        }
    }
}
