import XCTest
@testable import Encatch

/// Custom form-validation i18n: `projectI18nFileUrl` arrives at the TOP LEVEL of the show-form
/// response (never inside formConfiguration/questionnaireFields/appearanceProperties/
/// otherConfigurationProperties) and must be preserved and forwarded at the top level of the
/// form-config message sent to the WebView — or omitted entirely when null/missing, in which
/// case the WebView form engine falls back to its default language packs. Native code does no
/// translation loading itself.
final class ShowFormI18nTests: XCTestCase {

    private func parse(_ json: String) -> ShowFormResponse {
        guard case .object(let object)? = JSONValue.parse(json) else {
            XCTFail("test JSON did not parse as an object")
            return ShowFormResponse(feedbackConfigurationId: "")
        }
        return object.toShowFormResponse()
    }

    // ------------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------------

    func testParse_readsProjectI18nFileUrl_fromTopLevel() {
        let response = parse(#"{"feedbackConfigurationId":"cfg-1","projectI18nFileUrl":"https://cdn.example.com/form-i18n/v12.json"}"#)
        XCTAssertEqual(response.projectI18nFileUrl, "https://cdn.example.com/form-i18n/v12.json")
    }

    func testParse_missingProjectI18nFileUrl_isNil() {
        let response = parse(#"{"feedbackConfigurationId":"cfg-1"}"#)
        XCTAssertNil(response.projectI18nFileUrl)
    }

    func testParse_explicitNullProjectI18nFileUrl_isNil() {
        let response = parse(#"{"feedbackConfigurationId":"cfg-1","projectI18nFileUrl":null}"#)
        XCTAssertNil(response.projectI18nFileUrl)
    }

    func testParse_doesNotReadProjectI18nFileUrl_fromNestedConfigObjects() {
        let response = parse(#"""
        {
          "feedbackConfigurationId": "cfg-1",
          "formConfiguration": {"projectI18nFileUrl": "https://cdn.example.com/wrong-1.json"},
          "questionnaireFields": {"projectI18nFileUrl": "https://cdn.example.com/wrong-2.json"},
          "appearanceProperties": {"projectI18nFileUrl": "https://cdn.example.com/wrong-3.json"},
          "otherConfigurationProperties": {"projectI18nFileUrl": "https://cdn.example.com/wrong-4.json"}
        }
        """#)
        XCTAssertNil(response.projectI18nFileUrl)
    }

    // ------------------------------------------------------------------------
    // Forwarding to the WebView (FORM_CONFIG message)
    // ------------------------------------------------------------------------

    private struct NoopRedirectOpener: RedirectOpener {
        func openInternal(url: String) async {}
    }

    private final class SentMessages: @unchecked Sendable {
        var messages: [SDKMessage] = []
    }

    private func formConfigMessageData(_ formConfig: ShowFormResponse) -> [String: JSONValue]? {
        let sent = SentMessages()
        let bridge = FormWebViewBridge(
            onClose: { _ in },
            onHeightChange: { _ in },
            onForceFullHeight: { _ in },
            onReady: {},
            sendToWebView: { message in sent.messages.append(message) },
            redirectOpener: NoopRedirectOpener(),
            openExternal: { _ in }
        )
        bridge.setFormPayload(ShowFormPayload(
            formId: "f1",
            formConfig: formConfig,
            resetMode: .always,
            triggerType: .manual,
            presentation: "modal"
        ))
        bridge.handleFormReady()

        guard let message = sent.messages.first(where: { $0.type == .formConfig }),
              let dataJson = message.dataJson,
              case .object(let object)? = JSONValue.parse(dataJson)
        else { return nil }
        return object
    }

    func testFormConfigMessage_forwardsProjectI18nFileUrl_atTopLevel() {
        let data = formConfigMessageData(ShowFormResponse(
            feedbackConfigurationId: "cfg-1",
            projectI18nFileUrl: "https://cdn.example.com/form-i18n/v12.json"
        ))
        guard case .string(let url)? = data?["projectI18nFileUrl"] else {
            return XCTFail("projectI18nFileUrl missing from form-config message")
        }
        XCTAssertEqual(url, "https://cdn.example.com/form-i18n/v12.json")
    }

    func testFormConfigMessage_omitsProjectI18nFileUrl_whenNil() {
        let data = formConfigMessageData(ShowFormResponse(feedbackConfigurationId: "cfg-1"))
        XCTAssertNotNil(data)
        XCTAssertNil(data?["projectI18nFileUrl"])
        XCTAssertNotNil(data?["feedbackConfigurationId"])
    }
}
