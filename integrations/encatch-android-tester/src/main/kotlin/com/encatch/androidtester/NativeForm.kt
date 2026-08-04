package com.encatch.androidtester

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.encatch.core.BuildSubmitRequestOptions
import com.encatch.core.Encatch
import com.encatch.core.EventPayload
import com.encatch.core.EventType
import com.encatch.core.NativeFormResponse
import com.encatch.core.buildSubmitRequest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A blocked form, queued for the tester to open in the [InterceptorCarousel]. */
data class BlockedFormItem(
    val formId: String,
    val title: String,
    val questionnaireFields: JsonElement?,
)

/**
 * A single question extracted from `ShowFormResponse.questionnaireFields`. The real schema nests
 * questions under sections in a tree we don't have a typed model for on this side of the
 * interceptor boundary (see [ShowFormInterceptorPayload]'s doc comment) — so this walks the whole
 * tree and treats any object carrying both a recognizable `type`/`questionType` and `id`/
 * `questionId` key as a question, which is robust to either a flat array or a nested
 * `{sections: [{fields: [...]}]}` shape without needing to hard-code one.
 */
data class NativeFormQuestion(val id: String, val type: String, val title: String)

private val JsonObject.stringOrNull: (String) -> String?
    get() = { key -> (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null } }

fun parseQuestionnaireFields(questionnaireFields: JsonElement?): List<NativeFormQuestion> {
    val results = mutableListOf<NativeFormQuestion>()
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val get = element.stringOrNull
                val type = get("type") ?: get("questionType")
                val id = get("id") ?: get("questionId")
                if (type != null && id != null) {
                    val title = get("title") ?: get("label") ?: get("question") ?: id
                    results += NativeFormQuestion(id = id, type = type, title = title)
                }
                element.values.forEach { walk(it) }
            }
            is JsonArray -> element.forEach { walk(it) }
            else -> {}
        }
    }
    questionnaireFields?.let(::walk)
    return results
}

/**
 * Answerable question types this demo form knows how to draw; everything else falls back to a
 * plain text field. `welcome`/`thank_you` are deliberately excluded — those are display-only
 * markers already rendered by [WelcomeStep]/[ThankYouStep], not answerable questions.
 */
private val RENDERABLE_TYPES = setOf("rating", "short_answer", "long_text")

@Composable
fun InterceptorCarousel(items: List<BlockedFormItem>, onOpen: (BlockedFormItem) -> Unit, onDismiss: (String) -> Unit) {
    if (items.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.formId }) { item ->
                // Flat gray tile with a faint ink outline — mirrors the iOS carousel card.
                Column(
                    Modifier
                        .width(230.dp)
                        .clip(RoundedCornerShape(TesterTheme.CornerRadius))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.ink.copy(alpha = 0.25f),
                            RoundedCornerShape(TesterTheme.CornerRadius),
                        )
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onDismiss(item.formId) }) {
                            Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "Blocked by interceptor — tap to open custom UI",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onOpen(item) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.background,
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/**
 * A fully custom, non-WebView native form renderer: welcome -> questions (rating/short-answer/
 * long-text) -> thank-you, driving the SDK manually via [Encatch.emitEvent]/[buildSubmitRequest]/
 * [Encatch.submitForm]/[Encatch.dismissForm] instead of the SDK's own modal. Demonstrates the
 * pattern a host app follows after [EncatchConfig.onBeforeShowForm] returns `false`.
 */
@Composable
fun NativeFormModal(item: BlockedFormItem, onClose: () -> Unit) {
    // Empty when the payload has no answerable questions (or none in a type this demo renders) —
    // the flow then goes straight from Welcome to Thank-you, which is correct, not a fallback bug.
    val questions = remember(item.formId) {
        parseQuestionnaireFields(item.questionnaireFields).filter { it.type in RENDERABLE_TYPES }
    }
    var step by remember { mutableIntStateOf(0) } // 0 = welcome, 1..questions.size = questions, last = thank you
    val answers = remember { mutableStateOf(mapOf<String, Any?>()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(item.formId) {
        Encatch.emitEvent(EventType.FORM_SHOW, EventPayload(formId = item.formId, timestamp = 0))
        Encatch.emitEvent(EventType.FORM_STARTED, EventPayload(formId = item.formId, timestamp = 0))
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Custom native form", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = {
                    Encatch.emitEvent(EventType.FORM_CLOSE, EventPayload(formId = item.formId, timestamp = 0))
                    scope.launch { runCatching { Encatch.dismissForm(item.formId) } }
                    onClose()
                }) { Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(16.dp))

            Box(Modifier.fillMaxSize()) {
                when {
                    step == 0 -> WelcomeStep(item.title) { step = 1 }
                    step in 1..questions.size -> {
                        val q = questions[step - 1]
                        QuestionStep(
                            question = q,
                            value = answers.value[q.id],
                            onValueChange = { v -> answers.value = answers.value + (q.id to v) },
                            onNext = {
                                if (step == questions.size) step = questions.size + 1 else step += 1
                            },
                        )
                    }
                    else -> ThankYouStep {
                        val responses = questions.map { q ->
                            NativeFormResponse(questionId = q.id, type = q.type, value = answers.value[q.id])
                        }
                        val request = buildSubmitRequest(
                            BuildSubmitRequestOptions(formConfigurationId = item.formId),
                            responses,
                        )
                        scope.launch {
                            Encatch.emitEvent(EventType.FORM_SUBMIT, EventPayload(formId = item.formId, timestamp = 0))
                            runCatching { Encatch.submitForm(request) }
                            Encatch.emitEvent(EventType.FORM_COMPLETE, EventPayload(formId = item.formId, timestamp = 0))
                            runCatching { Encatch.dismissForm(item.formId) }
                            onClose()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(title: String, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "A custom-rendered form, built entirely from the interceptor payload's questionnaireFields — not the SDK's WebView.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryPillButton("Start", onClick = onNext)
    }
}

@Composable
private fun ThankYouStep(onSubmit: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Thank you!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        PrimaryPillButton("Submit", onClick = onSubmit)
    }
}

@Composable
private fun QuestionStep(question: NativeFormQuestion, value: Any?, onValueChange: (Any?) -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(question.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        when (question.type) {
            "rating" -> {
                Row {
                    for (star in 1..5) {
                        val filled = (value as? Double ?: 0.0) >= star
                        TextButton(onClick = { onValueChange(star.toDouble()) }) {
                            Text(
                                if (filled) "★" else "☆",
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (filled) MaterialTheme.colorScheme.ink
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
            "long_text" -> FilledField(
                value = value as? String ?: "",
                onValueChange = onValueChange,
                singleLine = false,
                minLines = 4,
            )
            else -> FilledField(
                value = value as? String ?: "",
                onValueChange = onValueChange,
            )
        }
        Spacer(Modifier.height(24.dp))
        PrimaryPillButton("Next", onClick = onNext)
    }
}
