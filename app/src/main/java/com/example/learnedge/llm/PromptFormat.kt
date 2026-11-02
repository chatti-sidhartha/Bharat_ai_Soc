package com.example.learnedge.llm

/** Llama 3.x Instruct chat template. */
object PromptFormat {
    data class Turn(val fromUser: Boolean, val text: String)

    private val SPECIAL_TOKEN = Regex("<\\|[a-z_]+\\|>")

    fun llama3(system: String, history: List<Turn>, userMessage: String): String = buildString {
        append("<|begin_of_text|>")
        appendTurn("system", system)
        history.forEach { appendTurn(if (it.fromUser) "user" else "assistant", it.text) }
        appendTurn("user", userMessage)
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    /** Removes special tokens such as <|eot_id|> from model output. */
    fun clean(token: String): String = token.replace(SPECIAL_TOKEN, "")

    private fun StringBuilder.appendTurn(role: String, text: String) {
        append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
        append(text.trim()).append("<|eot_id|>")
    }
}
