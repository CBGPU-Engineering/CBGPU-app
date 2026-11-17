package com.cbgpu.cbgpu_app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewBoundaryTest {
    private fun evaluate(s: ActivityScenario<MainActivity>, script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        s.onActivity {
            it.web.evaluateJavascript(script) { v ->
                result.set(v)
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun await(s: ActivityScenario<MainActivity>, script: String, expected: String) {
        val end = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < end) {
            if (evaluate(s, script) == expected) return
            Thread.sleep(100)
        }
        assertEquals(expected, evaluate(s, script))
    }

    private fun scenario(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ActivityScenario.launch(
            Intent(context, MainActivity::class.java).putExtra("cbgpu_test_mode", true),
        )
    }

    @Test
    fun jniPolicyRejectsUntrustedAndKeepsDesktopLinksMobile() {
        assertTrue(NativeCore.bridgeAllowed("https://app.cbgpu.com/ai"))
        for (url in
            listOf(
                "https://x.front.cbgpu.com",
                "https://gpu.cbgpu.com",
                "https://app.cbgpu.com.evil.test",
                "http://app.cbgpu.com",
            )) assertFalse(NativeCore.bridgeAllowed(url))
        assertTrue(
            NativeCore.route("https://cbgpu.com/study").contains("https://app.cbgpu.com/study"),
        )
        assertTrue(NativeCore.route("javascript:alert(1)").contains("deny"))
    }

    @Test
    fun onlyTrustedMainFrameHasPrivilegedBridge() {
        scenario().use { s ->
            s.onActivity {
                it.web.loadDataWithBaseURL(
                    "https://app.cbgpu.com/",
                    "<html><body>Native bridge probe</body></html>",
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            await(s, "typeof window.CBGPUNative", "\"object\"")
            evaluate(
                s,
                "window.answer=null;window.CBGPUNative.getAppInfo().then(v=>window.answer=v);true",
            )
            await(s, "window.answer&&window.answer.platform", "\"android\"")
            evaluate(
                s,
                "window.childReplies=0;var original=window.CBGPUNative._resolve;window.CBGPUNative._resolve=function(id,ok,value){if(id==='forged-child')window.childReplies++;return original(id,ok,value)};var frame=document.createElement('iframe');frame.srcdoc='<script>setTimeout(()=>{if(window.CBGPUNativeChannel)window.CBGPUNativeChannel.postMessage(JSON.stringify({callId:\"forged-child\",action:\"getAppInfo\",payload:{}}))},100)<\\/script>';document.body.appendChild(frame);true",
            )
            Thread.sleep(500)
            assertEquals("0", evaluate(s, "window.childReplies"))
            s.onActivity {
                it.web.loadDataWithBaseURL(
                    "https://tenant.front.cbgpu.com/",
                    "<html><body>Untrusted tenant</body></html>",
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            await(s, "location.hostname", "\"tenant.front.cbgpu.com\"")
            assertEquals("\"undefined\"", evaluate(s, "typeof window.CBGPUNativeChannel"))
            assertEquals("\"undefined\"", evaluate(s, "typeof window.CBGPUNative"))
        }
    }
}
