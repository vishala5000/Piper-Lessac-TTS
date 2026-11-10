package com.example.piperandroid

import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPlayer {
    private var audioTrack: AudioTrack? = null

    fun init(sampleRate: Int) {
        stop()
        
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        // Use a buffer 4x the minimum for ultra-smooth, gapless streaming
        val bufferSize = maxOf(minBufferSize * 4, 32768)

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
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM) // CRITICAL for real-time chunked playback
            .build()
        
        audioTrack?.play()
    }

    fun playChunk(samples: FloatArray) {
        val track = audioTrack ?: return
        
        val shortSamples = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            shortSamples[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }

        val byteCount = shortSamples.size * 2
        val buffer = ByteBuffer.allocate(byteCount)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (s in shortSamples) {
            buffer.putShort(s)
        }

        // Stream the chunk directly to the audio hardware
        track.write(buffer.array(), 0, byteCount)
    }

    fun stop() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
    
    fun waitForCompletion() {
        audioTrack?.let { track ->
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                Thread.sleep(50)
            }
        }
    }
}
