package com.encatch.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers `:kmp-sdk`'s lockstep copy of the schema-1.5.2 typed config classes
 * (`FormConfigTypes.kt`) and [PaymentsUpiAnswer.Companion.fromNumericAmount].
 */
class FormConfigTypesTest {

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun completionCtaParsesPerSurfaceActions() {
        val cta = CompletionCta.fromJson(
            parse(
                """
                {
                  "label": "Continue",
                  "autoTriggerDelayMs": 3000,
                  "inApp": {"action": "app_navigate", "route": "/home"},
                  "link": {"action": "redirect_external", "url": "https://encatch.com"},
                  "secondary": {"label": "Close form", "inApp": {"action": "dismiss"}}
                }
                """.trimIndent(),
            ),
        )
        assertEquals(CompletionCtaAction.APP_NAVIGATE, cta?.inApp?.action)
        assertEquals("/home", cta?.inApp?.route)
        assertEquals(CompletionCtaAction.REDIRECT_EXTERNAL, cta?.link?.action)
        assertEquals("Close form", cta?.secondary?.label)
        assertEquals(3000L, cta?.autoTriggerDelayMs)
    }

    @Test
    fun unknownCtaActionFallsBackToDismiss() {
        assertEquals(
            CompletionCtaAction.DISMISS,
            PlatformCompletionCta.fromJson(parse("""{"action":"launch_rocket"}"""))?.action,
        )
    }

    @Test
    fun logicJumpRuleParsesHighLevel() {
        val rule = LogicJumpRule.fromJson(
            parse("""{"jsonLogic":{"==":[{"var":"q1"},"yes"]},"targetQuestionId":"q5"}"""),
        )
        assertEquals("q5", rule?.targetQuestionId)
        assertEquals(parse("""{"==":[{"var":"q1"},"yes"]}"""), rule?.jsonLogic)
    }

    @Test
    fun typedFormConfigurationParsesFromFormConfigJson() {
        val payload = ShowFormInterceptorPayload(
            formId = "fc-1",
            resetMode = ResetMode.ALWAYS,
            triggerType = TriggerType.MANUAL,
            formConfigJson = """
                {"feedbackConfigurationId":"fc-1",
                 "formConfiguration":{"formTitle":"Title","formDescription":"Desc","respondentsCount":7}}
            """.trimIndent(),
        )
        assertEquals(
            FormConfigurationResponse("Title", "Desc", 7),
            payload.typedFormConfiguration(),
        )
        assertNull(payload.copy(formConfigJson = null).typedFormConfiguration())
        assertNull(payload.copy(formConfigJson = "not json").typedFormConfiguration())
    }

    @Test
    fun paymentsUpiFromNumericAmountFormatsDecimalString() {
        assertEquals(
            "150",
            PaymentsUpiAnswer.fromNumericAmount(
                transactionId = "t-1",
                encatchPaymentReference = "ref-1",
                amount = 150.0,
                payeeVpa = "encatch@upi",
            ).amount,
        )
        assertEquals(
            "150.5",
            PaymentsUpiAnswer.fromNumericAmount(
                transactionId = "t-2",
                encatchPaymentReference = "ref-2",
                amount = 150.5,
                payeeVpa = "encatch@upi",
            ).amount,
        )
    }
}
