package com.qualifiedopinion.privatevault.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qualifiedopinion.privatevault.R

/** In-app PIN pad (6–12 digits). [onSubmit] receives a fresh array it must wipe. */
@Composable
fun PinPad(
    title: String,
    error: String?,
    onSubmit: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val buffer = remember { PinBuffer() }
    DisposableEffect(buffer) { onDispose { buffer.wipe() } }
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        PinDots(buffer.length)
        Text(
            text = error ?: stringResource(R.string.pin_rules),
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        for (row in KEY_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (key in row) {
                    PinKey(key, buffer, enabled, onSubmit)
                }
            }
        }
    }
}

@Composable
private fun PinDots(count: Int) {
    val description = pluralStringResource(R.plurals.pin_digits_entered, count, count)
    Row(
        modifier = Modifier.height(16.dp).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(count) {
            Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

@Composable
private fun PinKey(key: Char, buffer: PinBuffer, enabled: Boolean, onSubmit: (ByteArray) -> Unit) {
    val keyModifier = Modifier.widthIn(min = 72.dp)
    when (key) {
        BACKSPACE -> TextButton(onClick = buffer::pop, enabled = enabled && buffer.length > 0, modifier = keyModifier) {
            Text(stringResource(R.string.pin_delete))
        }
        SUBMIT -> TextButton(
            onClick = { onSubmit(buffer.take()) },
            enabled = enabled && buffer.canSubmit,
            modifier = keyModifier,
        ) {
            Text(stringResource(R.string.pin_ok))
        }
        else -> FilledTonalButton(onClick = { buffer.push(key - '0') }, enabled = enabled, modifier = keyModifier) {
            Text(key.toString(), style = MaterialTheme.typography.titleLarge)
        }
    }
}

private const val BACKSPACE = '<'
private const val SUBMIT = '>'
private val KEY_ROWS = listOf("123", "456", "789", "<0>").map { it.toList() }
