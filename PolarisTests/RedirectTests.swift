import Foundation
import Network
import Synchronization
import Testing
@testable import Polaris

/// Provider requests through `HTTP.data` against real HTTP servers on the loopback address, so
/// what URLSession carries across a redirect is what it really carries. A redirect may lead only
/// to the same scheme, host and port, and a request carrying a key is not redirected at all.
@Suite(.serialized)
struct RedirectTests {
    @Test func aSameHostRedirectIsFollowed() async throws {
        let server = try await LoopbackServer.start()
        defer { server.stop() }
        let data = try await HTTP.data(for: URLRequest(url: server.url("/to-same")))
        #expect(String(decoding: data, as: UTF8.self) == "arrived")
        #expect(server.received.map(\.path) == ["/to-same", "/arrived"])
        // Both hops carry the User-Agent, with a contact (whichever this build has).
        let agent = /Polaris\/[^ ]+ \(iOS transit app; [^()\s]+\)/
        #expect(server.received.allSatisfy { $0.headers["user-agent"].map { (try? agent.wholeMatch(in: $0)) != nil } ?? false },
                "\(server.received.map { $0.headers["user-agent"] ?? "none" })")
    }

    @Test func aRedirectToAnotherHostIsRefused() async throws {
        let server = try await LoopbackServer.start()
        defer { server.stop() }
        // 127.0.0.1 and localhost are the same machine but not the same host.
        await #expect(throws: ProviderError.http(302)) {
            try await HTTP.data(for: URLRequest(url: server.url("/to-other-host")))
        }
        #expect(server.received.map(\.path) == ["/to-other-host"])
    }

    @Test func aRedirectToAnotherPortIsRefused() async throws {
        let server = try await LoopbackServer.start()
        let other = try await LoopbackServer.start()
        defer { server.stop(); other.stop() }
        server.elsewhere = other.url("/arrived")
        await #expect(throws: ProviderError.http(302)) {
            try await HTTP.data(for: URLRequest(url: server.url("/to-elsewhere")))
        }
        #expect(other.received.isEmpty)
    }

    @Test func aGoogleRequestIsNeverRedirected() async throws {
        let server = try await LoopbackServer.start()
        defer { server.stop() }
        var request = URLRequest(url: server.url("/to-same"))
        request.setValue("test-google-key", forHTTPHeaderField: "X-Goog-Api-Key")
        await #expect(throws: ProviderError.http(302)) { try await HTTP.data(for: request) }
        #expect(server.received.map(\.path) == ["/to-same"], "not even to the same host")
    }

    @Test func aTfLKeyInTheQueryIsNeverRedirected() async throws {
        let server = try await LoopbackServer.start()
        defer { server.stop() }
        await #expect(throws: ProviderError.http(302)) {
            try await HTTP.data(for: URLRequest(url: server.url("/to-same?app_key=test-tfl-key")))
        }
        #expect(server.received.map(\.path) == ["/to-same?app_key=test-tfl-key"], "not even to the same host")
    }

    @Test func noKeyReachesTheSecondHost() async throws {
        let server = try await LoopbackServer.start()
        let other = try await LoopbackServer.start()
        defer { server.stop(); other.stop() }
        // The second place keeps the query, as a server pointing elsewhere might.
        server.elsewhere = other.url("/arrived?app_key=test-tfl-key")
        for path in ["/to-other-host", "/to-elsewhere"] {
            var request = URLRequest(url: server.url("\(path)?app_key=test-tfl-key"))
            request.setValue("test-google-key", forHTTPHeaderField: "X-Goog-Api-Key")
            _ = try? await HTTP.data(for: request)
        }
        let seen = server.received.filter { $0.host.hasPrefix("localhost") } + other.received
        #expect(seen.isEmpty, "the second host saw \(seen)")
        #expect(!seen.contains { $0.headers["x-goog-api-key"] != nil || $0.path.contains("app_key") })
    }

    @Test func theSessionKeepsTheSharedSettings() {
        let mine = HTTP.session.configuration, shared = URLSession.shared.configuration
        #expect(mine.urlCache === shared.urlCache)
        #expect(mine.httpCookieStorage === shared.httpCookieStorage)
        #expect(mine.urlCredentialStorage === shared.urlCredentialStorage)
        #expect(mine.requestCachePolicy == shared.requestCachePolicy)
        #expect(mine.allowsCellularAccess == shared.allowsCellularAccess)
        #expect(mine.allowsExpensiveNetworkAccess == shared.allowsExpensiveNetworkAccess)
        #expect(mine.allowsConstrainedNetworkAccess == shared.allowsConstrainedNetworkAccess)
        #expect(mine.waitsForConnectivity == shared.waitsForConnectivity)
        #expect(mine.timeoutIntervalForRequest == shared.timeoutIntervalForRequest)
        #expect(mine.timeoutIntervalForResource == shared.timeoutIntervalForResource)
        #expect(mine.httpMaximumConnectionsPerHost == shared.httpMaximumConnectionsPerHost)
        #expect(mine.httpAdditionalHeaders == nil && shared.httpAdditionalHeaders == nil)
    }
}

/// A tiny HTTP/1.1 server on 127.0.0.1 that records what reaches it. Paths:
/// `/to-same` redirects to `/arrived` here, `/to-other-host` to the same port as `localhost`,
/// `/to-elsewhere` to `elsewhere`, and `/arrived` answers "arrived".
final class LoopbackServer: Sendable {
    struct Received: Sendable, CustomStringConvertible {
        var host: String
        var path: String
        var headers: [String: String]
        var description: String { "\(host)\(path) \(headers)" }
    }

    private let listener: NWListener
    private let state = Mutex<(received: [Received], elsewhere: URL?)>(([], nil))
    let port: UInt16

    var received: [Received] { state.withLock { $0.received } }
    var elsewhere: URL? {
        get { state.withLock { $0.elsewhere } }
        set { state.withLock { $0.elsewhere = newValue } }
    }

    func url(_ path: String) -> URL { URL(string: "http://127.0.0.1:\(port)\(path)")! }

    private init(listener: NWListener, port: UInt16) {
        self.listener = listener
        self.port = port
    }

    static func start() async throws -> LoopbackServer {
        let listener = try NWListener(using: .tcp, on: .any)
        let queue = DispatchQueue(label: "LoopbackServer")
        let port: UInt16 = try await withCheckedThrowingContinuation { continuation in
            let resumed = Mutex(false)
            listener.stateUpdateHandler = { state in
                let result: Result<UInt16, any Error>? = switch state {
                case .ready: .success(listener.port?.rawValue ?? 0)
                case .failed(let error): .failure(error)
                default: nil
                }
                guard let result, resumed.withLock({ was in defer { was = true }; return !was }) else { return }
                continuation.resume(with: result)
            }
            listener.newConnectionHandler = { _ in }
            listener.start(queue: queue)
        }
        let server = LoopbackServer(listener: listener, port: port)
        listener.newConnectionHandler = { [server] connection in server.serve(connection, on: queue) }
        return server
    }

    func stop() { listener.cancel() }

    private func serve(_ connection: NWConnection, on queue: DispatchQueue) {
        connection.start(queue: queue)
        read(connection, buffer: Data())
    }

    private func read(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [self] chunk, _, done, error in
            let buffer = buffer + (chunk ?? Data())
            guard let end = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                if done || error != nil { connection.cancel() } else { read(connection, buffer: buffer) }
                return
            }
            let head = String(decoding: buffer[..<end.lowerBound], as: UTF8.self)
            connection.send(content: respond(to: head), completion: .contentProcessed { _ in connection.cancel() })
        }
    }

    private func respond(to head: String) -> Data {
        let lines = head.components(separatedBy: "\r\n")
        let path = lines.first?.split(separator: " ").dropFirst().first.map(String.init) ?? "/"
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        let host = headers["host"] ?? ""
        state.withLock { $0.received.append(Received(host: host, path: path, headers: headers)) }

        let route = path.split(separator: "?").first.map(String.init) ?? path
        let location: String? = switch route {
        case "/to-same": "http://127.0.0.1:\(port)/arrived"
        case "/to-other-host": "http://localhost:\(port)/arrived"
        case "/to-elsewhere": elsewhere?.absoluteString
        default: nil
        }
        let status = location == nil ? "200 OK" : "302 Found"
        let body = location == nil ? "arrived" : ""
        var reply = "HTTP/1.1 \(status)\r\nContent-Length: \(body.utf8.count)\r\nCache-Control: no-store\r\nConnection: close\r\n"
        if let location { reply += "Location: \(location)\r\n" }
        return Data((reply + "\r\n" + body).utf8)
    }
}
