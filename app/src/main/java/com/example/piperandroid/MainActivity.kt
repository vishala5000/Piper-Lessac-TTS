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
    
    private var lastGeneratedSamples: FloatArray? = null
    private var lastSampleRate: Int = 0

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

            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "Generating...", Toast.LENGTH_SHORT).show()
                val result = ttsEngine.generateAudio(text)
                
                if (result != null) {
                    lastGeneratedSamples = result.first
                    lastSampleRate = result.second
                    
                    player.play(lastGeneratedSamples!!, lastSampleRate)
                    Toast.makeText(this@MainActivity, "Playing...", Toast.LENGTH_SHORT).show()
                    btnDownload.isEnabled = true
                } else {
                    Toast.makeText(this@MainActivity, "Generation Failed", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnDownload.setOnClickListener {
            if (lastGeneratedSamples == null) {
                Toast.makeText(this, "Generate audio first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            lifecycleScope.launch {
                saveWavFile(lastGeneratedSamples!!, lastSampleRate)
            }
        }
    }

    private suspend fun saveWavFile(samples: FloatArray, sampleRate: Int) {
        val dir = File(filesDir, "audio")
        if (!dir.exists()) dir.mkdirs()
        
        val fileName = "output_${System.currentTimeMillis()}.wav"
        val file = File(dir, fileName)
        
        try {
            val shortSamples = ShortArray(samples.size)
            for (i in samples.indices) {
                val clamped = samples[i].coerceIn(-1.0f, 1.0f)
                // Explicitly convert to Int first, then to Short
                shortSamples[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
            }

            FileOutputStream(file).use { fos ->
                writeWavHeader(fos, shortSamples.size, sampleRate, 1, 16)
                val buffer = java.nio.ByteBuffer.allocate(shortSamples.size * 2)
                buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (s in shortSamples) buffer.putShort(s)
                fos.write(buffer.array())
            }
            
            runOnUiThread {
                Toast.makeText(this, "Saved to Internal Storage: ${file.absolutePath}", Toast.LENGTH_LONG).show()
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
        return byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            value.toByte(),
            (value.toInt() shr 8).toByte()
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        player.stop()
    }
}
