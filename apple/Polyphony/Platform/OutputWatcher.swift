import CoreAudio
import Foundation

/// Says when headphones go away (AirPods disconnect, a cable is pulled, a USB headset is unplugged) so
/// the Mac's own speakers would take over: the player pauses, as the Android app's "pause on
/// disconnect". Plugging headphones in doesn't count.
@MainActor
final class OutputWatcher {
    var onHeadphonesGone: () -> Void = {}

    private var wasHeadphones = false
    private var deviceListener: AudioObjectPropertyListenerBlock?
    /// The built-in output's data source (headphone jack or speakers) on the current device.
    private var sourceDevice: AudioObjectID?
    private var sourceListener: AudioObjectPropertyListenerBlock?

    private static var defaultOutputAddress = AudioObjectPropertyAddress(
        mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain)
    private static var dataSourceAddress = AudioObjectPropertyAddress(
        mSelector: kAudioDevicePropertyDataSource, mScope: kAudioDevicePropertyScopeOutput,
        mElement: kAudioObjectPropertyElementMain)

    func start() {
        guard deviceListener == nil else { return }
        wasHeadphones = Self.isHeadphones(Self.defaultOutput())
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            MainActor.assumeIsolated { self?.changed() }
        }
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &Self.defaultOutputAddress, .main, block)
        deviceListener = block
        watchDataSource()
    }

    private func changed() {
        let now = Self.isHeadphones(Self.defaultOutput())
        if wasHeadphones && !now { onHeadphonesGone() }
        wasHeadphones = now
        watchDataSource()
    }

    /// Follows the current device's data source, which is how the built-in jack says headphones.
    private func watchDataSource() {
        let device = Self.defaultOutput()
        guard device != sourceDevice else { return }
        if let old = sourceDevice, let block = sourceListener {
            AudioObjectRemovePropertyListenerBlock(old, &Self.dataSourceAddress, .main, block)
        }
        sourceDevice = nil
        sourceListener = nil
        guard device != 0, AudioObjectHasProperty(device, &Self.dataSourceAddress) else { return }
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            MainActor.assumeIsolated { self?.changed() }
        }
        if AudioObjectAddPropertyListenerBlock(device, &Self.dataSourceAddress, .main, block) == noErr {
            sourceDevice = device
            sourceListener = block
        }
    }

    private static func defaultOutput() -> AudioObjectID {
        var device = AudioObjectID(0)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        let status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &defaultOutputAddress, 0, nil, &size, &device)
        return status == noErr ? device : 0
    }

    private static func uint32(_ device: AudioObjectID, _ selector: AudioObjectPropertySelector,
                               scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> UInt32? {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectHasProperty(device, &address) else { return nil }
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        return AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value) == noErr ? value : nil
    }

    /// Bluetooth and USB outputs are headphones (or a headset) here; the built-in output is when its
    /// data source is the headphone jack ('hdpn').
    private static func isHeadphones(_ device: AudioObjectID) -> Bool {
        guard device != 0, let transport = uint32(device, kAudioDevicePropertyTransportType) else { return false }
        switch transport {
        case kAudioDeviceTransportTypeBluetooth, kAudioDeviceTransportTypeBluetoothLE, kAudioDeviceTransportTypeUSB:
            return true
        case kAudioDeviceTransportTypeBuiltIn:
            return uint32(device, kAudioDevicePropertyDataSource, scope: kAudioDevicePropertyScopeOutput) == 0x6864_706E  // 'hdpn'
        default:
            return false
        }
    }
}
