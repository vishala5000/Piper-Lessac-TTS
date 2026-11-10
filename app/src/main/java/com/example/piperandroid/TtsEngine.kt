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
    private var initError: String? = null
    private val modelName = "en_US-lessac-medium"
    
    suspend fun initIfNeeded(): Boolean {
        if (tts != null) return true
        if (initError != null) return false
        
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()
        
        val onnxFile = File(modelDir, "$modelName.onnx")
        val jsonFile = File(modelDir, "$modelName.onnx.json")
        
        if (!onnxFile.exists() || !jsonFile.exists()) {
            Log.d("TtsEngine", "Downloading model (this may take a minute)...")
            // Use HuggingFace resolve URL for maximum reliability
            val onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/$modelName.onnx"
            val jsonUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/$modelName.onnx.json"
            
            try {
                downloadFile(onnxUrl, onnxFile)
                downloadFile(jsonUrl, jsonFile)
                Log.d("TtsEngine", "Model downloaded successfully.")
            } catch (e: Exception) {
                initError = "Model download failed. Check internet. (${e.message})"
                Log.e("TtsEngine", initError!!, e)
                return false
            }
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
            numThreads = 2, // Optimized for mobile CPU
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
            return true
        } catch (e: Exception) {
            initError = "TTS Initialization failed: ${e.message}"
            Log.e("TtsEngine", initError!!, e)
            return false
        }
    }

    private suspend fun downloadFile(urlStr: String, dest: File) {
        withContext(Dispatchers.IO) {
            URL(urlStr).openStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    suspend fun generateAudioStreaming(
        text: String, 
        onChunkGenerated: (samples: FloatArray, sampleRate: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val success = initIfNeeded()
        if (!success) {
            onError(initError ?: "Unknown initialization error")
            return
        }
        
        val cleanText = text.replace(Regex("\\s+"), " ").trim()
        val chunks = cleanText.split("(?<=[.!?])\\s+".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        withContext(Dispatchers.Default) {
            for (chunk in chunks) {
                try {
                    val result = tts!!.generate(chunk, sid = 0, speed = 1.0f)
                    onChunkGenerated(result.samples, result.sampleRate)
                } catch (e: Exception) {
                    Log.e("TtsGen", "Failed to generate chunk: $chunk", e)
                    onError("Generation failed: ${e.message}")
                    return@withContext
                }
            }
        }
    }
}
