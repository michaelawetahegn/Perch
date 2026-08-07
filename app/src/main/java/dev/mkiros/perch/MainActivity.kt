package dev.mkiros.perch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.mkiros.perch.ui.nav.PerchNavHost
import dev.mkiros.perch.ui.theme.PerchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge per DESIGN.md §4 — screens respect insets, nothing hardcodes a
        // status-bar height. Must run before setContent.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PerchTheme {
                PerchNavHost(container = (application as PerchApp).container)
            }
        }
    }
}
