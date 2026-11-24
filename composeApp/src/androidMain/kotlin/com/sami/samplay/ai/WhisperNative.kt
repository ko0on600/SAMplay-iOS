package com.sami.samplay.ai

import java.io.File

/** JNI bindings to whisper.cpp (libsamplay_whisper.so, built with the NDK for arm64-v8a). */
object WhisperNative {
    @Volatile private var loaded = false

    /** whisper.cpp is compiled for ARMv8.2-A + FP16 + dot-product; check the CPU before loading. */
    fun cpuSupported(): Boolean = runCatching {
        val features = File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features", ignoreCase = true) }
            .joinToString(" ")
        features.contains("asimdhp") && features.contains("asimddp")
    }.getOrDefault(false)

    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("samplay_whisper")
            loaded = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic external fun nativeInit(modelPath: String): Long
    @JvmStatic external fun nativeFree(ctx: Long)
    @JvmStatic external fun nativeCancel(cancel: Boolean)
    @JvmStatic external fun nativeProgress(): Int
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeTranscribe(
        ctx: Long, samples: FloatArray, language: String, translate: Boolean, threads: Int, offsetMs: Long,
    ): Array<String>?
}
