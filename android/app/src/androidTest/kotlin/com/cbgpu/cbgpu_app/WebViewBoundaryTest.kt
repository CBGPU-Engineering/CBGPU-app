package com.cbgpu.cbgpu_app

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
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
    fun officialAdaptiveForegroundIsTransparentAndInsideSafeZone() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val drawable =
            androidx.core.content.ContextCompat.getDrawable(
                context,
                R.drawable.ic_launcher_foreground,
            )!!
        val bitmap =
            android.graphics.Bitmap.createBitmap(108, 108, android.graphics.Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 108, 108)
        drawable.draw(android.graphics.Canvas(bitmap))
        var pixels = 0
        for (y in 0 until 108) for (x in 0 until 108) {
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) {
                assertTrue(
                    "Brand artwork must stay inside adaptive safe zone",
                    x in 18..90 && y in 18..90,
                )
                pixels++
            }
        }
        assertTrue("Actual supplied symbol must be rendered", pixels > 500)
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // Public synthetic test image survives Gradle's normal APK/data uninstall cleanup.
            val values =
                android.content.ContentValues().apply {
                    put(
                        android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        "brand-foreground.png",
                    )
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/CBGPU-test",
                    )
                }
            val uri =
                context.contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                )!!
            context.contentResolver.openOutputStream(uri)!!.use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        bitmap.recycle()
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
            s.onActivity { activity ->
                // Actual HTTPS navigation gives Chromium a real document origin.
                // loadDataWithBaseURL
                // is an opaque synthetic document on some system WebViews and cannot prove the
                // production WebMessageListener source-origin boundary.
                val original = WebViewCompat.getWebViewClient(activity.web)
                activity.web.webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            if (request.isForMainFrame && request.url.path == "/__native_test__")
                                return WebResourceResponse(
                                    "text/html",
                                    "UTF-8",
                                    "<html><body>Bridge fixture</body></html>".byteInputStream(),
                                )
                            return original.shouldInterceptRequest(view, request)
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String,
                            icon: android.graphics.Bitmap?,
                        ) = original.onPageStarted(view, url, icon)

                        override fun onPageFinished(view: WebView, url: String) =
                            original.onPageFinished(view, url)

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = original.shouldOverrideUrlLoading(view, request)
                    }
                activity.web.loadUrl("https://app.cbgpu.com/__native_test__")
            }
            await(s, "typeof window.CBGPUNative", "\"object\"")
            evaluate(
                s,
                "window.answer=null;window.CBGPUNative.getAppInfo().then(v=>window.answer=v);true",
            )
            await(s, "window.answer&&window.answer.platform", "\"android\"")
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val scopePrefs = context.getSharedPreferences("cbgpu_widget_scope", 0)
            val before = scopePrefs.getLong("generation", 0)
            evaluate(s, "window.dispatchEvent(new Event('cbgpu-school-changed'));true")
            val deadline = System.currentTimeMillis() + 10000
            while (
                scopePrefs.getLong("generation", 0) <= before &&
                    System.currentTimeMillis() < deadline
            ) Thread.sleep(50)
            assertTrue(scopePrefs.getLong("generation", 0) > before)
            val mainGeneration = scopePrefs.getLong("generation", 0)

            evaluate(
                s,
                "window.childReplies=0;var original=window.CBGPUNative._resolve;window.CBGPUNative._resolve=function(id,ok,value){if(id==='forged-child')window.childReplies++;return original(id,ok,value)};var frame=document.createElement('iframe');frame.srcdoc='<script>setTimeout(()=>{if(window.CBGPUNativeChannel)window.CBGPUNativeChannel.postMessage(JSON.stringify({callId:\"forged-child\",action:\"invalidateSchoolWidgets\",payload:{}}))},100)<\\/script>';document.body.appendChild(frame);true",
            )
            Thread.sleep(500)
            assertEquals("0", evaluate(s, "window.childReplies"))
            assertEquals(mainGeneration, scopePrefs.getLong("generation", 0))
            s.onActivity { it.web.loadUrl("https://tenant.front.cbgpu.com/__native_test__") }
            await(s, "location.hostname", "\"tenant.front.cbgpu.com\"")
            assertEquals("\"undefined\"", evaluate(s, "typeof window.CBGPUNativeChannel"))
            assertEquals("\"undefined\"", evaluate(s, "typeof window.CBGPUNative"))
        }
    }
}
