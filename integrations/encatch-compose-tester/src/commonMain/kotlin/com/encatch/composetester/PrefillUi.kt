package com.encatch.composetester

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Row-based `addToResponse` prefill editor, ported from the web tester's
 * `AddToResponsePrefillRows` (slash-admin-encatch): per-row question id, a grouped question-type
 * picker, a type-aware value editor with a one-tap sample, and strict validation on apply.
 */
@Composable
fun PrefillDialog(
    initialRows: List<PrefillRow>,
    onDismiss: () -> Unit,
    /** Called with the (validated) rows and their parsed values; caller persists + applies. */
    onApply: (rows: List<PrefillRow>, parsed: List<Pair<String, Any?>>) -> Unit,
) {
    var rows by remember {
        mutableStateOf(initialRows.ifEmpty { listOf(PrefillRow()) })
    }
    var error by remember { mutableStateOf<String?>(null) }
    var typePickerForIndex by remember { mutableStateOf<Int?>(null) }

    typePickerForIndex?.let { index ->
        PrefillTypePickerDialog(
            onSelect = { type ->
                rows = rows.mapIndexed { i, row ->
                    // Type change resets the value to that type's sample, like the web tester.
                    if (i == index) row.copy(typeWire = type.wire, value = type.sample) else row
                }
                typePickerForIndex = null
            },
            onDismiss = { typePickerForIndex = null },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            TesterCard {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).imePadding(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Prefill answers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Each row becomes an addToResponse(questionId, value) call before the form is shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    rows.forEachIndexed { index, row ->
                        PrefillRowEditor(
                            row = row,
                            onRowChange = { updated -> rows = rows.mapIndexed { i, r -> if (i == index) updated else r } },
                            onPickType = { typePickerForIndex = index },
                            onFillSample = {
                                rows = rows.mapIndexed { i, r -> if (i == index) r.copy(value = r.type.sample) else r }
                            },
                            onDelete = {
                                rows = if (rows.size <= 1) listOf(PrefillRow()) else rows.filterIndexed { i, _ -> i != index }
                            },
                        )
                        if (index < rows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }

                    SecondaryPillButton(text = "Add row", onClick = { rows = rows + PrefillRow() })

                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    val readyCount = rows.count { it.questionId.isNotBlank() }
                    PrimaryPillButton(
                        text = "Apply $readyCount & Show Form",
                        onClick = {
                            val filled = rows.filter { it.questionId.isNotBlank() }
                            runCatching {
                                filled.map { it.questionId.trim() to parsePrefillValue(it.type, it.value) }
                            }.fold(
                                onSuccess = { parsed -> error = null; onApply(rows, parsed) },
                                onFailure = { error = it.message ?: "Invalid value" },
                            )
                        },
                        enabled = readyCount > 0,
                    )
                    QuietTextButton(text = "Cancel", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun PrefillRowEditor(
    row: PrefillRow,
    onRowChange: (PrefillRow) -> Unit,
    onPickType: () -> Unit,
    onFillSample: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledField(row.questionId, { onRowChange(row.copy(questionId = it)) }, placeholder = "question id (uuid or slug)")

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            SecondaryPillButton(text = "Type: ${row.type.label}", onClick = onPickType, fullWidth = false)
        }

        when (row.type.editor) {
            PrefillEditor.BOOL -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("true", "false").forEach { option ->
                    val selected = row.value.trim() == option
                    TextButton(onClick = { onRowChange(row.copy(value = option)) }) {
                        Text(
                            if (selected) "● $option" else option,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.ink else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            PrefillEditor.JSON, PrefillEditor.LONG_TEXT -> FilledField(
                row.value,
                { onRowChange(row.copy(value = it)) },
                placeholder = row.type.hint,
                singleLine = false,
                minLines = 3,
            )
            else -> FilledField(row.value, { onRowChange(row.copy(value = it)) }, placeholder = row.type.hint)
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(row.type.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = onFillSample) { Text("Sample") }
            TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun PrefillTypePickerDialog(
    onSelect: (PrefillQuestionType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Question type", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PREFILL_CATEGORIES.forEach { category ->
                    Text(
                        category.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                    category.types.forEach { type ->
                        Text(
                            type.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(type) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
