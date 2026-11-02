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
            if (isUsable(model) && isUsable(tokenizer)) ModelFiles(model, tokenizer) else null
        }

    /** Says, per folder, which file is missing, unreadable or empty, so the fix is obvious on screen. */
    fun missingFilesMessage(context: Context): String = buildString {
        append("On-device model not found. Both $MODEL_FILE and $TOKENIZER_FILE must be in one of these folders:")
        searchDirs(context).forEach { dir ->
            append("\n\n${dir.absolutePath}")
            listOf(MODEL_FILE, TOKENIZER_FILE).forEach { name ->
                append("\n• $name: ${describe(File(dir, name))}")
            }
        }
        append("\n\nCopy them with adb push (see app/CLAUDE.md), then tap On-device again.")
    }

    fun describe(file: File): String = when {
        !file.exists() -> "missing"
        !file.canRead() -> "not readable by the app"
        file.length() == 0L -> "empty (0 bytes)"
        else -> "found (${file.length() / 1_000_000} MB)"
    }

    private fun isUsable(file: File) = file.canRead() && file.length() > 0
}

data class ModelFiles(val model: File, val tokenizer: File)
