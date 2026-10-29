import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

// The UPnP pieces a DLNA control point needs, ported from the Android app's (android/dlna), which
// plays to the Yamaha RX-V483: XML, SOAP, times, DIDL-Lite, SSDP and device descriptions. Pure, so
// they're tested here; the app supplies the discovery socket.

public struct DLNAError: Error, Sendable, Equatable, CustomStringConvertible {
    public let code: Int
    public let message: String
    public var description: String { code > 0 ? "\(message) (UPnP error \(code))" : message }
}

/// A small XML tree: element names without their namespace prefix, text trimmed.
final class XMLNode: @unchecked Sendable {
    let name: String
    var children: [XMLNode] = []
    var text = ""

    init(name: String) { self.name = name }

    func first(_ name: String) -> XMLNode? {
        for c in children {
            if c.name == name { return c }
            if let deeper = c.first(name) { return deeper }
        }
        return nil
    }

    func all(_ name: String) -> [XMLNode] {
        children.flatMap { ($0.name == name ? [$0] : []) + $0.all(name) }
    }

    func value(_ name: String) -> String? { first(name)?.text }

    static func parse(_ data: Data) -> XMLNode? {
        let delegate = Builder()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.shouldProcessNamespaces = false
        return parser.parse() ? delegate.root.children.first : nil
    }

    private final class Builder: NSObject, XMLParserDelegate {
        let root = XMLNode(name: "#root")
        private var stack: [XMLNode] = []

        override init() {
            super.init()
            stack = [root]
        }

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
            let local = elementName.split(separator: ":").last.map(String.init) ?? elementName
            let node = XMLNode(name: local)
            stack.last?.children.append(node)
            stack.append(node)
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            stack.last?.text += string
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            if let node = stack.popLast() { node.text = node.text.trimmingCharacters(in: .whitespacesAndNewlines) }
        }
    }
}

enum XMLEscape {
    static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}

public enum SOAP {
    public static func envelope(service: String, action: String, args: [(String, String)]) -> String {
        var s = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        s += "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
        s += "<s:Body><u:\(action) xmlns:u=\"\(XMLEscape.escape(service))\">"
        for (name, value) in args { s += "<\(name)>\(XMLEscape.escape(value))</\(name)>" }
        s += "</u:\(action)></s:Body></s:Envelope>"
        return s
    }

    /// The out-arguments of `<u:{action}Response>`; a SOAP fault throws its UPnP error.
    public static func parseResponse(_ data: Data, action: String) throws -> [String: String] {
        guard let root = XMLNode.parse(data) else { throw DLNAError(code: 0, message: "Unreadable answer to \(action)") }
        if let fault = root.first("Fault") {
            let code = Int(fault.value("errorCode") ?? "") ?? 500
            throw DLNAError(code: code, message: fault.value("errorDescription") ?? fault.value("faultstring") ?? "SOAP fault")
        }
        guard let response = root.first("\(action)Response") ?? root.first("Body")?.children.first else { return [:] }
        var out: [String: String] = [:]
        for child in response.children { out[child.name] = child.text }
        return out
    }
}

public enum UPnPTime {
    /// "0:03:05" or "0:03:05.250".
    public static func format(ms: Int, withMillis: Bool = false) -> String {
        let total = max(ms, 0)
        let h = total / 3_600_000, m = total / 60_000 % 60, s = total / 1000 % 60
        let base = "\(h):" + String(format: "%02d:%02d", m, s)
        return withMillis ? base + String(format: ".%03d", total % 1000) : base
    }

    /// Milliseconds from "H:MM:SS[.fff]"; nil for "NOT_IMPLEMENTED" and other non-times.
    public static func parse(_ value: String?) -> Int? {
        guard let value = value?.trimmingCharacters(in: .whitespaces), !value.isEmpty else { return nil }
        let parts = value.split(separator: ":")
        guard parts.count == 3, let h = Int(parts[0]), let m = Int(parts[1]) else { return nil }
        let secParts = parts[2].split(separator: ".", maxSplits: 1)
        guard let s = Int(secParts[0]) else { return nil }
        var ms = 0
        if secParts.count == 2 {
            let frac = String(secParts[1].prefix(3)).padding(toLength: 3, withPad: "0", startingAt: 0)
            ms = Int(frac) ?? 0
        }
        return ((h * 60 + m) * 60 + s) * 1000 + ms
    }
}

public enum DIDL {
    public static func protocolInfo(mime: String) -> String {
        let lower = mime.lowercased()
        let pn: String
        switch lower {
        case "audio/mpeg", "audio/mp3": pn = "DLNA.ORG_PN=MP3;"
        case "audio/mp4", "audio/aac", "audio/x-m4a": pn = "DLNA.ORG_PN=AAC_ISO_320;"
        case "audio/wav", "audio/x-wav": pn = "DLNA.ORG_PN=LPCM;"
        default: pn = lower.hasPrefix("audio/l16") ? "DLNA.ORG_PN=LPCM;" : ""
        }
        return "http-get:*:\(mime):\(pn)DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    }

    /// DIDL-Lite for one music track, as renderers want with SetAVTransportURI.
    public static func metadata(url: String, mime: String, title: String, artist: String?, album: String?,
                                artworkURL: String?, durationMs: Int?, itemID: String = "0") -> String {
        let e = XMLEscape.escape
        var s = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
        s += "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">"
        s += "<item id=\"\(e(itemID))\" parentID=\"-1\" restricted=\"1\">"
        s += "<dc:title>\(e(title.isEmpty ? "Unknown" : title))</dc:title>"
        if let artist, !artist.isEmpty { s += "<upnp:artist>\(e(artist))</upnp:artist><dc:creator>\(e(artist))</dc:creator>" }
        if let album, !album.isEmpty { s += "<upnp:album>\(e(album))</upnp:album>" }
        if let artworkURL, !artworkURL.isEmpty { s += "<upnp:albumArtURI>\(e(artworkURL))</upnp:albumArtURI>" }
        s += "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
        s += "<res protocolInfo=\"\(e(protocolInfo(mime: mime)))\""
        if let durationMs, durationMs > 0 { s += " duration=\"\(UPnPTime.format(ms: durationMs, withMillis: true))\"" }
        s += ">\(e(url))</res></item></DIDL-Lite>"
        return s
    }
}

public enum SSDP {
    public static let address = "239.255.255.250"
    public static let port: UInt16 = 1900
    public static let mediaRenderer = "urn:schemas-upnp-org:device:MediaRenderer:1"

    public static func search(_ target: String = mediaRenderer, mx: Int = 2) -> String {
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: \(mx)\r\nST: \(target)\r\n\r\n"
    }

    public struct Response: Sendable, Equatable {
        public var location: URL
        public var target: String?
        public var usn: String?
    }

    /// A search response or NOTIFY: its LOCATION (the device description) and what it is.
    public static func parse(_ datagram: String) -> Response? {
        var headers: [String: String] = [:]
        for line in datagram.components(separatedBy: "\r\n").dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].trimmingCharacters(in: .whitespaces).uppercased()] =
                line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        guard let location = headers["LOCATION"].flatMap(URL.init(string:)) else { return nil }
        return Response(location: location, target: headers["ST"] ?? headers["NT"], usn: headers["USN"])
    }
}

/// A DLNA speaker, from its device description.
public struct Renderer: Sendable, Hashable, Identifiable {
    public var id: String { udn }
    public var udn: String
    public var name: String
    public var manufacturer: String?
    public var model: String?
    public var avTransport: URL
    public var renderingControl: URL?

    public static let avTransportType = "urn:schemas-upnp-org:service:AVTransport:1"
    public static let renderingControlType = "urn:schemas-upnp-org:service:RenderingControl:1"

    /// Nil for a device without AVTransport (not a renderer).
    public static func parse(description data: Data, location: URL) -> Renderer? {
        guard let root = XMLNode.parse(data), let device = root.first("device") else { return nil }
        let base = root.value("URLBase").flatMap(URL.init(string:)) ?? location
        func control(_ type: String) -> URL? {
            for service in device.all("service") where (service.value("serviceType") ?? "").hasPrefix(type.dropLast(2)) {
                if let path = service.value("controlURL"), let url = URL(string: path, relativeTo: base) { return url.absoluteURL }
            }
            return nil
        }
        guard let av = control(avTransportType) else { return nil }
        return Renderer(udn: device.value("UDN") ?? location.absoluteString, name: device.value("friendlyName") ?? "Speaker",
                        manufacturer: device.value("manufacturer"), model: device.value("modelName"),
                        avTransport: av, renderingControl: control(renderingControlType))
    }
}
