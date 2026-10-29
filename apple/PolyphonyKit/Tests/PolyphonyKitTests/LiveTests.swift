import Foundation
import Testing
@testable import PolyphonyKit

/// Against real servers, only when asked (POLYPHONY_LIVE_CONTINUO / POLYPHONY_LIVE_SUBSONIC set):
/// the refusals a wrong password gets, as continuo and Navidrome really send them.
struct LiveTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["POLYPHONY_LIVE_CONTINUO"] != nil))
    func aWrongPasswordAtTheRealContinuo() async {
        let address = ProcessInfo.processInfo.environment["POLYPHONY_LIVE_CONTINUO"]!
        await #expect(throws: ConnectError.wrongPassword) {
            _ = try await Connector.connect(address: address, username: "polyphony-live-test", password: "not-the-password",
                                            deviceName: "PolyphonyKit live test")
        }
    }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["POLYPHONY_LIVE_SUBSONIC"] != nil))
    func aWrongPasswordAtARealSubsonicServer() async {
        let address = ProcessInfo.processInfo.environment["POLYPHONY_LIVE_SUBSONIC"]!
        await #expect(throws: ConnectError.wrongPassword) {
            _ = try await Connector.connect(address: address, username: "polyphony-live-test", password: "not-the-password",
                                            deviceName: "PolyphonyKit live test")
        }
    }
}
