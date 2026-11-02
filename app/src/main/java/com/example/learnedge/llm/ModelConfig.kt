package com.example.learnedge.llm

import android.content.Context
import java.io.File

/** The one place to change model file names, locations and generation settings. */
object ModelConfig {
    const val MODEL_FILE = "model.pte"
    const val TOKENIZER_FILE = "tokenizer.model"

    const val TEMPERATURE = 0.7f

    /** Max total tokens (prompt + output). The runner also caps this at the model's context length. */
    const val SEQ_LEN = 1536

    /** Directories searched in order. The first one holding both files wins. */
    fun searchDirs(context: Context): List<File> = listOfNotNull(
        // /sdcard/Android/data/com.example.learnedge/files — readable by the app without permissions.
        context.getExternalFilesDir(null),
        // Location used by the official LlamaDemo. Recent Android versions may block apps from reading it.
        File("/data/local/tmp/llama"),
    )

    fun findModelFiles(context: Context): ModelFiles? =
        searchDirs(context).firstNotNullOfOrNull { dir ->
            val model = File(dir, MODEL_FILE)
            val tokenizer = File(dir, TOKENIZER_FILE)
            if (model.canRead() && tokenizer.canRead()) ModelFiles(model, tokenizer) else null
        }

    fun missingFilesMessage(context: Context): String =
        "Model files not found. Expected $MODEL_FILE and $TOKENIZER_FILE in one of:\n" +
            searchDirs(context).joinToString("\n") { "• ${it.absolutePath}" }
}

data class ModelFiles(val model: File, val tokenizer: File)
