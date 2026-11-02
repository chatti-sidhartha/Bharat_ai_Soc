package com.example.learnedge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.learnedge.ui.AppRoot
import com.example.learnedge.ui.theme.LearnEdgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LearnEdgeTheme {
                AppRoot()
            }
        }
    }
}
