import Foundation
import Testing
@testable import Polaris

/// The alert store against a recorded transport and an in-memory Keychain: which server gets
/// which token and secret, what a locked Keychain does, and what the server's reply may be.
@MainActor
struct AlertStoreTests {
    final class Secrets: SecretStore, @unchecked Sendable {
        var items: [String: String] = [:]
        var locked: Set<String> = []
        var writes: [String] = []
        var failingWrites: Set<String> = []
        func read(_ account: String) -> KeychainRead {
            if locked.contains(account) { return .unreadable }
            return items[account].map { .value($0) } ?? .notFound
        }
        func write(_ value: String, for account: String) -> Bool {
            writes.append(account)
            if failingWrites.contains(account) { return false }
            items[account] = value.isEmpty ? nil : value
            return true
        }
    }

    final class Wire: @unchecked Sendable {
        var requests: [URLRequest] = []
        var failing: Set<String> = []
        var reply = Data(#"{"checkedAt":"2026-11-24T12:00:00Z","alerts":[]}"#.utf8)
        func send(_ request: URLRequest) throws -> (Data, URLResponse) {
            requests.append(request)
            if failing.contains(request.url?.host() ?? "") { throw URLError(.cannotConnectToHost) }
            return (reply, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        func to(_ host: String) -> [URLRequest] { requests.filter { $0.url?.host() == host } }
    }

    static let pushToken = String(repeating: "ab", count: 32)

    func store(secrets: Secrets = Secrets(), wire: Wire = Wire(), registered: Bool = true,
               builtIn: (String, String)? = ("https://built.example.com", "built-token")) -> AlertStore {
        let name = "AlertStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.set(try! JSONEncoder().encode([RouteAlert()]), forKey: "alerts")
        defaults.set(Self.pushToken, forKey: "alerts.token")
        if registered { defaults.set(Self.pushToken, forKey: "alerts.registered") }
        var deps = AlertStore.Dependencies()
        deps.secrets = secrets
        deps.defaults = defaults
        deps.builtInURL = builtIn?.0
        deps.builtInToken = builtIn?.1
        deps.transport = { try wire.send($0) }
        deps.registerForPush = { nil }
        deps.syncDelay = .zero
        return AlertStore(deps)
    }

    @Test func aTokenOnlyGoesToItsOwnAddress() async throws {
        let wire = Wire()
        let store = store(wire: wire)
        #expect(store.server?.isBuiltIn == true)
        let note = try await store.useServer(url: "https://mine.example.com", token: "mine-token")
        #expect(note == nil)
        await store.waitForSync()

        let old = wire.to("built.example.com"), new = wire.to("mine.example.com")
        #expect(old.map(\.httpMethod) == ["DELETE"], "the old server is only told to forget the phone")
        #expect(old.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer built-token" })
        #expect(new.contains { $0.httpMethod == "PUT" } && new.contains { $0.url?.path().hasSuffix("/v1/status") == true })
        #expect(new.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer mine-token" })
        let oldSecret = old.first?.value(forHTTPHeaderField: "X-Device-Secret")
        let newSecret = new.first { $0.httpMethod == "PUT" }?.value(forHTTPHeaderField: "X-Device-Secret")
        #expect(oldSecret != nil && newSecret != nil && oldSecret != newSecret, "each server its own secret")
    }

    @Test func aFailedRemovalIsSaid() async throws {
        let wire = Wire()
        wire.failing = ["built.example.com"]
        let store = store(wire: wire)
        let note = try await store.useServer(url: "https://mine.example.com", token: "mine-token")
        #expect(note?.contains("built.example.com") == true)
        #expect(store.server?.url.host() == "mine.example.com")
    }

    @Test func aLockedKeychainMeansWaitNotAnotherServer() async {
        let secrets = Secrets(), wire = Wire()
        secrets.items[AlertStore.urlAccount] = "https://mine.example.com"
        secrets.items[AlertStore.tokenAccount] = "mine-token"
        secrets.locked = [AlertStore.urlAccount]
        let store = store(secrets: secrets, wire: wire)
        #expect(store.server == nil, "not the built-in server")
        await store.refreshStatus(force: true)
        #expect(wire.requests.isEmpty)

        secrets.locked = []
        await store.refreshStatus(force: true)
        #expect(wire.requests.map { $0.url?.host() } == ["mine.example.com"], "once readable, the entered server")
    }

    @Test func aLockedSecretIsNeverReplaced() throws {
        let secrets = Secrets()
        let store = store(secrets: secrets)
        let server = try #require(store.server)
        secrets.locked = [server.secretAccount]
        #expect(throws: AlertStore.AlertError.self) { try store.deviceSecret(for: server) }
        #expect(secrets.writes.isEmpty)
    }

    @Test func theBuiltInServersOldSecretCarriesOver() throws {
        let secrets = Secrets()
        let legacy = String(repeating: "s", count: 43)
        secrets.items[AlertServer.legacySecretAccount] = legacy
        let store = store(secrets: secrets)
        let builtIn = try #require(store.server)
        #expect(try store.deviceSecret(for: builtIn) == legacy, "phones registered by earlier builds stay theirs")
        let typed = AlertServer(url: builtIn.url, token: "built-token", isBuiltIn: false)
        #expect(try store.deviceSecret(for: typed) == legacy, "and entering the same address keeps it")
        #expect(secrets.items[AlertServer.legacySecretAccount] == legacy, "the old item stays for older builds")
        let other = AlertServer(url: URL(string: "https://other.example.com")!, token: "t", isBuiltIn: false)
        #expect(try store.deviceSecret(for: other) != legacy)
    }

    @Test func theOldSecretUnreadableMeansWait() throws {
        let secrets = Secrets()
        secrets.locked = [AlertServer.legacySecretAccount]
        let store = store(secrets: secrets)
        let builtIn = try #require(store.server)
        #expect(throws: AlertStore.AlertError.self) { try store.deviceSecret(for: builtIn) }
        #expect(secrets.writes.isEmpty, "no new secret minted over one that exists")
    }

    @Test func aSecretThatWontSaveIsNotUsed() throws {
        let secrets = Secrets()
        let store = store(secrets: secrets)
        let other = AlertServer(url: URL(string: "https://other.example.com")!, token: "t", isBuiltIn: false)
        secrets.failingWrites = [other.secretAccount]
        #expect(throws: AlertStore.AlertError.self) { try store.deviceSecret(for: other) }
    }

    @Test func theBuiltInAddressTypedDifferentlyIsTheSameServer() async throws {
        let wire = Wire(), secrets = Secrets()
        let legacy = String(repeating: "s", count: 43)
        secrets.items[AlertServer.legacySecretAccount] = legacy
        let store = store(secrets: secrets, wire: wire)
        try await store.useServer(url: " HTTPS://Built.Example.com:443/ ", token: "built-token")
        await store.waitForSync()
        #expect(!wire.requests.contains { $0.httpMethod == "DELETE" }, "not removed and re-registered")
        let server = try #require(store.server)
        #expect(server.url.absoluteString == "https://built.example.com")
        #expect(try store.deviceSecret(for: server) == legacy)
    }

    @Test func anOversizedReplyIsRefused() async throws {
        let wire = Wire()
        wire.reply = Data(count: AlertStore.maxResponse + 1)
        let store = store(wire: wire)
        await #expect(throws: AlertStore.AlertError.self) { try await store.send("POST", path: "status", to: store.server!) }
    }

    @Test func noRedirects() async {
        let session = URLSession(configuration: .ephemeral)
        let task = session.dataTask(with: URL(string: "https://built.example.com/v1/status")!)
        let response = HTTPURLResponse(url: URL(string: "https://built.example.com/v1/status")!, statusCode: 302, httpVersion: nil,
                                       headerFields: ["Location": "https://elsewhere.example.com/"])!
        let next = await NoRedirects.shared.urlSession(session, task: task, willPerformHTTPRedirection: response,
                                                        newRequest: URLRequest(url: URL(string: "https://elsewhere.example.com/")!))
        #expect(next == nil)
    }

    @Test func serverTextIsKeptShort() throws {
        let long = String(repeating: "x", count: 10_000)
        let issues = (0..<40).map { _ in #"{"severity":"closed","title":"\#(long)","detail":"\#(long)"}"# }.joined(separator: ",")
        let json = #"{"checkedAt":"2026-11-24T12:00:00Z","alerts":[{"id":"a","issues":[\#(issues)],"upcoming":[]}]}"#
        let status = try AlertStatus.decode(Data(json.utf8))
        let entry = try #require(status.alerts.first)
        #expect(entry.issues.count == AlertStatus.maxIssues)
        #expect(entry.issues.allSatisfy { $0.title.count <= AlertStatus.maxTitle && $0.detail.count <= AlertStatus.maxDetail })
    }
}
