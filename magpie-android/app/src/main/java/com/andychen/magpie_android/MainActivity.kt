package com.andychen.magpie_android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.andychen.magpie_android.ui.MagpieApp
import com.andychen.magpie_android.ui.ThemeMode
import com.andychen.magpie_android.ui.theme.MagpieTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val preferences = getPreferences(MODE_PRIVATE)
        setContent {
            var theme by remember {
                val savedTheme = preferences.getString("theme", null)
                mutableStateOf(ThemeMode.entries.firstOrNull { it.name == savedTheme } ?: ThemeMode.System)
            }
            MagpieTheme(theme) {
                MagpieApp(theme) { selected ->
                    theme = selected
                    preferences.edit { putString("theme", selected.name) }
                }
            }
        }
    }
}
