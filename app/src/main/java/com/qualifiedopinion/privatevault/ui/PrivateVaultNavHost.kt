package com.qualifiedopinion.privatevault.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.qualifiedopinion.privatevault.AppGraph

/**
 * Navigation shell. PR3 has one destination: the vault flow (setup → lock → unlocked, or the
 * "folder not available" screen), driven by state rather than the back stack, so Back can never
 * return to an unlocked screen. Grid, viewer and player routes arrive in PR5/PR6.
 */
@Composable
fun PrivateVaultNavHost(graph: AppGraph, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier.fillMaxSize(),
    ) {
        composable(Routes.HOME) {
            Surface(modifier = Modifier.fillMaxSize()) {
                VaultScreen(viewModel { VaultViewModel(graph) })
            }
        }
    }
}
