package com.encatch.kmptester

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A blocked form, queued for the tester to open in the interceptor carousel. */
data class BlockedFormItem(val formId: String, val title: String, val formConfigJson: String?)

/** A single question extracted from `formConfigJson`'s `questionnaireFields`. */
data class NativeFormQuestion(val id: String, val type: String, val title: String)

/**
 * Walks the whole `questionnaireFields` JSON tree and treats any object carrying both a
 * recognizable `type`/`questionType` and `id`/`questionId` key as a question — robust to the real
 * `{questions: {id: Question}, sections: [...]}` shape without needing to hard-code it (see
 * `encatch-android-tester`'s `NativeForm.kt` for the same approach).
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

/** Renders the floating dismissible-card carousel of blocked forms into [container]. */
fun Activity.renderInterceptorCarousel(
    container: LinearLayout,
    items: List<BlockedFormItem>,
    onOpen: (BlockedFormItem) -> Unit,
    onDismiss: (String) -> Unit,
) {
    container.removeAllViews()
    if (items.isEmpty()) return
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20f), dp(4f), dp(20f), dp(8f))
    }
    items.forEach { item ->
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(12f))
            background = roundedBackground(surfaceVariant())
            layoutParams = LinearLayout.LayoutParams(dp(260f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12f) }
        }
        card.addView(TextView(this).apply {
            text = item.title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink())
        })
        card.addView(TextView(this).apply {
            text = "Blocked by interceptor — tap to open custom UI"
            textSize = 12f
            setTextColor(secondaryText())
            setPadding(0, dp(2f), 0, dp(8f))
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(chipButton("Open", selected = true) { onOpen(item) })
        buttons.addView(chipButton("Dismiss") { onDismiss(item.formId) }.apply {
            background = pillBackground(surface())
        })
        card.addView(buttons)
        row.addView(card)
    }
    container.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    })
}

/**
 * Builds a fully custom, non-WebView native form: welcome -> questions (rating/short-answer/
 * long-text) -> thank-you, driving the SDK manually via [TesterController.emitEvent]/
 * [TesterController.submitNativeForm]/[TesterController.dismissForm] instead of the SDK's own
 * modal. Demonstrates the pattern a host app follows after `onBeforeShowForm` returns `false`.
 */
fun Activity.buildNativeFormModal(item: BlockedFormItem, scope: CoroutineScope, onClose: () -> Unit): View {
    val questions = parseQuestionnaireFields(item.formConfigJson).filter { it.type in RENDERABLE_TYPES }
    val answers = mutableMapOf<String, String>()
    var step = 0 // 0 = welcome, 1..questions.size = questions, last = thank you

    TesterController.emitEvent("form:show", item.formId)
    TesterController.emitEvent("form:started", item.formId)

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20f), dp(48f), dp(20f), dp(24f))
        setBackgroundColor(surface())
    }

    val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    header.addView(TextView(this).apply {
        text = "Custom native form"
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ink())
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    val closeButton = chipButton("Close") {
        TesterController.emitEvent("form:close", item.formId)
        scope.launch { runCatching { TesterController.dismissForm(item.formId) } }
        onClose()
    }
    header.addView(closeButton)
    root.addView(header)

    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(24f), 0, 0) }
    root.addView(content)

    fun renderStep() {
        content.removeAllViews()
        when {
            step == 0 -> {
                content.addView(TextView(this).apply {
                    text = item.title
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ink())
                    setPadding(0, 0, 0, dp(8f))
                })
                content.addView(TextView(this).apply {
                    text = "A custom-rendered form, built entirely from the interceptor payload's questionnaireFields — not the SDK's WebView."
                    textSize = 14f
                    setTextColor(secondaryText())
                    setPadding(0, 0, 0, dp(12f))
                })
                content.addView(primaryButton("Start") { step = 1; renderStep() })
            }
            step <= questions.size -> {
                val q = questions[step - 1]
                content.addView(TextView(this).apply {
                    text = q.title
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ink())
                    setPadding(0, 0, 0, dp(12f))
                })
                when (q.type) {
                    "rating" -> {
                        val ratingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        (1..5).forEach { star ->
                            val filled = (answers[q.id]?.toDoubleOrNull() ?: 0.0) >= star
                            val chip = chipButton(if (filled) "★" else "☆") {
                                answers[q.id] = star.toString(); renderStep()
                            }
                            if (filled) {
                                chip.background = pillBackground(TesterTheme.GREEN)
                                chip.setTextColor(0xFFFFFFFF.toInt())
                            }
                            chip.textSize = 18f
                            ratingRow.addView(chip)
                        }
                        content.addView(ratingRow)
                    }
                    else -> {
                        // Kept at child index 1 — the Next handler below reads it back by position.
                        val field = filledField(null).apply { setText(answers[q.id] ?: "") }
                        content.addView(field)
                        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) answers[q.id] = field.text.toString() }
                    }
                }
                content.addView(primaryButton("Next") {
                    if (content.getChildAt(1) is EditText) answers[q.id] = (content.getChildAt(1) as EditText).text.toString()
                    step += 1
                    renderStep()
                })
            }
            else -> {
                content.addView(TextView(this).apply {
                    text = "Thank you!"
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ink())
                    setPadding(0, 0, 0, dp(12f))
                })
                content.addView(primaryButton("Submit") {
                    scope.launch {
                        TesterController.emitEvent("form:submit", item.formId)
                        runCatching {
                            TesterController.submitNativeForm(
                                item.formId,
                                questions.map { it.id },
                                questions.map { it.type },
                                questions.map { answers[it.id] },
                            )
                        }
                        TesterController.emitEvent("form:complete", item.formId)
                        runCatching { TesterController.dismissForm(item.formId) }
                        onClose()
                    }
                })
            }
        }
    }
    renderStep()

    return ScrollView(this).apply { setBackgroundColor(surface()); addView(root) }
}
