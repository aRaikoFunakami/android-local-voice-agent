package com.example.localvoiceagent.tts

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 検聴用の最小 WAV (PCM16) ライタ。debug 用途のみ。 */
object WavWriter {
    fun write(file: File, samples: ShortArray, sampleRate: Int, channels: Int = 1) {
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        buf.putInt(sampleRate).putInt(sampleRate * channels * 2)
        buf.putShort((channels * 2).toShort()).putShort(16)
        buf.put("data".toByteArray()).putInt(dataSize)
        samples.forEach { buf.putShort(it) }
        RandomAccessFile(file, "rw").use { it.setLength(0); it.write(buf.array()) }
    }
}
