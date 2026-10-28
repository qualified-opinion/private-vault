package com.qualifiedopinion.privatevault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qualifiedopinion.privatevault.ui.lock.LockScreen
import com.qualifiedopinion.privatevault.ui.lock.VaultUnavailableScreen
import com.qualifiedopinion.privatevault.ui.setup.ConfirmDataLossScreen
import com.qualifiedopinion.privatevault.ui.setup.ExistingVaultScreen
import com.qualifiedopinion.privatevault.ui.setup.PrimaryWarningScreen
import com.qualifiedopinion.privatevault.ui.setup.SetPinScreen
import com.qualifiedopinion.privatevault.ui.setup.WelcomeScreen
import com.qualifiedopinion.privatevault.ui.unlocked.UnlockedScreen

/** Renders [VaultViewModel.screen] and owns the two folder-picker launchers (setup and re-pick). */
@Composable
fun VaultScreen(vm: VaultViewModel, modifier: Modifier = Modifier) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val pickSetup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), vm::onFolderPicked)
    val pickAgain = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), vm::onRepicked)
    // The system photo picker: read access to the chosen items only, no storage permission.
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
        vm::onImportPicked,
    )

    Box(modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        when (val s = screen) {
            Screen.Loading -> Progress(null)
            is Screen.Working -> Progress(stringResource(s.message))
            is Screen.Welcome -> WelcomeScreen(s.hasCard, s.error) { pickSetup.launch(vm.initialPickerUri()) }
            Screen.PrimaryWarning -> PrimaryWarningScreen(vm::onPrimaryWarningAccepted, vm::onPickAnother)
            is Screen.ExistingVault -> ExistingVaultScreen(s.canOpen, vm::onUseExistingVault, vm::onPickAnother)
            is Screen.SetPin -> SetPinScreen(s.confirming, s.error, vm::onSetupPin)
            Screen.ConfirmDataLoss -> ConfirmDataLossScreen(vm::onCreateVault, vm::onPickAnother)
            is Screen.Locked -> LockScreen(s.error, vm::onUnlock)
            is Screen.Unlocked -> UnlockedScreen(
                itemCount = items.size,
                readOnly = s.readOnly,
                importState = importState,
                onImport = { pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) },
                onImportDismissed = vm::onImportDismissed,
                onLock = vm::onLock,
            )
            is Screen.Unavailable -> VaultUnavailableScreen(s.reason, s.error, vm::onRetry) {
                pickAgain.launch(vm.initialPickerUri())
            }
        }
    }
}

@Composable
private fun Progress(message: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        if (message != null) Text(message)
    }
}
