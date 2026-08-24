package com.encatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the typed config classes added for `@encatch/schema` 1.5.2 parity —
 * [FormConfigurationResponse], [LogicJumpRule], the completion CTA config tree, and
 * [PaymentsUpiAnswer.Companion.fromNumericAmount]. Mirrors the equivalent tests in the
 * Flutter SDK's `encatch_test.dart` (1.1.2).
 */
class FormConfigTypesTest {

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun formConfigurationResponseParsesAllFields() {
        val parsed = FormConfigurationResponse.fromJson(
            parse("""{"formTitle":"NPS survey","formDescription":"Quarterly","respondentsCount":42}"""),
        )
        assertEquals("NPS survey", parsed?.formTitle)
        assertEquals("Quarterly", parsed?.formDescription)
        assertEquals(42, parsed?.respondentsCount)
    }

    @Test
    fun formConfigurationResponseDefaultsMissingFields() {
        val parsed = FormConfigurationResponse.fromJson(parse("""{}"""))
        assertEquals("", parsed?.formTitle)
        assertEquals("", parsed?.formDescription)
        assertNull(parsed?.respondentsCount)
        assertNull(FormConfigurationResponse.fromJson(null))
    }

    @Test
    fun showFormResponseTypedFormConfiguration() {
        val config = parse("""{"formTitle":"Title","formDescription":"Desc","respondentsCount":7}""")
        val response = ShowFormResponse(
            feedbackConfigurationId = "fc-1",
            formConfiguration = config,
        )
        assertEquals(
            FormConfigurationResponse("Title", "Desc", 7),
            response.typedFormConfiguration,
        )
        assertNull(ShowFormResponse(feedbackConfigurationId = "fc-1").typedFormConfiguration)
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
    fun completionCtaParsesPerSurfaceActions() {
        val cta = CompletionCta.fromJson(
            parse(
                """
                {
                  "label": "Continue",
                  "autoTriggerDelayMs": 3000,
                  "inApp": {"action": "app_navigate", "route": "/home"},
                  "link": {"action": "redirect_external", "url": "https://encatch.com"},
                  "secondary": {
                    "label": "Close form",
                    "inApp": {"action": "dismiss"}
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals("Continue", cta?.label)
        assertEquals(3000L, cta?.autoTriggerDelayMs)
        assertEquals(CompletionCtaAction.APP_NAVIGATE, cta?.inApp?.action)
        assertEquals("/home", cta?.inApp?.route)
        assertEquals(CompletionCtaAction.REDIRECT_EXTERNAL, cta?.link?.action)
        assertEquals("https://encatch.com", cta?.link?.url)
        assertEquals("Close form", cta?.secondary?.label)
        assertEquals(CompletionCtaAction.DISMISS, cta?.secondary?.inApp?.action)
        assertNull(cta?.secondary?.link)
    }

    @Test
    fun platformCompletionCtaUnknownActionFallsBackToDismiss() {
        val cta = PlatformCompletionCta.fromJson(parse("""{"action":"launch_rocket"}"""))
        assertEquals(CompletionCtaAction.DISMISS, cta?.action)
    }

    @Test
    fun completionCtaActionWireRoundTrip() {
        for (action in CompletionCtaAction.entries) {
            assertEquals(action, CompletionCtaAction.fromWire(action.wireValue))
        }
        assertNull(CompletionCtaAction.fromWire("unknown"))
    }

    @Test
    fun paymentsUpiFromNumericAmountFormatsDecimalString() {
        val whole = PaymentsUpiAnswer.fromNumericAmount(
            transactionId = "t-1",
            encatchPaymentReference = "ref-1",
            amount = 150.0,
            payeeVpa = "encatch@upi",
        )
        assertEquals("150", whole.amount)
        assertEquals("INR", whole.currency)

        val fractional = PaymentsUpiAnswer.fromNumericAmount(
            transactionId = "t-2",
            encatchPaymentReference = "ref-2",
            amount = 150.5,
            payeeVpa = "encatch@upi",
        )
        assertEquals("150.5", fractional.amount)
    }
}
