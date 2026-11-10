package com.example.piperandroid

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        
        // Copy from APK assets to internal storage if not already there
        if (!onnxFile.exists() || !jsonFile.exists()) {
            try {
                copyAssetToFile("$modelName.onnx", onnxFile)
                copyAssetToFile("$modelName.onnx.json", jsonFile)
                Log.d("TtsEngine", "Model extracted from assets successfully.")
            } catch (e: Exception) {
                initError = "Failed to extract model from assets: ${e.message}"
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
            numThreads = 4, // Maximize offline generation speed
            debug = false
        )
        
        val config = OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = "",
            maxNumSentences = 1
        )
        
        try {
            tts = OfflineTts(null, config)
            Log.d("TtsEngine", "TTS Engine Initialized successfully (100% Offline).")
            return true
        } catch (e: Exception) {
            initError = "TTS Initialization failed: ${e.message}"
            Log.e("TtsEngine", initError!!, e)
            return false
        }
    }

    private fun copyAssetToFile(assetName: String, destFile: File) {
        context.assets.open(assetName).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
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
        // Split only on sentence endings for perfect, natural prosody
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
