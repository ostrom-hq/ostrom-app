// SPDX-License-Identifier: Apache-2.0
import SwiftUI

@main
struct OstromApp: App {
    // Push and the tapped notifications (AppDelegate.swift).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
