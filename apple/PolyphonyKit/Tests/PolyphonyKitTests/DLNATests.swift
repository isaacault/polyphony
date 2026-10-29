import Foundation
import Testing
@testable import PolyphonyKit

struct UPnPTests {
    @Test func timesBothWays() {
        #expect(UPnPTime.format(ms: 185_250) == "0:03:05" && UPnPTime.format(ms: 185_250, withMillis: true) == "0:03:05.250")
        #expect(UPnPTime.format(ms: 3_725_000) == "1:02:05")
        #expect(UPnPTime.parse("0:03:05.25") == 185_250 && UPnPTime.parse("01:00:00") == 3_600_000)
        #expect(UPnPTime.parse("NOT_IMPLEMENTED") == nil && UPnPTime.parse(nil) == nil)
    }

    @Test func soapEnvelopeEscapesItsArguments() {
        let env = SOAP.envelope(service: Renderer.avTransportType, action: "SetAVTransportURI",
                                args: [("InstanceID", "0"), ("CurrentURI", "http://x/a?b=1&c=2")])
        #expect(env.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        #expect(env.contains("<CurrentURI>http://x/a?b=1&amp;c=2</CurrentURI>"))
    }

    @Test func responsesAndFaults() throws {
        let ok = #"<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><Track>1</Track><TrackDuration>0:03:20</TrackDuration><RelTime>0:00:42</RelTime></u:GetPositionInfoResponse></s:Body></s:Envelope>"#
        let out = try SOAP.parseResponse(Data(ok.utf8), action: "GetPositionInfo")
        #expect(out["RelTime"] == "0:00:42" && out["TrackDuration"] == "0:03:20")
        let fault = #"<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>716</errorCode><errorDescription>Resource not found</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"#
        #expect(throws: DLNAError(code: 716, message: "Resource not found")) { try SOAP.parseResponse(Data(fault.utf8), action: "Play") }
    }

    @Test func didlCarriesTheTrackAndAnEscapedURL() {
        let didl = DIDL.metadata(url: "http://192.168.0.101/rest/stream?id=1&t=x", mime: "audio/flac", title: "Speaker & Co",
                                 artist: "Alaskan Tapes", album: nil, artworkURL: nil, durationMs: 180_038)
        #expect(didl.contains("<dc:title>Speaker &amp; Co</dc:title>") && didl.contains("<upnp:artist>Alaskan Tapes</upnp:artist>"))
        #expect(didl.contains("duration=\"0:03:00.038\"") && didl.contains(">http://192.168.0.101/rest/stream?id=1&amp;t=x</res>"))
        #expect(DIDL.protocolInfo(mime: "audio/mpeg").contains("DLNA.ORG_PN=MP3;"))
    }

    @Test func ssdpResponses() throws {
        let reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.0.215:49154/MediaRenderer/desc.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:9ab0c000::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        let r = try #require(SSDP.parse(reply))
        #expect(r.location.absoluteString == "http://192.168.0.215:49154/MediaRenderer/desc.xml" && r.target == SSDP.mediaRenderer)
        #expect(SSDP.search().hasPrefix("M-SEARCH * HTTP/1.1\r\n") && SSDP.search().contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"))
    }

    @Test func aDeviceDescriptionGivesTheRenderer() throws {
        let xml = #"<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Lounge</friendlyName><manufacturer>Yamaha Corporation</manufacturer><modelName>RX-V483</modelName><UDN>uuid:9ab0c000-f668-11de-9976-00a0deffd733</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/AVTransport/ctrl</controlURL></service><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/RenderingControl/ctrl</controlURL></service></serviceList></device></root>"#
        let r = try #require(Renderer.parse(description: Data(xml.utf8), location: URL(string: "http://192.168.0.215:49154/MediaRenderer/desc.xml")!))
        #expect(r.name == "Lounge" && r.model == "RX-V483" && r.udn.hasPrefix("uuid:9ab0c000"))
        #expect(r.avTransport.absoluteString == "http://192.168.0.215:49154/AVTransport/ctrl")
        #expect(r.renderingControl?.absoluteString == "http://192.168.0.215:49154/RenderingControl/ctrl")
    }

    @Test func somethingWithoutAVTransportIsntARenderer() {
        let xml = #"<root><device><friendlyName>NAS</friendlyName><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/cd</controlURL></service></serviceList></device></root>"#
        #expect(Renderer.parse(description: Data(xml.utf8), location: URL(string: "http://h/d.xml")!) == nil)
    }
}

/// A speaker that answers SOAP from a script: its transport state and position.
private final class FakeSpeaker: @unchecked Sendable {
    var state = "STOPPED"
    var position = "0:00:00"
    var actions: [String] = []
    var lastURI: String?

    func respond(_ request: HTTPRequest) -> HTTPResponse {
        let action = (request.headers["SOAPACTION"] ?? "").split(separator: "#").last.map { String($0.dropLast()) } ?? ""
        actions.append(action)
        let body = String(data: request.body ?? Data(), encoding: .utf8) ?? ""
        if action == "SetAVTransportURI", let r = body.range(of: "<CurrentURI>"), let e = body.range(of: "</CurrentURI>") {
            lastURI = String(body[r.upperBound..<e.lowerBound])
        }
        let out: String
        switch action {
        case "GetTransportInfo": out = "<CurrentTransportState>\(state)</CurrentTransportState>"
        case "GetPositionInfo": out = "<RelTime>\(position)</RelTime><TrackDuration>0:03:20</TrackDuration>"
        default: out = ""
        }
        return HTTPResponse(status: 200, body: Data("<s:Envelope xmlns:s=\"x\"><s:Body><u:\(action)Response>\(out)</u:\(action)Response></s:Body></s:Envelope>".utf8))
    }
}

@MainActor
struct DLNAEngineTests {
    private let renderer = Renderer(udn: "uuid:1", name: "Lounge", manufacturer: nil, model: nil,
                                    avTransport: URL(string: "http://192.168.0.215:49154/AVTransport/ctrl")!,
                                    renderingControl: URL(string: "http://192.168.0.215:49154/RenderingControl/ctrl")!)

    private func engine(_ speaker: FakeSpeaker) -> (DLNAEngine, [AudioEvent]) {
        let e = DLNAEngine(control: DLNAControl(renderer: renderer, transport: FakeTransport { speaker.respond($0) }), autoPoll: false)
        return (e, [])
    }

    @Test func loadingSetsTheURIWithMetadataAndPlays() async {
        let speaker = FakeSpeaker()
        let (e, _) = engine(speaker)
        e.load(AudioSource(url: URL(string: "http://192.168.0.101/rest/stream?id=1")!,
                           info: .init(title: "Speaker", artist: "Alaskan Tapes", durationMs: 180_000)), startAt: 0, autoplay: true)
        for _ in 0..<50 { await Task.yield() }
        #expect(speaker.actions == ["SetAVTransportURI", "Play"] && speaker.lastURI == "http://192.168.0.101/rest/stream?id=1")
    }

    @Test func pollingReportsPlayingPositionAndTheEnd() async {
        let speaker = FakeSpeaker()
        let (e, _) = engine(speaker)
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        e.load(AudioSource(url: URL(string: "http://h/a")!), startAt: 0, autoplay: true)
        for _ in 0..<50 { await Task.yield() }
        speaker.state = "PLAYING"; speaker.position = "0:00:42"
        await e.pollOnce()
        #expect(events.contains(.started) && events.contains(.position(42, duration: 200)))
        speaker.state = "STOPPED"
        await e.pollOnce()
        #expect(events.last == .ended)
        await e.pollOnce()
        #expect(events.filter { $0 == .ended }.count == 1, "once")
    }

    @Test func ourOwnStopIsntAnEnd() async {
        let speaker = FakeSpeaker()
        let (e, _) = engine(speaker)
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        e.load(AudioSource(url: URL(string: "http://h/a")!), startAt: 0, autoplay: true)
        speaker.state = "PLAYING"
        await e.pollOnce()
        e.stop()
        speaker.state = "STOPPED"
        await e.pollOnce()
        #expect(!events.contains(.ended))
    }

    @Test func aPausedSpeakerSaysPaused() async {
        let speaker = FakeSpeaker()
        let (e, _) = engine(speaker)
        var events: [AudioEvent] = []
        e.onEvent = { events.append($0) }
        e.load(AudioSource(url: URL(string: "http://h/a")!), startAt: 0, autoplay: true)
        speaker.state = "PLAYING"; await e.pollOnce()
        speaker.state = "PAUSED_PLAYBACK"; await e.pollOnce()
        #expect(events.last(where: { $0 == .paused || $0 == .started }) == .paused)
    }
}
