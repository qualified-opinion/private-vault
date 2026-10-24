package com.qualifiedopinion.privatevault.ui.lock

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.qualifiedopinion.privatevault.R
import com.qualifiedopinion.privatevault.ui.common.MessagePanel
import com.qualifiedopinion.privatevault.ui.common.PinPad
import com.qualifiedopinion.privatevault.vault.UnavailableReason

@Composable
fun LockScreen(error: Int?, onSubmit: (ByteArray) -> Unit) {
    PinPad(
        title = stringResource(R.string.unlock_title),
        error = error?.let { stringResource(it) },
        onSubmit = onSubmit,
    )
}

/** Planning pack §5.4 error UI: Retry, or re-pick the folder (same vault id only). */
@Composable
fun VaultUnavailableScreen(reason: UnavailableReason, error: Int?, onRetry: () -> Unit, onRepick: () -> Unit) {
    MessagePanel(
        title = stringResource(R.string.unavailable_title),
        body = stringResource(R.string.unavailable_body) + "\n\n" + stringResource(reasonText(reason)),
        error = error?.let { stringResource(it) },
    ) {
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        OutlinedButton(onClick = onRepick) { Text(stringResource(R.string.repick_folder)) }
    }
}

private fun reasonText(reason: UnavailableReason): Int = when (reason) {
    UnavailableReason.NO_PERMISSION -> R.string.reason_no_permission
    UnavailableReason.VOLUME_MISSING -> R.string.reason_volume_missing
    UnavailableReason.VAULT_MISSING -> R.string.reason_vault_missing
    UnavailableReason.WRONG_VAULT -> R.string.reason_wrong_vault
    UnavailableReason.VAULT_DAMAGED -> R.string.reason_vault_damaged
    UnavailableReason.PROVIDER_ERROR -> R.string.reason_provider_error
}
