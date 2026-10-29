import Testing
@testable import PolyphonyKit

struct MD5Tests {
    @Test("RFC 1321 test vectors", arguments: [
        ("", "d41d8cd98f00b204e9800998ecf8427e"),
        ("a", "0cc175b9c0f1b6a831c399e269772661"),
        ("abc", "900150983cd24fb0d6963f7d28e17f72"),
        ("message digest", "f96b697d7cb7938d525a2f31aaf161d0"),
        ("abcdefghijklmnopqrstuvwxyz", "c3fcd3d76192e4007dfb496cca67e13b"),
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", "d174ab98d277d9f5a5611c2c9f419d9f"),
        (String(repeating: "1234567890", count: 8), "57edf4a22be3c955ac49da2e2107b67a"),
    ])
    func vectors(input: String, expected: String) {
        #expect(MD5.hex(input) == expected)
    }

    @Test func subsonicTokenExample() {
        // The Subsonic API docs' own example: password "sesame", salt "c19b2d".
        #expect(MD5.hex("sesame" + "c19b2d") == "26719a1196d2a940705a59634eb18eab")
    }
}
