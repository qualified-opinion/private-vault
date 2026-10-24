package com.qualifiedopinion.privatevault.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qualifiedopinion.privatevault.R
import com.qualifiedopinion.privatevault.ui.common.MessagePanel
import com.qualifiedopinion.privatevault.ui.common.PinPad

/** Setup start: explain, then open the system folder picker. */
@Composable
fun WelcomeScreen(hasCard: Boolean, error: Int?, onPickFolder: () -> Unit) {
    MessagePanel(
        title = stringResource(R.string.setup_title),
        body = stringResource(if (hasCard) R.string.setup_body_card else R.string.setup_body_no_card),
        error = error?.let { stringResource(it) },
    ) {
        Button(onClick = onPickFolder) { Text(stringResource(R.string.setup_pick_folder)) }
    }
}

/** Planning pack §5.3/§5.5: internal storage is allowed only as an explicit choice, after this warning. */
@Composable
fun PrimaryWarningScreen(onContinue: () -> Unit, onPickAnother: () -> Unit) {
    MessagePanel(
        title = stringResource(R.string.primary_warning_title),
        body = stringResource(R.string.primary_warning_body),
    ) {
        Button(onClick = onPickAnother) { Text(stringResource(R.string.pick_another_folder)) }
        OutlinedButton(onClick = onContinue) { Text(stringResource(R.string.primary_warning_continue)) }
    }
}

@Composable
fun ExistingVaultScreen(canOpen: Boolean, onUse: () -> Unit, onPickAnother: () -> Unit) {
    MessagePanel(
        title = stringResource(R.string.existing_vault_title),
        body = stringResource(if (canOpen) R.string.existing_vault_body else R.string.existing_vault_no_key),
    ) {
        if (canOpen) Button(onClick = onUse) { Text(stringResource(R.string.existing_vault_use)) }
        OutlinedButton(onClick = onPickAnother) { Text(stringResource(R.string.pick_another_folder)) }
    }
}

@Composable
fun SetPinScreen(confirming: Boolean, error: Int?, onSubmit: (ByteArray) -> Unit) {
    PinPad(
        title = stringResource(if (confirming) R.string.pin_confirm_title else R.string.pin_set_title),
        error = error?.let { stringResource(it) },
        onSubmit = onSubmit,
    )
}

/** "Forgot PIN and lost phrase = data lost" (planning pack §4.5), confirmed with a checkbox. */
@Composable
fun ConfirmDataLossScreen(onCreate: () -> Unit, onCancel: () -> Unit) {
    var understood by rememberSaveable { mutableStateOf(false) }
    MessagePanel(
        title = stringResource(R.string.data_loss_title),
        body = stringResource(R.string.data_loss_body),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = understood, onCheckedChange = { understood = it })
            Text(stringResource(R.string.data_loss_checkbox), modifier = Modifier.padding(start = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCreate, enabled = understood) { Text(stringResource(R.string.data_loss_create)) }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}
