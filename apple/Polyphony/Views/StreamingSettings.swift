import PolyphonyKit
import SwiftUI

/// Settings > Streaming: which quality applies when, as the Android app's Streaming & transcoding.
struct StreamingSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var editing: TranscodeProfile?

    private var rules: StreamingRules { model.streaming }

    var body: some View {
        Form {
            rightNow
            ruleSection
            remoteSection
            adaptiveSection
            profilesSection
        }
        .formStyle(.grouped)
        .sheet(item: $editing) { profile in
            ProfileEditor(profile: profile) { rules.save(profile: $0) }
        }
    }

    private var rightNow: some View {
        Section("Right now") {
            let d = rules.decision()
            LabeledContent(d.profile.name, value: d.explanation)
            Text(d.profile.summary).font(.caption).foregroundStyle(.secondary)
            if rules.latencyDegraded {
                Button("Restore quality") { rules.restoreQuality() }
            }
        }
    }

    private func policy<V>(_ field: WritableKeyPath<TranscodePolicy, V>) -> Binding<V> {
        Binding(get: { rules.policy[keyPath: field] }, set: { value in rules.update { $0[keyPath: field] = value } })
    }

    private func latency<V>(_ field: WritableKeyPath<AdaptiveLatencySettings, V>) -> Binding<V> {
        policy((\TranscodePolicy.adaptiveLatency).appending(path: field))
    }

    private var ruleSection: some View {
        Section("Rules") {
            Picker("Default profile", selection: policy(\.defaultProfileID)) {
                ForEach(rules.allProfiles) { Text($0.name).tag($0.id) }
            }
            profilePicker("On Wi-Fi or Ethernet", policy(\.wifiProfileID), none: "Use default profile")
            profilePicker("On metered connections", policy(\.cellularProfileID), none: "Use default profile")
            Toggle("Treat Wi-Fi away from home as metered", isOn: policy(\.awayWifiAsCellular))
            Toggle("Treat metered Wi-Fi as metered", isOn: policy(\.meteredWifiAsCellular))
            Toggle("Apply automatic quality changes to the current song", isOn: policy(\.reloadCurrentTrackOnChange))
            Text("Home is where the server answers at its LAN address (Settings > Servers > Speakers). On this Mac, Opus and Vorbis profiles come as MP3 of about the same quality, as Apple's player can't play them. An already-compressed file streams as it is, unless the connection is struggling.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private func profilePicker(_ title: String, _ selection: Binding<String?>, none: String) -> some View {
        Picker(title, selection: selection) {
            Text(none).tag(String?.none)
            ForEach(rules.allProfiles) { Text($0.name).tag(String?.some($0.id)) }
        }
    }

    private var remoteSection: some View {
        Section("Speakers and Cast") {
            profilePicker("Default profile for speakers", policy(\.castDefaultProfileID), none: "Use network rules")
            let devices = rules.stored.knownDevices.sorted { $0.value < $1.value }
            ForEach(devices, id: \.key) { device in
                HStack {
                    profilePicker(device.value, Binding(get: { rules.stored.deviceProfiles[device.key] },
                                                        set: { rules.setProfile($0, forDevice: device.key) }), none: "Default rules")
                    Button("Forget") { rules.forget(device: device.key) }
                }
            }
        }
    }

    private var adaptiveSection: some View {
        Section("Adaptive quality") {
            Toggle("Lower quality when buffering", isOn: latency(\.enabled))
            if rules.policy.adaptiveLatency.enabled {
                profilePicker("Cap to", latency(\.highLatencyProfileID), none: "None (disabled)")
                Picker("Restore quality after", selection: latency(\.recoverAfterSeconds)) {
                    Text("5 min").tag(300)
                    Text("10 min").tag(600)
                    Text("30 min").tag(1800)
                    Text("Never (manual only)").tag(0)
                }
                Stepper(value: latency(\.stallThreshold), in: 1...10) {
                    LabeledContent("Stalls before capping", value: "\(rules.policy.adaptiveLatency.stallThreshold)")
                }
                Picker("Within", selection: latency(\.windowSeconds)) {
                    Text("1 min").tag(60)
                    Text("2 min").tag(120)
                    Text("5 min").tag(300)
                }
                Picker("Single stall longer than", selection: latency(\.longStallMs)) {
                    Text("2 s").tag(2_000)
                    Text("4 s").tag(4_000)
                    Text("8 s").tag(8_000)
                }
                Toggle("Also apply on speakers and Cast", isOn: latency(\.applyToRemoteOutputs))
            }
        }
    }

    private var profilesSection: some View {
        Section("Profiles") {
            ForEach(rules.allProfiles) { profile in
                HStack {
                    VStack(alignment: .leading) {
                        Text(profile.name)
                        Text(profile.summary).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if profile.builtIn {
                        Text("Built-in").font(.caption).foregroundStyle(.secondary)
                    } else {
                        Button("Edit") { editing = profile }
                        Button("Delete", role: .destructive) { rules.delete(profile: profile.id) }
                    }
                }
            }
            Button("New Profile…") {
                editing = TranscodeProfile(id: "custom.\(UUID().uuidString)", name: "", codec: .mp3, maxBitrateKbps: 192)
            }
        }
    }
}

private struct ProfileEditor: View {
    @State var profile: TranscodeProfile
    let save: (TranscodeProfile) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            TextField("Name", text: $profile.name)
            Picker("Format", selection: $profile.codec) {
                ForEach(AudioCodec.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            if profile.codec.lossy {
                Picker("Maximum bitrate", selection: $profile.maxBitrateKbps) {
                    Text("Unlimited").tag(0)
                    ForEach([64, 96, 128, 160, 192, 256, 320], id: \.self) { Text("\($0) kbps").tag($0) }
                }
            }
            Picker("Maximum sample rate", selection: $profile.maxSampleRateHz) {
                Text("Any").tag(0)
                Text("44.1 kHz").tag(44_100)
                Text("48 kHz").tag(48_000)
                Text("96 kHz").tag(96_000)
            }
            Picker("Maximum bit depth", selection: $profile.maxBitDepth) {
                Text("Any").tag(0)
                Text("16-bit").tag(16)
                Text("24-bit").tag(24)
            }
            Text("Subsonic and Navidrome servers honour the format and bitrate; sample rate and bit depth reach only Jellyfin servers.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
        .frame(width: 420, height: 340)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    save(profile)
                    dismiss()
                }
                .disabled(profile.name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }
}
