package com.example.learnedge.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.learnedge.ui.components.PlaceholderScreen

@Composable
fun TodayScreen(onEditProfile: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        PlaceholderScreen("Today", "Today's study topics will appear here.")
        OutlinedButton(onClick = onEditProfile, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
            Text("Edit learning profile")
        }
    }
}
