package com.example.localvoiceagent

/**
 * sherpa-onnx ランタイム（STT/TTS/VAD 共通）のロード管理。
 * モデルファイルは初回配置方式（scripts/ 参照）。モデルがなくてもロード自体は成功し、
 * Recognizer/Tts の生成は各機能の Issue (#18, #19) で行う。
 */
object SherpaRuntime {
    const val VERSION = "1.13.5"

    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    fun status(): String =
        if (loaded) "sherpa-onnx $VERSION: loaded" else "sherpa-onnx $VERSION: NOT loaded"
}
