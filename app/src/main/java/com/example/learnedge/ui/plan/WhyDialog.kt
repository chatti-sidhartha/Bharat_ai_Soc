package com.example.learnedge.ui.plan

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.example.learnedge.domain.ReasonText

/**
 * Explains why the planner put [item] where it is, in two or three sentences built from fixed
 * templates (no language model). [whenText] is "today" or "on Tue 17 Nov"; it is ignored for deferred topics.
 */
@Composable
fun WhyDialog(item: StudyItemUi, whenText: String, onDismiss: () -> Unit) {
    val reason = item.reason
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.topicName) },
        text = {
            Text(
                if (reason != null) ReasonText.render(reason, whenText, item.deferredReason) else "No explanation is available for this topic.",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
