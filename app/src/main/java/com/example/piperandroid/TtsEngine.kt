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
    private val modelFolder = "piper-model"
    
    suspend fun initIfNeeded(): Boolean {
        if (tts != null) return true
        if (initError != null) return false
        
        val modelDir = File(context.filesDir, modelFolder)
        
        // Extract model from APK assets
        if (!modelDir.exists()) {
            try {
                extractAssetsToInternal(modelFolder, modelDir)
                Log.d("TtsEngine", "✅ Model extracted successfully.")
            } catch (e: Exception) {
                initError = "Failed to extract model: ${e.message}"
                Log.e("TtsEngine", initError!!, e)
                return false
            }
        }
        
        // Find required files
        val modelFile = findFile(modelDir, ".onnx")
        val tokensFile = File(modelDir, "tokens.txt")
        val espeakDataDir = File(modelDir, "espeak-ng-data")
        
        if (modelFile == null || !modelFile.exists()) {
            initError = "Missing .onnx model file"
            Log.e("TtsEngine", initError!!)
            return false
        }
        if (!tokensFile.exists()) {
            initError = "Missing tokens.txt"
            Log.e("TtsEngine", initError!!)
            return false
        }
        if (!espeakDataDir.exists()) {
            initError = "Missing espeak-ng-data folder"
            Log.e("TtsEngine", initError!!)
            return false
        }
        
        Log.d("TtsEngine", "📁 Model: ${modelFile.absolutePath}")
        Log.d("TtsEngine", "📁 Tokens: ${tokensFile.absolutePath}")
        Log.d("TtsEngine", "📁 espeak-ng: ${espeakDataDir.absolutePath}")
        
        try {
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                lexicon = "",
                tokens = tokensFile.absolutePath,
                dataDir = espeakDataDir.parentFile?.absolutePath ?: "",
                dictDir = "",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f
            )
            
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 4,
                debug = false
            )
            
            val config = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = "",
                maxNumSentences = 2
            )
            
            tts = OfflineTts(null, config)
            Log.d("TtsEngine", "✅ TTS Engine Initialized (100% Offline).")
            return true
        } catch (e: Exception) {
            initError = "TTS Init failed: ${e.message}"
            Log.e("TtsEngine", initError!!, e)
            return false
        }
    }

    private fun extractAssetsToInternal(assetFolder: String, destDir: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetFolder) ?: return
        
        if (!destDir.exists()) destDir.mkdirs()
        
        for (fileName in files) {
            val assetPath = "$assetFolder/$fileName"
            val destFile = File(destDir, fileName)
            
            val subFiles = assetManager.list(assetPath)
            if (subFiles != null && subFiles.isNotEmpty()) {
                extractAssetsToInternal(assetPath, destFile)
            } else {
                assetManager.open(assetPath).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun findFile(dir: File, extension: String): File? {
        return dir.listFiles()?.firstOrNull { it.name.endsWith(extension) }
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
                    if (result.samples.isNotEmpty()) {
                        onChunkGenerated(result.samples, result.sampleRate)
                    }
                } catch (e: Exception) {
                    Log.e("TtsGen", "Failed to generate chunk: $chunk", e)
                    onError("Generation failed: ${e.message}")
                    return@withContext
                }
            }
        }
    }
}
