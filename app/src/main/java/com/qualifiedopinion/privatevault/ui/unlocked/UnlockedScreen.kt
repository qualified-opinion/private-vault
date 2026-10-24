package com.qualifiedopinion.privatevault.ui.unlocked

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.qualifiedopinion.privatevault.R
import com.qualifiedopinion.privatevault.ui.common.MessagePanel

/** PR3 placeholder for the grid (PR5): the item count and a Lock button. */
@Composable
fun UnlockedScreen(itemCount: Int, readOnly: Boolean, onLock: () -> Unit) {
    val count = pluralStringResource(R.plurals.unlocked_items, itemCount, itemCount)
    MessagePanel(
        title = stringResource(R.string.unlocked_title),
        body = if (readOnly) count + "\n\n" + stringResource(R.string.unlocked_read_only) else count,
    ) {
        Button(onClick = onLock) { Text(stringResource(R.string.lock)) }
    }
}
