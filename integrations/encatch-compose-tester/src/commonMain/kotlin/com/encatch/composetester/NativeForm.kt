package com.encatch.composetester

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.encatch.sdk.BuildSubmitRequestOptions
import com.encatch.sdk.Encatch
import com.encatch.sdk.EventPayload
import com.encatch.sdk.EventType
import com.encatch.sdk.NativeFormResponse
import com.encatch.sdk.buildSubmitRequest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A blocked form, queued for the tester to open in the [InterceptorCarousel]. */
data class BlockedFormItem(val formId: String, val title: String, val formConfigJson: String?)

data class NativeFormQuestion(val id: String, val type: String, val title: String)

/**
 * Walks the whole `questionnaireFields` JSON tree (carried as a raw JSON string via
 * `ShowFormInterceptorPayload.formConfigJson`, since `:kmp-sdk`'s commonMain can't depend on a
 * typed `ShowFormResponse`) and treats any object carrying both a recognizable `type` and `id` key
 * as a question — robust to the real `{questions: {id: Question}, sections: [...]}` shape without
 * needing to hard-code it (see `encatch-android-tester`'s `NativeForm.kt` for the same approach).
 */
fun parseQuestionnaireFields(formConfigJson: String?): List<NativeFormQuestion> {
    if (formConfigJson.isNullOrBlank()) return emptyList()
    val root = runCatching { Json.parseToJsonElement(formConfigJson) }.getOrNull() ?: return emptyList()
    val results = mutableListOf<NativeFormQuestion>()
    fun str(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val type = str(element, "type") ?: str(element, "questionType")
                val id = str(element, "id") ?: str(element, "questionId")
                if (type != null && id != null) {
                    val title = str(element, "title") ?: str(element, "label") ?: str(element, "question") ?: id
                    results += NativeFormQuestion(id, type, title)
                }
                element.values.forEach { walk(it) }
            }
            is JsonArray -> element.forEach { walk(it) }
            else -> {}
        }
    }
    walk(root)
    return results
}

private val RENDERABLE_TYPES = setOf("rating", "short_answer", "long_text")

@Composable
fun InterceptorCarousel(items: List<BlockedFormItem>, onOpen: (BlockedFormItem) -> Unit, onDismiss: (String) -> Unit) {
    if (items.isEmpty()) return
    Surface(shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.formId }) { item ->
                Card(modifier = Modifier.width(220.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { onDismiss(item.formId) }) { Text("✕") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Blocked by interceptor — tap to open custom UI", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onOpen(item) }) { Text("Open") }
                    }
                }
            }
        }
    }
}

/**
 * A fully custom, non-WebView native form renderer: welcome -> questions (rating/short-answer/
 * long-text) -> thank-you, driving the SDK manually via [Encatch.emitEvent]/[buildSubmitRequest]/
 * [Encatch.submitForm]/[Encatch.dismissForm] instead of the SDK's own modal.
 */
@Composable
fun NativeFormModal(item: BlockedFormItem, onClose: () -> Unit) {
    val questions = remember(item.formId) {
        parseQuestionnaireFields(item.formConfigJson).filter { it.type in RENDERABLE_TYPES }
    }
    var step by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateOf(mapOf<String, Any?>()) }
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(item.formId) {
        Encatch.emitEvent(EventType.FORM_SHOW, EventPayload(formId = item.formId, timestamp = 0))
        Encatch.emitEvent(EventType.FORM_STARTED, EventPayload(formId = item.formId, timestamp = 0))
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Custom native form", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    Encatch.emitEvent(EventType.FORM_CLOSE, EventPayload(formId = item.formId, timestamp = 0))
                    scope.launch { runCatching { Encatch.dismissForm(item.formId) } }
                    onClose()
                }) { Text("Close") }
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
                            onNext = { step += 1 },
                        )
                    }
                    else -> ThankYouStep {
                        val responses = questions.map { q -> NativeFormResponse(questionId = q.id, type = q.type, value = answers.value[q.id]) }
                        val requestJson = buildSubmitRequest(BuildSubmitRequestOptions(formConfigurationId = item.formId), responses)
                        scope.launch {
                            Encatch.emitEvent(EventType.FORM_SUBMIT, EventPayload(formId = item.formId, timestamp = 0))
                            runCatching { Encatch.submitForm(requestJson) }
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
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("A custom-rendered form, built entirely from the interceptor payload's questionnaireFields — not the SDK's WebView.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNext) { Text("Start") }
    }
}

@Composable
private fun ThankYouStep(onSubmit: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Thank you!", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSubmit) { Text("Submit") }
    }
}

@Composable
private fun QuestionStep(question: NativeFormQuestion, value: Any?, onValueChange: (Any?) -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(question.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        when (question.type) {
            "rating" -> {
                Row {
                    for (star in 1..5) {
                        val filled = (value as? Double ?: 0.0) >= star
                        TextButton(onClick = { onValueChange(star.toDouble()) }) {
                            Text(if (filled) "★" else "☆", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
            "long_text" -> OutlinedTextField(
                value = value as? String ?: "",
                onValueChange = onValueChange,
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> OutlinedTextField(
                value = value as? String ?: "",
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNext) { Text("Next") }
    }
}
