package com.qualifiedopinion.privatevault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qualifiedopinion.privatevault.ui.PrivateVaultNavHost
import com.qualifiedopinion.privatevault.ui.theme.PrivateVaultTheme
import com.qualifiedopinion.privatevault.vault.VaultAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording or app-switcher preview of vault content (or of the PIN pad).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val graph = (application as PrivateVaultApp).graph
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Planning pack §5.4: check grant, volume and vault id on every resume…
                launch(Dispatchers.IO) {
                    if (graph.monitor.state.value !is VaultAvailability.Unknown) graph.monitor.fullCheck()
                }
                // …and poll grant + volume every second while visible, so a revoked grant locks within 1 s
                // even though Android sends no broadcast for it.
                withContext(Dispatchers.Default) { graph.monitor.pollWhileActive() }
            }
        }
        setContent {
            PrivateVaultTheme {
                PrivateVaultNavHost(graph)
            }
        }
    }
}
