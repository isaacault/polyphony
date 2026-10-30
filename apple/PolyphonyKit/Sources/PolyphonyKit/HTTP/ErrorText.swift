import Foundation

/// What to tell the user about an error: PolyphonyKit's own errors say it in their description; any
/// other (a system one) in its localized description. (On Apple platforms every error bridges to
/// NSError, which is CustomStringConvertible too, so a cast can't tell them apart.)
public enum ErrorText {
    public static func of(_ error: Error) -> String {
        switch error {
        case let e as SubsonicError: e.description
        case let e as ContinuoError: e.description
        case let e as ConnectError: e.description
        case let e as TransportError: e.description
        case let e as DLNAError: e.description
        default: error.localizedDescription
        }
    }
}
