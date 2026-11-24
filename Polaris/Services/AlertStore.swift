import Foundation
import Observation
import Security
import UIKit
@preconcurrency import UserNotifications

/// Your alerts, kept on the phone and mirrored to an alerts server (worker/) with this phone's
/// push token, so the server can tell you about trouble while the app is closed. With no server
/// (none built in, none entered in Settings) alerts are off: no requests, no push registration.
@MainActor @Observable
final class AlertStore {
    static let shared = AlertStore()

    private(set) var alerts: [RouteAlert] {
        didSet { save() }
    }

    /// What TfL says about each alert, by id, from the server.
    private(set) var status: [String: AlertStatus.Entry] = [:]
    private(set) var checkedAt: Date?
    private(set) var statusProblem: String?
    private(set) var syncProblem: String?
    private(set) var permission: UNNotificationSettings?
    /// Set when you tap one of these notifications.
    var showAlerts = false

    /// What the store reads and writes outside itself; tests give their own.
    struct Dependencies {
        var secrets: any SecretStore = SystemKeychain()
        var defaults: UserDefaults = .standard
        var builtInURL: String? = AlertStore.builtInURL
        var builtInToken: String? = Bundle.main.object(forInfoDictionaryKey: "PolarisAlertsToken") as? String
        var transport: @Sendable (URLRequest) async throws -> (Data, URLResponse) = AlertStore.boundedFetch
        /// How long sync waits for more edits before sending.
        var syncDelay: Duration = .milliseconds(400)
        /// Asks for notifications and a push token.
        var registerForPush: @MainActor () async -> UNNotificationSettings? = {
            let center = UNUserNotificationCenter.current()
            _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
            UIApplication.shared.registerForRemoteNotifications()
            return await center.notificationSettings()
        }
    }

    private let deps: Dependencies
    private var defaults: UserDefaults { deps.defaults }
    private var token: String? { didSet { defaults.set(token, forKey: "alerts.token") } }
    /// The token the server last heard, to forget it when the phone gets a new one.
    private var registered: String? { didSet { defaults.set(registered, forKey: "alerts.registered") } }
    private var syncing: Task<Void, Never>?
    private var statusTask: Task<Void, Never>?

    /// The server in use: one entered in Settings (Keychain), else the one built in. Nil until
    /// the Keychain could be read: a locked phone means wait, never another server.
    private(set) var server: AlertServer?
    private var serverLoaded = false
    var isConfigured: Bool { server != nil }
    /// An address entered in Settings, even one that doesn't pass (then alerts are off).
    private(set) var enteredURL = ""

    nonisolated static let builtInURL = Bundle.main.object(forInfoDictionaryKey: "PolarisAlertsURL") as? String
    static var hasBuiltInServer: Bool {
        AlertServer.resolve(enteredURL: "", enteredToken: "", builtInURL: builtInURL,
                            builtInToken: Bundle.main.object(forInfoDictionaryKey: "PolarisAlertsToken") as? String) != nil
    }
    static let urlAccount = "alerts.server.url", tokenAccount = "alerts.server.token"

    #if DEBUG
    private static let environment = "sandbox"
    #else
    private static let environment = "production"
    #endif

    init(_ deps: Dependencies = Dependencies()) {
        self.deps = deps
        alerts = deps.defaults.data(forKey: "alerts").flatMap { try? JSONDecoder().decode([RouteAlert].self, from: $0) } ?? []
        token = deps.defaults.string(forKey: "alerts.token")
        registered = deps.defaults.string(forKey: "alerts.registered")
        loadServer()
    }

    /// Reads the entered server; if the Keychain can't be read now, leaves alerts idle to try again.
    private func loadServer() {
        let url = deps.secrets.read(Self.urlAccount), token = deps.secrets.read(Self.tokenAccount)
        guard url != .unreadable, token != .unreadable else { return }
        enteredURL = url.value ?? ""
        server = AlertServer.resolve(enteredURL: enteredURL, enteredToken: token.value ?? "",
                                     builtInURL: deps.builtInURL, builtInToken: deps.builtInToken)
        serverLoaded = true
    }

    /// For tests: until the pending sync has gone out.
    func waitForSync() async { await syncing?.value }

    /// The server, once the Keychain has been read.
    private func currentServer() -> AlertServer? {
        if !serverLoaded { loadServer() }
        return serverLoaded ? server : nil
    }

    private var builtInAddress: URL? { deps.builtInURL.flatMap { try? AlertServer.address($0) } }

    /// This install's own secret for a server: it lets only the install that registered a phone
    /// change, test or remove it. Random, kept in the Keychain, never shown; one per address.
    /// The built-in server's from earlier builds is carried over, so those phones stay theirs.
    /// A Keychain that can't be read now means no request, never a new secret.
    func deviceSecret(for server: AlertServer) throws -> String {
        let account = server.secretAccount
        switch deps.secrets.read(account) {
        case .value(let saved) where saved.count >= 32: return saved
        case .unreadable: throw AlertError.keychainLocked
        default: break
        }
        if server.url == builtInAddress {
            switch deps.secrets.read(AlertServer.legacySecretAccount) {
            case .value(let legacy) where legacy.count >= 32:
                // Kept under its new name if that works; the old one stays either way.
                deps.secrets.write(legacy, for: account)
                return legacy
            case .unreadable: throw AlertError.keychainLocked
            default: break
            }
        }
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let secret = Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        // A secret that couldn't be kept would lock this phone out of its own registration later.
        guard deps.secrets.write(secret, for: account) else { throw AlertError.keychainLocked }
        return secret
    }

    // MARK: Choosing the server

    /// Switches to a server entered in Settings, or with an empty address back to the built-in
    /// one (or none). This phone is first removed from the server it leaves, with that server's
    /// own token and secret, so it stops pushing; nothing of the old server's goes to the new
    /// one. Alerts stay on the phone and are registered with the new server. Returns a note
    /// when the old server couldn't be told.
    @discardableResult
    func useServer(url: String, token appToken: String) async throws(AlertServer.Problem) -> String? {
        let url = url.trimmingCharacters(in: .whitespacesAndNewlines), appToken = appToken.trimmingCharacters(in: .whitespacesAndNewlines)
        let next: AlertServer?
        if url.isEmpty {
            next = AlertServer.resolve(enteredURL: "", enteredToken: "", builtInURL: deps.builtInURL, builtInToken: deps.builtInToken)
        } else {
            let address = try AlertServer.address(url)
            guard !appToken.isEmpty else { throw .noToken }
            next = AlertServer(url: address, token: appToken, isBuiltIn: false)
        }
        guard serverLoaded || { loadServer(); return serverLoaded }() else { throw .keychainLocked }
        syncing?.cancel()
        statusTask?.cancel()
        var note: String?
        // The same address (however it was typed) is the same server: no removal, just the new token.
        if let old = server, old.url != next?.url, let pushToken = registered {
            do { try await send("DELETE", path: "devices/\(pushToken)", to: old) } catch {
                note = "Couldn't remove this phone from \(old.label), so it may keep sending alerts until it's taken down."
            }
        }
        deps.secrets.write(url.isEmpty ? "" : url, for: Self.urlAccount)
        deps.secrets.write(url.isEmpty ? "" : appToken, for: Self.tokenAccount)
        enteredURL = url
        server = next
        registered = nil
        status = [:]
        checkedAt = nil
        statusProblem = nil
        syncProblem = nil
        if next != nil, !alerts.isEmpty {
            await enablePush()
            sync()
            await refreshStatus(force: true)
        }
        return note
    }

    private func save() {
        defaults.set(try? JSONEncoder().encode(alerts), forKey: "alerts")
    }

    // MARK: Editing

    func save(_ alert: RouteAlert) {
        if let index = alerts.firstIndex(where: { $0.id == alert.id }) { alerts[index] = alert } else { alerts.append(alert) }
        status[alert.id] = nil
        Task {
            await enablePush()
            await refreshStatus(force: true)
        }
        sync()
    }

    func delete(_ id: RouteAlert.ID) {
        alerts.removeAll { $0.id == id }
        status[id] = nil
        sync()
    }

    // MARK: Push

    /// Asks once, then registers for a token on every launch (it can change).
    func enablePush() async {
        guard !alerts.isEmpty, currentServer() != nil else { return }
        permission = await deps.registerForPush()
    }

    func refreshPermission() async {
        permission = await UNUserNotificationCenter.current().notificationSettings()
    }

    func didRegister(_ deviceToken: Data) {
        token = deviceToken.map { String(format: "%02x", $0) }.joined()
        sync()
    }

    func didFailToRegister(_ error: any Error) {
        syncProblem = "This phone couldn't get a push token: \(error.localizedDescription)"
    }

    /// The server's copy follows the phone's; the last change wins.
    private func sync() {
        syncing?.cancel()
        syncing = Task {
            try? await Task.sleep(for: deps.syncDelay)
            guard !Task.isCancelled, let token, let server = currentServer() else { return }
            do {
                if let old = registered, old != token { try await send("DELETE", path: "devices/\(old)", to: server) }
                if alerts.isEmpty {
                    try await send("DELETE", path: "devices/\(token)", to: server)
                    registered = nil
                } else {
                    let body = RouteAlert.Registration(environment: Self.environment, timeZone: TimeZone.current.identifier,
                                                       alerts: alerts.map(RouteAlert.Wire.init))
                    try await send("PUT", path: "devices/\(token)", body: JSONEncoder().encode(body), to: server)
                    registered = token
                }
                syncProblem = nil
            } catch is CancellationError {
            } catch {
                syncProblem = "Couldn't reach the alert server, so notifications may be out of date. It tries again next time you open the app."
            }
        }
    }

    /// On launch: pick up a new token, and resend in case the last change didn't reach the server.
    func resume() {
        guard !alerts.isEmpty, currentServer() != nil else { return }
        Task { await refreshPermission() }
        UIApplication.shared.registerForRemoteNotifications()
    }

    /// A real notification, sent the way trouble will be.
    func sendTest() async -> String {
        guard let server = currentServer() else { return "No alert server is set." }
        guard let token, registered == token else { return "This phone isn't registered with the alert server yet. Allow notifications, then try again." }
        do {
            try await send("POST", path: "devices/\(token)/test", to: server)
            return "Sent. It should arrive in a few seconds."
        } catch AlertError.server(let message) {
            return message
        } catch {
            return "Couldn't reach the alert server."
        }
    }

    // MARK: Status

    /// What TfL says now, at most once a minute.
    func refreshStatus(force: Bool = false) async {
        guard !alerts.isEmpty, let server = currentServer() else { return }
        if !force, let checkedAt, Date.now.timeIntervalSince(checkedAt) < 60, Set(status.keys) == Set(alerts.map(\.id)) { return }
        statusTask?.cancel()
        let task = Task {
            do {
                struct Body: Encodable { var alerts: [RouteAlert.Wire]; var timeZone: String }
                let body = try JSONEncoder().encode(Body(alerts: alerts.map(RouteAlert.Wire.init), timeZone: TimeZone.current.identifier))
                let result = try AlertStatus.decode(try await send("POST", path: "status", body: body, to: server))
                guard !Task.isCancelled else { return }
                status = Dictionary(result.alerts.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
                checkedAt = result.checkedAt
                statusProblem = nil
            } catch is CancellationError {
            } catch {
                if !Task.isCancelled { statusProblem = "Couldn't check TfL just now." }
            }
        }
        statusTask = task
        await task.value
    }

    // MARK: Server

    enum AlertError: Error { case server(String), keychainLocked, tooLarge }
    /// The most a reply may be: a status for 30 alerts is a few KB.
    nonisolated static let maxResponse = 512 * 1024

    /// One request to `server`, with its token and, for a phone's own routes, this install's
    /// secret for it. Redirects are refused: the token and secret go to that address only.
    @discardableResult
    func send(_ method: String, path: String, body: Data? = nil, to server: AlertServer) async throws -> Data {
        var request = URLRequest(url: server.url.appending(path: "v1/\(path)"))
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 20
        request.setValue("Bearer \(server.token)", forHTTPHeaderField: "Authorization")
        if path.hasPrefix("devices/") { request.setValue(try deviceSecret(for: server), forHTTPHeaderField: "X-Device-Secret") }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(HTTP.userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await deps.transport(request)
        guard data.count <= Self.maxResponse else { throw AlertError.tooLarge }   // for any transport
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            struct Problem: Decodable { var error: String?; var reason: String? }
            let problem = try? JSONDecoder().decode(Problem.self, from: data)
            // The server's own words, kept short: it is whatever address was entered.
            let said = problem?.error ?? problem?.reason.map { "Apple refused it: \($0)" } ?? "The alert server said \(code)."
            throw AlertError.server(String(said.prefix(200)))
        }
        return data
    }
}

extension AlertStore {
    /// Fetches without following redirects, refusing a reply larger than `maxResponse` from its
    /// Content-Length, or as it arrives, before it is all in memory.
    nonisolated static func boundedFetch(_ request: URLRequest) async throws -> (Data, URLResponse) {
        let (bytes, response) = try await URLSession.shared.bytes(for: request, delegate: NoRedirects.shared)
        if response.expectedContentLength > Int64(maxResponse) { bytes.task.cancel(); throw AlertError.tooLarge }
        var data = Data()
        for try await byte in bytes {
            data.append(byte)
            if data.count > maxResponse { bytes.task.cancel(); throw AlertError.tooLarge }
        }
        return (data, response)
    }
}

/// Alert requests go to the address given, never on to wherever it points.
final class NoRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    static let shared = NoRedirects()

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        nil
    }
}

/// Hands push tokens to the alerts.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        AlertStore.shared.didRegister(deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: any Error) {
        AlertStore.shared.didFailToRegister(error)
    }
}
