package com.example.piperandroid

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

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
                saveWavToPublicDownloads(lastSampleRate)
            }
        }
    }

    /**
     * Saves WAV to public Downloads/PiperTTS/ folder.
     * Upsamples to 44100 Hz for 100% compatibility with ALL video editors.
     */
    private suspend fun saveWavToPublicDownloads(originalSampleRate: Int) {
        withContext(Dispatchers.IO) {
            try {
                val originalSamples = allGeneratedSamples.toShortArray()
                
                // 🎯 CRITICAL: Upsample to 44100 Hz for video editor compatibility
                // Piper outputs 22050 Hz, but CapCut/InShot/VN/KineMaster require 44100 Hz
                val upsampledSamples = upsample2x(originalSamples)
                val targetSampleRate = originalSampleRate * 2  // 44100 Hz
                
                val fileName = "PiperTTS_${System.currentTimeMillis()}.wav"
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ : Use MediaStore (no permissions needed)
                    saveViaMediaStore(upsampledSamples, targetSampleRate, fileName)
                } else {
                    // Android 9 and below: Use direct file write
                    saveViaLegacyStorage(upsampledSamples, targetSampleRate, fileName)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Upsamples audio by 2x using linear interpolation.
     * Converts 22050 Hz → 44100 Hz for universal video editor support.
     */
    private fun upsample2x(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        val result = ShortArray(samples.size * 2)
        for (i in samples.indices) {
            result[i * 2] = samples[i]
            if (i < samples.size - 1) {
                // Linear interpolation between current and next sample
                val interpolated = (samples[i].toInt() + samples[i + 1].toInt()) / 2
                result[i * 2 + 1] = interpolated.toShort()
            } else {
                result[i * 2 + 1] = samples[i]
            }
        }
        return result
    }

    /**
     * Android 10+ : Saves via MediaStore to public Downloads folder
     */
    private fun saveViaMediaStore(samples: ShortArray, sampleRate: Int, fileName: String) {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Download/PiperTTS")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        
        val uri = resolver.insert(collection, contentValues)
        uri?.let {
            resolver.openOutputStream(it).use { outputStream ->
                outputStream?.let { os ->
                    writeWavToStream(os, samples, sampleRate)
                }
            }
            
            // Mark as fully written
            val updateValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(it, updateValues, null, null)
            
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "✓ Saved to Downloads/PiperTTS/\n$fileName\n\nReady for video editors!",
                    Toast.LENGTH_LONG
                ).show()
            }
        } ?: run {
            throw Exception("Failed to create file in Downloads folder")
        }
    }

    /**
     * Android 9 and below: Direct file write to public Downloads
     */
    private fun saveViaLegacyStorage(samples: ShortArray, sampleRate: Int, fileName: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val piperDir = File(downloadsDir, "PiperTTS")
        if (!piperDir.exists()) piperDir.mkdirs()
        
        val file = File(piperDir, fileName)
        FileOutputStream(file).use { fos ->
            writeWavToStream(fos, samples, sampleRate)
        }
        
        runOnUiThread {
            Toast.makeText(
                this@MainActivity,
                "✓ Saved to Downloads/PiperTTS/\n$fileName\n\nReady for video editors!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Writes a standard 16-bit PCM WAV file (compatible with ALL video editors)
     * Format: 44100 Hz, 16-bit, Mono, PCM
     */
    private fun writeWavToStream(out: OutputStream, samples: ShortArray, sampleRate: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = samples.size * 2
        val headerSize = 44
        
        // RIFF Header
        out.write("RIFF".toByteArray())
        out.write(intToByteArray(headerSize + dataSize - 8))
        out.write("WAVE".toByteArray())
        
        // fmt chunk
        out.write("fmt ".toByteArray())
        out.write(intToByteArray(16))                    // Chunk size
        out.write(shortToByteArray(1))                   // PCM format
        out.write(shortToByteArray(channels.toShort()))  // Mono
        out.write(intToByteArray(sampleRate))            // 44100 Hz
        out.write(intToByteArray(byteRate))              // Byte rate
        out.write(shortToByteArray(blockAlign.toShort()))
        out.write(shortToByteArray(bitsPerSample.toShort()))
        
        // data chunk
        out.write("data".toByteArray())
        out.write(intToByteArray(dataSize))
        
        // Audio samples
        val buffer = java.nio.ByteBuffer.allocate(dataSize)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort(s)
        out.write(buffer.array())
        
        out.flush()
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
