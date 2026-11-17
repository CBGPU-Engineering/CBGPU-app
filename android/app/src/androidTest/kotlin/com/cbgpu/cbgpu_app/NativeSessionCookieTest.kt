package com.cbgpu.cbgpu_app

import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSessionCookieTest {
    private fun session(owner: String, sid: String): JSONObject {
        val claims =
            JSONObject()
                .put("sub", owner)
                .put("session_id", sid)
                .put("exp", System.currentTimeMillis() / 1000 + 3600)
        return JSONObject()
            .put(
                "access_token",
                "a." +
                    Base64.encodeToString(
                        claims.toString().toByteArray(),
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                    ) +
                    ".b",
            )
            .put("refresh_token", "fixture-refresh-token")
            .put("user", JSONObject().put("id", owner))
    }

    private fun set(cookie: String) {
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setCookie("https://app.cbgpu.com/", cookie) {
                CookieManager.getInstance().flush()
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
    }

    private fun clear() {
        for (name in
            listOf(
                "sb-cbgpu-auth-token",
                "sb-cbgpu-auth-token.0",
                "cbgpu-shared-session-state",
            )) set("$name=; Domain=.cbgpu.com; Path=/; Secure; HttpOnly; Max-Age=0")
    }

    @Test
    fun nativeRefreshKeepsHttpOnlyFormatAndCannotReplaceAnotherLoginOrUndoLogout() {
        clear()
        try {
            NativeCookieSession.verified(session("owner-a", "session-a"))
            assertEquals("owner-a", NativeCookieSession.currentOwner())
            assertTrue(
                CookieManager.getInstance()
                    .getCookie("https://app.cbgpu.com/")
                    .contains("sb-cbgpu-auth-token.0="),
            )
            set(
                "sb-cbgpu-auth-token.0=" +
                    Uri.encode(session("owner-b", "session-b").toString()) +
                    "; Domain=.cbgpu.com; Path=/; Secure; HttpOnly; Max-Age=3600",
            )
            NativeCookieSession.verified(session("owner-a", "session-a"))
            assertEquals("owner-b", NativeCookieSession.currentOwner())
            NativeCookieSession.clear(session("owner-a", "session-a"))
            assertEquals("owner-b", NativeCookieSession.currentOwner())
            NativeCookieSession.clear(session("owner-b", "session-b"))
            assertNull(NativeCookieSession.currentOwner())
            NativeCookieSession.verified(session("owner-a", "session-a"))
            assertNull(NativeCookieSession.currentOwner())
        } finally {
            clear()
        }
    }

    @Test
    fun legacySessionMigratesToAndroidKeystoreWithoutLosingTokens() {
        clear()
        val c =
            androidx.test.core.app.ApplicationProvider.getApplicationContext<
                android.content.Context,
            >()
        val encrypted = c.getSharedPreferences("cbgpu_native_session", 0)
        val legacy = c.getSharedPreferences("FlutterSharedPreferences", 0)
        encrypted.edit().clear().commit()
        val fixture = session("owner-crypto", "session-crypto")
        legacy
            .edit()
            .putString(
                "flutter.sb-cbgpu-auth-token",
                JSONObject().put("currentSession", fixture).toString(),
            )
            .commit()
        try {
            val vault = SessionVault(c)
            val read =
                SessionVault::class.java.getDeclaredMethod("read").apply { isAccessible = true }
            assertEquals(
                fixture.getString("refresh_token"),
                (read.invoke(vault) as JSONObject).getString("refresh_token"),
            )
            val write =
                SessionVault::class.java.getDeclaredMethod("write", JSONObject::class.java).apply {
                    isAccessible = true
                }
            write.invoke(vault, fixture)
            assertFalse(
                encrypted.getString("session", "").orEmpty().contains("fixture-refresh-token"),
            )
            assertNull(legacy.getString("flutter.sb-cbgpu-auth-token", null))
            assertEquals("owner-crypto", vault.notificationOwner())
            assertEquals("owner-crypto", NativeCookieSession.currentOwner())
            assertEquals(
                fixture.getString("refresh_token"),
                vault.session()?.getString("refresh_token"),
            )
        } finally {
            encrypted.edit().clear().commit()
            legacy.edit().remove("flutter.sb-cbgpu-auth-token").commit()
            clear()
        }
    }

    @Test
    fun legacyOwnerlessNotificationNeverReachesSdkAutomaticDisplay() {
        // No attached Context exists: entering the SDK display branch would throw.
        NativeMessagingService()
            .handleIntent(
                android.content
                    .Intent("com.google.android.c2dm.intent.RECEIVE")
                    .putExtra("gcm.n.e", "1")
                    .putExtra("gcm.n.title", "Legacy private title"),
            )
    }
}
