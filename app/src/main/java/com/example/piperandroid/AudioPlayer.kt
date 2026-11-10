package com.example.piperandroid

import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPlayer {
    private var audioTrack: AudioTrack? = null

    fun play(samples: FloatArray, sampleRate: Int) {
        stop()
        
        val shortSamples = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            // Explicitly convert to Int first, then to Short
            shortSamples[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }

        val byteCount = shortSamples.size * 2
        val buffer = ByteBuffer.allocate(byteCount)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (s in shortSamples) {
            buffer.putShort(s)
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(minBufferSize, byteCount))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.write(buffer.array(), 0, byteCount)
        audioTrack?.play()
    }

    fun stop() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
