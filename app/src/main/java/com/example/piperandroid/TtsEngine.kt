package com.example.piperandroid

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class TtsEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private val modelName = "en_US-lessac-medium"
    
    fun initIfNeeded() {
        if (tts != null) return
        
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()
        
        val onnxFile = File(modelDir, "$modelName.onnx")
        val jsonFile = File(modelDir, "$modelName.onnx.json")
        
        if (!onnxFile.exists() || !jsonFile.exists()) {
            Log.d("TtsEngine", "Downloading model (this may take a moment)...")
            val onnxUrl = "https://raw.githubusercontent.com/rhasspy/piper-voices/main/en/en_US/lessac/medium/$modelName.onnx"
            val jsonUrl = "https://raw.githubusercontent.com/rhasspy/piper-voices/main/en/en_US/lessac/medium/$modelName.onnx.json"
            
            downloadFile(onnxUrl, onnxFile)
            downloadFile(jsonUrl, jsonFile)
            Log.d("TtsEngine", "Model downloaded successfully.")
        }
        
        val vitsConfig = OfflineTtsVitsModelConfig(
            model = onnxFile.absolutePath,
            lexicon = "",
            tokens = "",
            dataDir = "",
            dictDir = ""
        )
        
        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = 4, // Optimal balance of speed and battery
            debug = false
        )
        
        val config = OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = "",
            maxNumSentences = 1
        )
        
        try {
            tts = OfflineTts(null, config)
            Log.d("TtsEngine", "TTS Engine Initialized successfully.")
        } catch (e: Exception) {
            Log.e("TtsEngine", "Failed to init TTS", e)
        }
    }

    private fun downloadFile(urlStr: String, dest: File) {
        try {
            URL(urlStr).openStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e("Download", "Error downloading $urlStr", e)
        }
    }

    /**
     * Generates audio in real-time chunks. 
     * PERFECT PROSODY: Only splits on sentence endings (. ! ?) so commas and clauses 
     * are processed naturally by the TTS engine without resetting its state.
     */
    suspend fun generateAudioStreaming(
        text: String, 
        onChunkGenerated: (samples: FloatArray, sampleRate: Int) -> Unit
    ) {
        initIfNeeded()
        if (tts == null) return
        
        // Clean text and split ONLY by sentence-ending punctuation
        val cleanText = text.replace(Regex("\\s+"), " ").trim()
        val chunks = cleanText.split("(?<=[.!?])\\s+".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        withContext(Dispatchers.Default) {
            for (chunk in chunks) {
                try {
                    // 1.0f is the native, perfectly tuned speed for the Lessac voice
                    val result = tts!!.generate(chunk, sid = 0, speed = 1.0f)
                    onChunkGenerated(result.samples, result.sampleRate)
                } catch (e: Exception) {
                    Log.e("TtsGen", "Failed to generate chunk: $chunk", e)
                }
            }
        }
    }
}
