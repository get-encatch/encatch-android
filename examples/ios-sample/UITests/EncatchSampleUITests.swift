import XCTest

/// Drives the sample app through init -> show modal form -> show inline form against a running
/// `:mock-server` (see `mock-server/`), attaching a real screenshot at each step. Requires
/// `:mock-server` reachable at `http://127.0.0.1:8089` (or override via the `MOCK_SERVER_BASE_URL`
/// launch environment variable) before running — otherwise `initButton` hits nothing and every
/// subsequent step silently no-ops.
final class EncatchSampleUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func attachScreenshot(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testProviderSignInModalAndInlineForm() throws {
        let app = XCUIApplication()
        app.launch()
        attachScreenshot(app, name: "00-launch")

        // EncatchProvider initializes the SDK; the status comes from EncatchContext
        let statusText = app.staticTexts["statusText"]
        XCTAssertTrue(statusText.waitForExistence(timeout: 5))
        let initialized = expectation(for: NSPredicate(format: "label CONTAINS 'Initialized: true'"), evaluatedWith: statusText)
        wait(for: [initialized], timeout: 10)
        attachScreenshot(app, name: "01-initialized")

        // EncatchContext publishes the identity once the identify reply is applied, and clears it
        let identityText = app.staticTexts["identityText"]
        XCTAssertEqual(identityText.label, "Identified: no")
        app.buttons["signInButton"].tap()
        let signedIn = expectation(for: NSPredicate(format: "label == 'Identified: sample-user'"), evaluatedWith: identityText)
        wait(for: [signedIn], timeout: 10)
        app.buttons["signOutButton"].tap()
        let signedOut = expectation(for: NSPredicate(format: "label == 'Identified: no'"), evaluatedWith: identityText)
        wait(for: [signedOut], timeout: 10)

        app.buttons["showModalButton"].tap()
        let submitButton = app.buttons["Submit"]
        XCTAssertTrue(submitButton.waitForExistence(timeout: 8))
        attachScreenshot(app, name: "02-modal-form")

        // The mock hosted-form page fires form:submit then form:complete ~200ms later, which
        // the bridge translates into onClose(false) — tapping Submit (real WebView/DOM content,
        // exposed to XCUITest via accessibility) drives the real dismiss flow instead of a
        // synthetic gesture, matching how a real form would close.
        submitButton.tap()
        XCTAssertTrue(app.buttons["showInlineButton"].waitForExistence(timeout: 5))

        // EncatchInlineForm: 0 tall until a form is shown, then sized to the survey
        app.buttons["showInlineButton"].tap()
        XCTAssertTrue(app.buttons["Submit"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["Submit"].isHittable, "the inline form has a real height (a 0-tall slot would hide it)")
        let inline = app.otherElements["inlineForm"]
        if inline.exists {
            XCTAssertGreaterThan(inline.frame.height, 100, "the inline form sized itself to the survey")
        }
        attachScreenshot(app, name: "03-inline-form")
    }
}
