import PolyphonyKit
import SwiftUI

/// Sign in to continuo (search, requests, Play now) or any Subsonic server (Navidrome…): the same
/// form; the app works out which it is.
struct SignInView: View {
    @Environment(AppModel.self) private var model
    @State private var address = "https://continuo.ault.me"
    @State private var username = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "waveform")
                .font(.system(size: 44, weight: .light))
                .foregroundStyle(.tint)
            VStack(spacing: 4) {
                Text("Polyphony").font(.largeTitle.weight(.semibold))
                Text("Sign in to continuo or any Subsonic server, like Navidrome.")
                    .foregroundStyle(.secondary)
            }
            Form {
                TextField("Server", text: $address, prompt: Text("continuo.example.com"))
                    .textContentType(.URL)
                TextField("Username", text: $username)
                    .textContentType(.username)
                SecureField("Password", text: $password)
                    .textContentType(.password)
                    .onSubmit(signIn)
            }
            .formStyle(.grouped)
            .frame(width: 380)
            .disabled(busy)

            if let error {
                Text(error).foregroundStyle(.red).font(.callout).multilineTextAlignment(.center).frame(width: 360)
            }
            Button(action: signIn) {
                if busy { ProgressView().controlSize(.small) } else { Text("Sign In").frame(width: 120) }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .keyboardShortcut(.defaultAction)
            .disabled(busy || address.isEmpty || username.isEmpty || password.isEmpty)
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func signIn() {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                try await model.signIn(address: address, username: username, password: password)
            } catch let e as ConnectError {
                error = e.description
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
