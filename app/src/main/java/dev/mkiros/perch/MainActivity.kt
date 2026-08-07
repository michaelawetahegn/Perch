package dev.mkiros.perch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkiros.perch.data.settings.PerchSettings
import dev.mkiros.perch.ui.nav.PerchNavHost
import dev.mkiros.perch.ui.theme.PerchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge per DESIGN.md §4 — screens respect insets, nothing hardcodes a
        // status-bar height. Must run before setContent.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as PerchApp).container
        setContent {
            // The theme choice is read here, above the nav graph, so that changing it in
            // Settings recolours the whole app in place rather than only the screen that
            // was showing when it changed. The default stands in for the one frame before
            // DataStore's first emission, which is the same value on every launch but the
            // first one after a change.
            val settings by container.settings.settings
                .collectAsStateWithLifecycle(initialValue = PerchSettings())
            PerchTheme(mode = settings.themeMode) {
                PerchNavHost(container = container)
            }
        }
    }
}
