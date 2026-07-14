import SwiftUI

@main
struct KompileChatLocalApp: App {

    @StateObject private var appSettings = AppSettings()
    @StateObject private var inferenceRouter = InferenceRouter()
    @StateObject private var graphReasoningService = GraphReasoningService()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appSettings)
                .environmentObject(inferenceRouter)
                .environmentObject(graphReasoningService)
                .onAppear {
                    inferenceRouter.configure(settings: appSettings)
                }
        }
    }
}
