import Foundation
import Network

/// Says what connection the Mac is on, for the quality rules. A phone's hotspot or a connection set to
/// Low Data Mode counts as metered.
@MainActor
final class NetworkWatcher {
    var onChange: (NetworkState) -> Void = { _ in }
    private let monitor = NWPathMonitor()

    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            let state = Self.state(of: path)
            Task { @MainActor in self?.onChange(state) }
        }
        monitor.start(queue: DispatchQueue(label: "app.polyphony.network", qos: .utility))
    }

    private nonisolated static func state(of path: NWPath) -> NetworkState {
        guard path.status == .satisfied else { return .offline }
        let kind: NetworkKind
        if path.usesInterfaceType(.wiredEthernet) {
            kind = .ethernet
        } else if path.usesInterfaceType(.wifi) {
            kind = .wifi
        } else if path.usesInterfaceType(.cellular) {
            kind = .cellular
        } else {
            kind = .other
        }
        return NetworkState(kind: kind, isMetered: path.isExpensive || path.isConstrained)
    }
}
