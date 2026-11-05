package com.cbgpu.cbgpu_app

import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.cbgpu/app_info")
            .setMethodCallHandler { call, result ->
                if (call.method != "getAppInfo") {
                    result.notImplemented()
                } else {
                    try {
                        @Suppress("DEPRECATION")
                        val info = packageManager.getPackageInfo(packageName, 0)
                        @Suppress("DEPRECATION")
                        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                        result.success(mapOf("version" to info.versionName, "buildNumber" to code, "platform" to "android"))
                    } catch (_: Exception) {
                        result.error("VERSION_UNAVAILABLE", "Could not read installed version", null)
                    }
                }
            }
    }
}
