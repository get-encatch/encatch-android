package com.encatch.composetester

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.background)) {
        HorizontalDivider(thickness = 1.dp, color = cs.onBackground.copy(alpha = 0.08f))
        LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.formId }) { item ->
                Column(
                    Modifier
                        .width(230.dp)
                        .clip(RoundedCornerShape(CardCornerRadius))
                        .background(cs.surfaceVariant)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            item.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onBackground,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "✕",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onDismiss(item.formId) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Blocked by interceptor — tap to open custom UI",
                        fontSize = 12.sp,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(cs.ink)
                            .clickable { onOpen(item) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Open", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.background)
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
    val cs = MaterialTheme.colorScheme

    androidx.compose.runtime.LaunchedEffect(item.formId) {
        Encatch.emitEvent(EventType.FORM_SHOW, EventPayload(formId = item.formId, timestamp = 0))
        Encatch.emitEvent(EventType.FORM_STARTED, EventPayload(formId = item.formId, timestamp = 0))
    }

    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Custom native form",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Close",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            Encatch.emitEvent(EventType.FORM_CLOSE, EventPayload(formId = item.formId, timestamp = 0))
                            scope.launch { runCatching { Encatch.dismissForm(item.formId) } }
                            onClose()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            Box(Modifier.fillMaxSize()) {
                when {
                    step == 0 -> WelcomeStep(item.title) { step = 1 }
                    step in 1..questions.size -> {
                        val q = questions[step - 1]
                        QuestionStep(
                            question = q,
                            stepIndex = step,
                            stepCount = questions.size,
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
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 56.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "A custom-rendered form, built entirely from the interceptor payload's questionnaireFields — not the SDK's WebView.",
            fontSize = 14.sp,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryPillButton(text = "Start", onClick = onNext)
    }
}

@Composable
private fun ThankYouStep(onSubmit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✓", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = UberGreen)
        Spacer(Modifier.height(8.dp))
        Text("Thank you!", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
        Spacer(Modifier.height(6.dp))
        Text("Your answers are ready to submit.", fontSize = 14.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        PrimaryPillButton(text = "Submit", onClick = onSubmit)
    }
}

@Composable
private fun QuestionStep(
    question: NativeFormQuestion,
    stepIndex: Int,
    stepCount: Int,
    value: Any?,
    onValueChange: (Any?) -> Unit,
    onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Text(
            "Question $stepIndex of $stepCount",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(question.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
        Spacer(Modifier.height(16.dp))
        when (question.type) {
            "rating" -> {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                ) {
                    for (star in 1..5) {
                        val filled = (value as? Double ?: 0.0) >= star
                        Text(
                            if (filled) "★" else "☆",
                            fontSize = 28.sp,
                            color = if (filled) UberGreen else cs.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onValueChange(star.toDouble()) },
                                )
                                .padding(horizontal = 8.dp),
                        )
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
                placeholder = "Answer",
            )
        }
        Spacer(Modifier.weight(1f))
        PrimaryPillButton(text = "Next", onClick = onNext)
    }
}
