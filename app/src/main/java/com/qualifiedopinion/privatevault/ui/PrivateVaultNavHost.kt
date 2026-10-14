package com.qualifiedopinion.privatevault.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/** Empty navigation shell for PR1: a single blank destination. */
@Composable
fun PrivateVaultNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier.fillMaxSize(),
    ) {
        composable(Routes.HOME) {
            Surface(modifier = Modifier.fillMaxSize()) {}
        }
    }
}
