// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-ios/TeamCode/ContentView.swift; see NOTICE.
import Companion
import SwiftUI
import UIKit

/// Hosts the shared Compose UI (`:companion`, framework `Companion`).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            // An artifact's page handed to the app (ostrom://open?url=…); the sign-in's return reaches its own session.
            .onOpenURL { url in
                if url.absoluteString.lowercased().hasPrefix("ostrom://open?") {
                    MainViewControllerKt.openLink(link: url.absoluteString)
                }
            }
    }
}
