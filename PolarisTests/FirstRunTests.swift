import Foundation
import Testing
@testable import Polaris

/// The setup step: once, for new installs only, never for a phone updating with data.
@MainActor
struct FirstRunTests {
    private func defaults() -> UserDefaults {
        let name = "FirstRunTests.\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        return d
    }

    private let empty: (String) -> KeychainRead = { _ in .notFound }

    @Test func aNewInstallSeesItOnce() {
        let d = defaults()
        #expect(FirstRun.takeTurn(defaults: d, keychain: empty))
        #expect(!FirstRun.takeTurn(defaults: d, keychain: empty), "never again, finished or skipped")
    }

    /// What each store writes when used counts as data: the real stores, not a copy of their keys.
    @Test func anUpdatedInstallWithDataNeverSeesIt() {
        let uses: [(String, (UserDefaults) -> Void)] = [
            ("a saved place", { PlaceStore(defaults: $0).home = Place(name: "King's Cross", coordinate: .init(latitude: 51.53, longitude: -0.12)) }),
            ("a recent place", { PlaceStore(defaults: $0).remember(Place(name: "Bank", coordinate: .init(latitude: 51.51, longitude: -0.09))) }),
            ("a setting", { AppSettings(defaults: $0).deepSearch = false }),
            ("a sort order", { AppSettings(defaults: $0).sortOrder = .fastest }),
            ("Google's count", { GoogleUsage(defaults: $0).spend(1) }),
            ("an alert", { d in
                var deps = AlertStore.Dependencies()
                deps.defaults = d
                deps.secrets = AlertStoreTests.Secrets()
                deps.builtInURL = nil
                AlertStore(deps).save(RouteAlert())
            }),
        ]
        for (what, use) in uses {
            let d = defaults()
            use(d)
            #expect(!FirstRun.takeTurn(defaults: d, keychain: empty), "\(what)")
            #expect(d.bool(forKey: FirstRun.seenKey), "and it's decided for good")
        }
    }

    @Test func keysEnteredBeforeMeanNoSetup() {
        for account in FirstRun.keychainAccounts {
            let d = defaults()
            #expect(!FirstRun.takeTurn(defaults: d, keychain: { $0 == account ? .value("value") : .notFound }), "\(account)")
        }
        #expect(FirstRun.takeTurn(defaults: defaults(), keychain: { _ in .value("") }), "empty values don't count")
    }

    @Test func anUnreadableKeychainDecidesNothing() {
        let d = defaults()
        #expect(!FirstRun.takeTurn(defaults: d, keychain: { _ in .unreadable }), "never shown on a read that didn't happen")
        #expect(!d.bool(forKey: FirstRun.seenKey), "and nothing written: a later launch decides")
        #expect(FirstRun.takeTurn(defaults: d, keychain: empty), "readable and new: shown")
    }

    /// Every User-Agent names a contact: Settings' if valid, else the build's, else the project's page.
    @Test func theUserAgentAlwaysNamesAContact() {
        let project = "Polaris/0.1.0 (iOS transit app; https://github.com/VDEData/polaris)"
        #expect(HTTP.userAgent(version: "0.1.0", entered: nil, built: nil) == project)
        #expect(HTTP.userAgent(version: "0.1.0", entered: "", built: "") == project, "an xcconfig left empty")
        #expect(HTTP.userAgent(version: "0.1.0", entered: nil, built: "https://example.org/my-polaris")
                == "Polaris/0.1.0 (iOS transit app; https://example.org/my-polaris)")
        #expect(HTTP.userAgent(version: "0.1.0", entered: " someone@example.org ", built: "https://example.org/my-polaris")
                == "Polaris/0.1.0 (iOS transit app; someone@example.org)")
        #expect(HTTP.userAgent(version: "0.1.0", entered: "someone", built: "https://example.org/my-polaris")
                == "Polaris/0.1.0 (iOS transit app; https://example.org/my-polaris)", "an invalid entry falls back to the build's")
        #expect(HTTP.userAgent(version: "0.1.0", entered: "someone", built: "not a contact") == project)
        #expect(Contact.valid(Contact.project) == Contact.project)
    }

    @Test func contactValidation() {
        #expect(Contact.valid(" someone@example.org ") == "someone@example.org")
        #expect(Contact.valid("https://github.com/someone/polaris") != nil)
        #expect(Contact.valid("") == nil)
        #expect(Contact.valid("someone") == nil)
        #expect(Contact.valid("ftp://example.org") == nil)
        #expect(Contact.valid("a@b.c\r\nX-Evil: 1") == nil, "nothing that could break the header")
        #expect(Contact.valid(String(repeating: "a", count: 200) + "@example.org") == nil)
    }
}
