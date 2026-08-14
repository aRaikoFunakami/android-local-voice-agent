package com.example.localvoiceagent

/**
 * liblocal_audio_engine.so（WebRTC APM/AEC3 ラッパ、fork の local_audio/）の Kotlin 境界。
 * .so は scripts/build_webrtc_android.sh が jniLibs/arm64-v8a/ に配置する（コミットしない）。
 * ネイティブ側は JNI_OnLoad + RegisterNatives で登録（Java_* 命名規約は不使用）。
 * processCapture/processRender 等の 5 操作は Issue #12 で追加する。
 */
object LocalAudioEngine {
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("local_audio_engine")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    fun status(): String =
        if (loaded) "engine: ${nativeGetVersion()}" else "engine: NOT loaded"

    @JvmStatic
    private external fun nativeGetVersion(): String
}
