import Foundation
import Network
import Observation
import PolyphonyKit

/// Finds Cast devices on the LAN by Bonjour (_googlecast._tcp), which Local Network access covers.
@MainActor
@Observable
final class CastDiscovery {
    private(set) var devices: [CastDevice] = []
    @ObservationIgnored private var endpoints: [String: NWEndpoint] = [:]
    @ObservationIgnored private var browser: NWBrowser?

    func start() {
        guard browser == nil else { return }
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_googlecast._tcp", domain: nil), using: parameters)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let found = results.compactMap { Self.device(from: $0) }
            Task { @MainActor in self?.update(found) }
        }
        browser.start(queue: DispatchQueue(label: "app.polyphony.cast"))
        self.browser = browser
    }

    func endpoint(of device: CastDevice) -> NWEndpoint? { endpoints[device.id] }

    private func update(_ found: [(CastDevice, NWEndpoint)]) {
        endpoints = Dictionary(found.map { ($0.0.id, $0.1) }, uniquingKeysWith: { $1 })
        devices = found.map(\.0).sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    /// TXT record: id, fn (its name), md (its model).
    private nonisolated static func device(from result: NWBrowser.Result) -> (CastDevice, NWEndpoint)? {
        guard case let .bonjour(txt) = result.metadata else { return nil }
        let id = txt["id"] ?? { if case let .service(name, _, _, _) = result.endpoint { return name } else { return nil } }()
        guard let id else { return nil }
        return (CastDevice(id: id, name: txt["fn"] ?? "Cast device", model: txt["md"]), result.endpoint)
    }
}

/// The TLS connection to a Cast device. Its certificate is self-signed, so it isn't checked: as with a
/// speaker on the LAN, what's found on the network is trusted with the song's link.
@MainActor
final class NetworkCastSocket: CastSocket {
    var onConnected: (() -> Void)?
    var onBytes: ((Data) -> Void)?
    var onClosed: ((String?) -> Void)?
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "app.polyphony.cast.socket")

    init(endpoint: NWEndpoint) {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, _, complete in complete(true) }, DispatchQueue.global())
        connection = NWConnection(to: endpoint, using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
    }

    func connect() {
        connection.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                switch state {
                case .ready:
                    self?.onConnected?()
                    self?.receive()
                case let .failed(error):
                    self?.onClosed?(error.localizedDescription)
                case let .waiting(error):
                    self?.onClosed?(error.localizedDescription)
                default:
                    break
                }
            }
        }
        connection.start(queue: queue)
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, complete, error in
            Task { @MainActor in
                guard let self else { return }
                if let data, !data.isEmpty { self.onBytes?(data) }
                if let error {
                    self.onClosed?(error.localizedDescription)
                } else if complete {
                    self.onClosed?(nil)
                } else {
                    self.receive()
                }
            }
        }
    }

    func send(_ data: Data) {
        connection.send(content: data, completion: .idempotent)
    }

    func close() {
        connection.cancel()
    }
}
