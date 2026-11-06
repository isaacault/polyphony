import Foundation
import IOKit.ps

/// Whether the Mac is on mains power (a desktop always is), for Settings > Downloads.
enum PowerSource {
    static var onMains: Bool {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let type = IOPSGetProvidingPowerSourceType(info)?.takeUnretainedValue() else { return true }
        return (type as String) == kIOPSACPowerValue
    }
}
