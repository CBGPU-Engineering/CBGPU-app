package com.cbgpu.cbgpu_app

object NativeCore {
    init {
        System.loadLibrary("cbgpu_core")
    }

    @JvmStatic external fun route(url: String): String

    @JvmStatic external fun bridgeAllowed(url: String): Boolean

    @JvmStatic external fun validMessage(json: String): Boolean
}
