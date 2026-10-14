package com.qualifiedopinion.privatevault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.qualifiedopinion.privatevault.ui.PrivateVaultNavHost
import com.qualifiedopinion.privatevault.ui.theme.PrivateVaultTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PrivateVaultTheme {
                PrivateVaultNavHost()
            }
        }
    }
}
