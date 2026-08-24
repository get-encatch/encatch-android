import XCTest
@testable import Encatch

/// Covers the typed config classes added for `@encatch/schema` 1.5.2 parity —
/// `FormConfigurationResponse`, `LogicJumpRule`, the completion CTA config tree, and
/// `PaymentsUpiAnswer.fromNumericAmount`. Mirrors the Kotlin `FormConfigTypesTest`.
final class FormConfigTypesTests: XCTestCase {

    private func parse(_ json: String) -> [String: JSONValue] {
        JSONValue.parse(json)?.asObject ?? [:]
    }

    func testFormConfigurationResponseParsesAllFields() {
        let parsed = FormConfigurationResponse.fromJson(
            parse(#"{"formTitle":"NPS survey","formDescription":"Quarterly","respondentsCount":42}"#)
        )
        XCTAssertEqual(parsed?.formTitle, "NPS survey")
        XCTAssertEqual(parsed?.formDescription, "Quarterly")
        XCTAssertEqual(parsed?.respondentsCount, 42)
    }

    func testFormConfigurationResponseDefaultsMissingFields() {
        let parsed = FormConfigurationResponse.fromJson(parse("{}"))
        XCTAssertEqual(parsed?.formTitle, "")
        XCTAssertEqual(parsed?.formDescription, "")
        XCTAssertNil(parsed?.respondentsCount)
        XCTAssertNil(FormConfigurationResponse.fromJson(nil))
    }

    func testShowFormResponseTypedFormConfiguration() {
        let response = ShowFormResponse(
            feedbackConfigurationId: "fc-1",
            formConfiguration: parse(#"{"formTitle":"Title","formDescription":"Desc","respondentsCount":7}"#)
        )
        XCTAssertEqual(
            response.typedFormConfiguration,
            FormConfigurationResponse(formTitle: "Title", formDescription: "Desc", respondentsCount: 7)
        )
        XCTAssertNil(ShowFormResponse(feedbackConfigurationId: "fc-1").typedFormConfiguration)
    }

    func testLogicJumpRuleParsesHighLevel() {
        let rule = LogicJumpRule.fromJson(
            parse(#"{"jsonLogic":{"==":[{"var":"q1"},"yes"]},"targetQuestionId":"q5"}"#)
        )
        XCTAssertEqual(rule?.targetQuestionId, "q5")
        XCTAssertEqual(rule?.jsonLogic, parse(#"{"==":[{"var":"q1"},"yes"]}"#))
    }

    func testCompletionCtaParsesPerSurfaceActions() {
        let cta = CompletionCta.fromJson(parse(#"""
        {
          "label": "Continue",
          "autoTriggerDelayMs": 3000,
          "inApp": {"action": "app_navigate", "route": "/home"},
          "link": {"action": "redirect_external", "url": "https://encatch.com"},
          "secondary": {"label": "Close form", "inApp": {"action": "dismiss"}}
        }
        """#))
        XCTAssertEqual(cta?.label, "Continue")
        XCTAssertEqual(cta?.autoTriggerDelayMs, 3000)
        XCTAssertEqual(cta?.inApp?.action, .appNavigate)
        XCTAssertEqual(cta?.inApp?.route, "/home")
        XCTAssertEqual(cta?.link?.action, .redirectExternal)
        XCTAssertEqual(cta?.link?.url, "https://encatch.com")
        XCTAssertEqual(cta?.secondary?.label, "Close form")
        XCTAssertEqual(cta?.secondary?.inApp?.action, .dismiss)
        XCTAssertNil(cta?.secondary?.link)
    }

    func testUnknownCtaActionFallsBackToDismiss() {
        let cta = PlatformCompletionCta.fromJson(parse(#"{"action":"launch_rocket"}"#))
        XCTAssertEqual(cta?.action, .dismiss)
    }

    func testCompletionCtaActionWireRoundTrip() {
        for action in CompletionCtaAction.allCases {
            XCTAssertEqual(CompletionCtaAction.fromWire(action.wireValue), action)
        }
        XCTAssertNil(CompletionCtaAction.fromWire("unknown"))
    }

    func testPaymentsUpiFromNumericAmountFormatsDecimalString() {
        let whole = PaymentsUpiAnswer.fromNumericAmount(
            transactionId: "t-1",
            encatchPaymentReference: "ref-1",
            amount: 150.0,
            payeeVpa: "encatch@upi"
        )
        XCTAssertEqual(whole.amount, "150")
        XCTAssertEqual(whole.currency, "INR")
        XCTAssertTrue(whole.selfReported)

        let fractional = PaymentsUpiAnswer.fromNumericAmount(
            transactionId: "t-2",
            encatchPaymentReference: "ref-2",
            amount: 150.5,
            payeeVpa: "encatch@upi"
        )
        XCTAssertEqual(fractional.amount, "150.5")
    }
}
