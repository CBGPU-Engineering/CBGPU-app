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
}
