import SwiftUI

/// Root view.  Hosts a TabView with Chat and Settings tabs.
/// Initialises the ChatEngine and GraphReasoningService on appear.
struct ContentView: View {

    @EnvironmentObject var appSettings: AppSettings
    @EnvironmentObject var inferenceRouter: InferenceRouter
    @EnvironmentObject var graphReasoningService: GraphReasoningService

    var body: some View {
        TabView {
            ChatView()
                .tabItem {
                    Label("Chat", systemImage: "bubble.left.and.bubble.right.fill")
                }

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape.fill")
                }
        }
        .onAppear {
            graphReasoningService.initialise()
            // If a kgraph path was persisted from a previous session, reload it.
            if appSettings.hasKgraph {
                graphReasoningService.openGraph(at: appSettings.kgraphPath)
            }
        }
    }
}

#Preview {
    ContentView()
        .environmentObject(AppSettings())
        .environmentObject(InferenceRouter())
        .environmentObject(GraphReasoningService())
}
