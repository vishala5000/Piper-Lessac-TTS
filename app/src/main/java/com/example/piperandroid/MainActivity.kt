package com.example.piperandroid

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var editText: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnDownload: Button
    private val ttsEngine by lazy { TtsEngine(this) }
    private val player = AudioPlayer()
    
    private var allGeneratedSamples = mutableListOf<Short>()
    private var lastSampleRate: Int = 0
    private var isGenerating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editText = findViewById(R.id.editTextText)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnDownload = findViewById(R.id.btnDownload)

        btnGenerate.setOnClickListener {
            val text = editText.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Please enter text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isGenerating) return@setOnClickListener
            isGenerating = true
            btnGenerate.text = "Initializing..."
            btnGenerate.isEnabled = false
            btnDownload.isEnabled = false
            
            allGeneratedSamples.clear()
            lastSampleRate = 0

            lifecycleScope.launch {
                ttsEngine.generateAudioStreaming(
                    text = text,
                    onChunkGenerated = { samples, sampleRate ->
                        if (lastSampleRate == 0) {
                            lastSampleRate = sampleRate
                            player.init(sampleRate)
                            runOnUiThread { btnGenerate.text = "Generating & Playing..." }
                        }
                        
                        val shortChunk = ShortArray(samples.size)
                        for (i in samples.indices) {
                            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
                            shortChunk[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
                        }
                        allGeneratedSamples.addAll(shortChunk.toList())
                        
                        // Real-time auto-play
                        player.playChunk(samples)
                    },
                    onError = { errorMsg ->
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                            isGenerating = false
                            btnGenerate.text = "Generate & Play"
                            btnGenerate.isEnabled = true
                        }
                    }
                )
                
                if (lastSampleRate > 0) {
                    player.waitForCompletion()
                }
                
                isGenerating = false
                btnGenerate.text = "Generate & Play"
                btnGenerate.isEnabled = true
                
                if (lastSampleRate > 0) {
                    btnDownload.isEnabled = true
                    Toast.makeText(this@MainActivity, "Playback Complete", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnDownload.setOnClickListener {
            if (allGeneratedSamples.isEmpty() || lastSampleRate == 0) {
                Toast.makeText(this, "Generate audio first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            lifecycleScope.launch {
                saveWavFilePerfect(lastSampleRate)
            }
        }
    }

    private suspend fun saveWavFilePerfect(sampleRate: Int) {
        // Saves to App Internal Storage (No permissions required, 100% offline)
        val dir = File(filesDir, "audio")
        if (!dir.exists()) dir.mkdirs()
        
        val fileName = "piper_offline_${System.currentTimeMillis()}.wav"
        val file = File(dir, fileName)
        
        try {
            val shortSamples = allGeneratedSamples.toShortArray()
            val totalDataLen = shortSamples.size * 2

            FileOutputStream(file).use { fos ->
                writeWavHeader(fos, totalDataLen, sampleRate, 1, 16)
                val buffer = java.nio.ByteBuffer.allocate(totalDataLen)
                buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (s in shortSamples) buffer.putShort(s)
                fos.write(buffer.array())
            }
            
            runOnUiThread {
                Toast.makeText(this, "Saved to Internal Storage:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun writeWavHeader(out: FileOutputStream, totalDataLen: Int, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val headerSize = 44
        
        out.write("RIFF".toByteArray())
        out.write(intToByteArray(headerSize + totalDataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToByteArray(16))
        out.write(shortToByteArray(1))
        out.write(shortToByteArray(channels.toShort()))
        out.write(intToByteArray(sampleRate))
        out.write(intToByteArray(byteRate))
        out.write(shortToByteArray(blockAlign.toShort()))
        out.write(shortToByteArray(bitsPerSample.toShort()))
        out.write("data".toByteArray())
        out.write(intToByteArray(totalDataLen))
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(value.toByte(), (value.toInt() shr 8).toByte())
    }
    
    override fun onDestroy() {
        super.onDestroy()
        player.stop()
    }
}
