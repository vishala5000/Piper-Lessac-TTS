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
            Log.d("TtsEngine", "Downloading model...")
            val onnxUrl = "https://raw.githubusercontent.com/rhasspy/piper-voices/main/en/en_US/lessac/medium/$modelName.onnx"
            val jsonUrl = "https://raw.githubusercontent.com/rhasspy/piper-voices/main/en/en_US/lessac/medium/$modelName.onnx.json"
            
            downloadFile(onnxUrl, onnxFile)
            downloadFile(jsonUrl, jsonFile)
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
            numThreads = 4,
            debug = false
        )
        
        val config = OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = "",
            maxNumSentences = 1
        )
        
        try {
            // Pass null for AssetManager since we are using absolute file paths
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

    suspend fun generateAudio(text: String): Pair<FloatArray, Int>? {
        initIfNeeded()
        if (tts == null) return null
        
        return withContext(Dispatchers.Default) {
            try {
                // Use 1.0f to explicitly denote a Float literal
                val result = tts!!.generate(text, sid = 0, speed = 1.0f)
                Pair(result.samples, result.sampleRate)
            } catch (e: Exception) {
                Log.e("TtsGen", "Generation failed", e)
                null
            }
        }
    }
}
