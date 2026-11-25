import XCTest
@testable import Encatch

/// Locale and country share one saved entry: no save may overwrite another's value, whatever the
/// order or the thread (setLocale, setCountry and identifyUser's locale/country).
final class PreferencesSaveTests: XCTestCase {
    func testSetLocaleThenIdentifyWithACountryKeepsBoth() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults)
        defer { sdk.stop() }
        sdk.setLocale("fr")
        try await sdk.identifyUser(userName: "alice", options: IdentifyOptions(country: "FR"))
        await waitFor { server.count("identify-user") == 1 }
        let saved = EncatchStorage(defaults: defaults).getPreferences()
        XCTAssertEqual(saved.locale, "fr")
        XCTAssertEqual(saved.country, "FR")
    }

    func testSetLocaleAndCountryAreSavedByTheTimeTheyReturn() async throws {
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: FakeServer(), defaults: defaults)
        defer { sdk.stop() }
        let storage = EncatchStorage(defaults: defaults)
        for i in 0..<50 {
            sdk.setLocale("l\(i)")
            XCTAssertEqual(storage.getPreferences().locale, "l\(i)")
            sdk.setCountry("c\(i)")
            XCTAssertEqual(storage.getPreferences().country, "c\(i)")
        }
    }

    func testTheLastOfQuickSetLocaleCallsIsSaved() async throws {
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: FakeServer(), defaults: defaults)
        defer { sdk.stop() }
        sdk.setLocale("fr")
        sdk.setLocale("de")
        sdk.setCountry("FR")
        sdk.setCountry("DE")
        let saved = EncatchStorage(defaults: defaults).getPreferences()
        XCTAssertEqual(saved.locale, "de")
        XCTAssertEqual(saved.country, "DE")
    }

    func testConcurrentSavesNeverLoseAValue() {
        let storage = EncatchStorage(defaults: makeTestDefaults())
        for round in 0..<300 {
            storage.clearPreferences()
            DispatchQueue.concurrentPerform(iterations: 2) { index in
                if index == 0 { storage.setPreferences(locale: "l\(round)") } else { storage.setPreferences(country: "c\(round)") }
            }
            let saved = storage.getPreferences()
            XCTAssertEqual(saved.locale, "l\(round)", "round \(round)")
            XCTAssertEqual(saved.country, "c\(round)", "round \(round)")
        }
    }

    func testSetLocaleAndCountryBeforeInitializeDoNotCrash() {
        let sdk = Encatch()
        sdk.setLocale("fr")
        sdk.setCountry("FR")
        XCTAssertEqual(sdk.locale, "fr")
    }
}
