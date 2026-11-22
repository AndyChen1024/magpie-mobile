import Foundation
import Network

nonisolated enum UsageTransportError: Error {
    case network, localPermission, tls, redirect, invalidResponse
}

nonisolated struct UsageHTTPResponse {
    let statusCode: Int
    let body: String
}

struct UsageHTTPTransport {
    let origin: UsageOrigin
    private let configuration: URLSessionConfiguration

    init(origin: UsageOrigin, configuration: URLSessionConfiguration = .ephemeral) {
        self.origin = origin
        self.configuration = configuration
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 20
    }

    func get(url address: String, authorization: String) async throws -> UsageHTTPResponse {
        try Task.checkCancellation()
        guard var parts = URLComponents(string: address),
              parts.user == nil, parts.password == nil, parts.fragment == nil,
              let url = parts.url else { throw UsageTransportError.redirect }
        parts.path = ""
        parts.query = nil
        guard let requestOrigin = parts.string, (try? UsageOrigin(requestOrigin)) == origin else {
            throw UsageTransportError.redirect
        }

        var request = URLRequest(url: url)
        request.setValue(authorization, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let session = URLSession(configuration: configuration, delegate: RejectRedirects(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            switch error.code {
            case .cancelled:
                throw CancellationError()
            case .serverCertificateHasBadDate, .serverCertificateUntrusted, .serverCertificateHasUnknownRoot,
                 .serverCertificateNotYetValid, .clientCertificateRejected, .clientCertificateRequired,
                 .secureConnectionFailed, .appTransportSecurityRequiresSecureConnection:
                throw UsageTransportError.tls
            case .notConnectedToInternet, .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed, .timedOut:
                if await reportsLocalPermissionDenial(url) { throw UsageTransportError.localPermission }
                try Task.checkCancellation()
                throw UsageTransportError.network
            default:
                throw UsageTransportError.network
            }
        }

        guard let http = response as? HTTPURLResponse else { throw UsageTransportError.invalidResponse }
        guard !(300..<400).contains(http.statusCode) else { throw UsageTransportError.redirect }
        guard let body = String(data: data, encoding: .utf8) else { throw UsageTransportError.invalidResponse }
        return UsageHTTPResponse(statusCode: http.statusCode, body: body)
    }

    private func reportsLocalPermissionDenial(_ url: URL) async -> Bool {
        guard let host = url.host(percentEncoded: false),
              let port = NWEndpoint.Port(rawValue: UInt16(url.port ?? 443)) else { return false }
        let ipOrName = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        let connection = NWConnection(host: NWEndpoint.Host(ipOrName), port: port, using: .tcp)
        let (stream, continuation) = AsyncStream.makeStream(of: Bool.self, bufferingPolicy: .bufferingNewest(1))
        connection.stateUpdateHandler = { [weak connection] state in
            switch state {
            case .waiting where connection?.currentPath?.unsatisfiedReason == .localNetworkDenied:
                continuation.yield(true)
                continuation.finish()
            case .ready, .failed, .cancelled:
                continuation.finish()
            default:
                break
            }
        }
        connection.start(queue: .global(qos: .utility))
        let timeout = Task {
            try? await Task.sleep(for: .milliseconds(750))
            continuation.finish()
        }
        defer {
            timeout.cancel()
            connection.cancel()
        }
        return await stream.first { _ in true } ?? false
    }
}

nonisolated private final class RejectRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}
