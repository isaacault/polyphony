import Darwin
import Foundation
import Observation
import PolyphonyKit

/// Finds DLNA speakers on the LAN: an SSDP search (UDP multicast), then each answer's device
/// description. macOS asks once for Local Network access (NSLocalNetworkUsageDescription).
@MainActor
@Observable
final class SpeakerDiscovery {
    private(set) var renderers: [Renderer] = []
    private(set) var searching = false

    func search() {
        guard !searching else { return }
        searching = true
        Task {
            let locations = await Task.detached { Self.ssdpSearch(seconds: 3) }.value
            var found: [String: Renderer] = Dictionary(uniqueKeysWithValues: renderers.map { ($0.udn, $0) })
            for location in locations {
                guard let data = try? await URLSession.shared.data(from: location).0,
                      let renderer = Renderer.parse(description: data, location: location) else { continue }
                found[renderer.udn] = renderer
            }
            renderers = found.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            searching = false
        }
    }

    /// Sends M-SEARCH for media renderers and collects the LOCATIONs answered within [seconds].
    nonisolated private static func ssdpSearch(seconds: Double) -> [URL] {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return [] }
        defer { close(fd) }
        var ttl: UInt8 = 2
        setsockopt(fd, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, socklen_t(MemoryLayout<UInt8>.size))
        var timeout = timeval(tv_sec: 0, tv_usec: 300_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        var dest = sockaddr_in()
        dest.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        dest.sin_family = sa_family_t(AF_INET)
        dest.sin_port = SSDP.port.bigEndian
        inet_pton(AF_INET, SSDP.address, &dest.sin_addr)
        let message = Array(SSDP.search().utf8)
        for _ in 0..<2 {   // UDP: say it twice
            _ = withUnsafePointer(to: &dest) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(fd, message, message.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }

        var locations: [URL] = []
        var buffer = [UInt8](repeating: 0, count: 4096)
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            let n = recv(fd, &buffer, buffer.count, 0)
            guard n > 0, let text = String(bytes: buffer[0..<n], encoding: .utf8),
                  let response = SSDP.parse(text) else { continue }
            if !locations.contains(response.location) { locations.append(response.location) }
        }
        return locations
    }
}
