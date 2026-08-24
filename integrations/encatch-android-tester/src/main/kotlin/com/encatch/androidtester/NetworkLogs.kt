package com.encatch.androidtester

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.encatch.core.Encatch
import com.encatch.core.EncatchNetworkLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling capture of every SDK HTTP call for the Logs tab, fed by [Encatch.onNetworkLog]
 * (which only fires in debugMode). Installed once for the process lifetime.
 */
object NetworkLogStore {
    val logs = mutableStateListOf<EncatchNetworkLogEntry>()
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val main = Handler(Looper.getMainLooper())
        Encatch.onNetworkLog = { entry ->
            main.post {
                logs.add(0, entry)
                if (logs.size > 200) logs.removeAt(logs.size - 1)
            }
        }
    }
}

private fun EncatchNetworkLogEntry.shortName() = endpoint.substringAfterLast('/')

private fun EncatchNetworkLogEntry.statusLabel() = if (status == 0) (if (error != null) "ERR" else "—") else status.toString()

private fun EncatchNetworkLogEntry.timeLabel(): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))

/** Full plain-text dump used by the copy buttons. */
private fun EncatchNetworkLogEntry.fullText(): String = buildString {
    appendLine("$method ${shortName()} — ${statusLabel()} in ${durationMs}ms")
    appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(timestampMs))}")
    appendLine("URL: $url")
    appendLine()
    appendLine("--- Request headers ---")
    requestHeaders.entries.sortedBy { it.key }.forEach { (k, v) -> appendLine("$k: $v") }
    appendLine()
    appendLine("--- Request body ---")
    appendLine(requestBody)
    appendLine()
    if (responseHeaders.isNotEmpty()) {
        appendLine("--- Response headers ---")
        responseHeaders.entries.sortedBy { it.key }.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
    }
    appendLine("--- Response (${statusLabel()}) ---")
    append(responseBody.ifEmpty { error ?: "(empty)" })
}

@Composable
fun LogsScreen() {
    val clipboard = LocalClipboardManager.current
    var selected by remember { mutableStateOf<EncatchNetworkLogEntry?>(null) }
    val logs = NetworkLogStore.logs

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${logs.size} requests · newest first",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(logs.joinToString("\n\n============\n\n") { it.fullText() }))
            }) {
                Text("Copy all", color = MaterialTheme.colorScheme.ink, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { logs.clear() }) {
                Text("Clear", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        }

        if (logs.isEmpty()) {
            Text(
                "No SDK requests yet",
                modifier = Modifier.padding(top = 48.dp).fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs, key = { it.timestampMs.toString() + it.endpoint + it.durationMs }) { entry ->
                    // Flat gray row card, Uber-style: no border, no elevation.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selected = entry }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.statusLabel(),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (entry.status in 200..299) TesterTheme.Green else MaterialTheme.colorScheme.error,
                            modifier = Modifier.width(44.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.shortName(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${entry.timeLabel()} · ${entry.durationMs}ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(entry.fullText())) }) {
                            Text("Copy", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
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
                    modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
                )
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(entry.fullText())) }) {
                    Text("Copy", color = MaterialTheme.colorScheme.ink, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
}
