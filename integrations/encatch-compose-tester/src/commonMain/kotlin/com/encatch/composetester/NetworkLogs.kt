package com.encatch.composetester

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.encatch.sdk.Encatch
import com.encatch.sdk.NetworkLogEntry

/**
 * Rolling capture of every SDK HTTP call for the Logs tab, fed by [Encatch.setOnNetworkLog]
 * (which only fires in debugMode). Installed once for the process lifetime. SnapshotStateList
 * mutation is thread-safe, so the callback can append from any thread.
 */
object NetworkLogStore {
    val logs = mutableStateListOf<NetworkLogEntry>()
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        Encatch.setOnNetworkLog { entry ->
            logs.add(0, entry)
            if (logs.size > 200) logs.removeAt(logs.size - 1)
        }
    }
}

private fun NetworkLogEntry.shortName() = endpoint.substringAfterLast('/')

private fun NetworkLogEntry.statusLabel() = if (status == 0) (if (error != null) "ERR" else "—") else status.toString()

/** Full plain-text dump used by the copy buttons. */
private fun NetworkLogEntry.fullText(): String = buildString {
    appendLine("$method ${shortName()} — ${statusLabel()} in ${durationMs}ms")
    appendLine("Timestamp (epoch ms): $timestampMs")
    appendLine("URL: $url")
    appendLine()
    appendLine("--- Request headers ---")
    requestHeaders.entries.sortedBy { it.key }.forEach { (k, v) -> appendLine("$k: $v") }
    appendLine()
    appendLine("--- Request body ---")
    appendLine(requestBody)
    appendLine()
    appendLine("--- Response (${statusLabel()}) ---")
    append(responseBody.ifEmpty { error ?: "(empty)" })
}

@Composable
fun LogsScreen() {
    val clipboard = LocalClipboardManager.current
    var selected by remember { mutableStateOf<NetworkLogEntry?>(null) }
    val logs = NetworkLogStore.logs

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${logs.size} requests · newest first",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(logs.joinToString("\n\n============\n\n") { it.fullText() }))
            }) { Text("Copy all") }
            TextButton(onClick = { logs.clear() }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
        }

        if (logs.isEmpty()) {
            Text(
                "No SDK requests yet",
                modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs, key = { "${it.timestampMs}-${it.endpoint}-${it.durationMs}" }) { entry ->
                    Card(onClick = { selected = entry }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.statusLabel(),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (entry.status in 200..299) Color(0xFF06C167) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.width(44.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(entry.shortName(), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${entry.durationMs}ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(entry.fullText())) }) {
                                Text("Copy")
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.shortName()) },
            text = {
                Text(
                    entry.fullText(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(entry.fullText())) }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
            },
        )
    }
}
