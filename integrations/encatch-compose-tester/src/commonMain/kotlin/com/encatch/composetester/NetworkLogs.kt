package com.encatch.composetester

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    var selected by remember { mutableStateOf<NetworkLogEntry?>(null) }
    val logs = NetworkLogStore.logs
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                "${logs.size} requests · newest first",
                fontSize = 12.sp,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Copy all",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(logs.joinToString("\n\n============\n\n") { it.fullText() }))
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                "Clear",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { logs.clear() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        if (logs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No SDK requests yet", fontSize = 14.sp, color = cs.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(logs, key = { "${it.timestampMs}-${it.endpoint}-${it.durationMs}" }) { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.surfaceVariant)
                            .clickable { selected = entry }
                            .padding(12.dp),
                    ) {
                        Text(
                            entry.statusLabel(),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (entry.status in 200..299) UberGreen else cs.error,
                            modifier = Modifier.width(44.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(entry.shortName(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                            Text("${entry.durationMs}ms", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                        Text(
                            "Copy",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { clipboard.setText(AnnotatedString(entry.fullText())) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = cs.background,
            titleContentColor = cs.onBackground,
            textContentColor = cs.onBackground,
            title = { Text(entry.shortName(), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    entry.fullText(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(entry.fullText())) }) {
                    Text("Copy", fontWeight = FontWeight.SemiBold, color = cs.ink)
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) {
                    Text("Close", fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                }
            },
        )
    }
}
