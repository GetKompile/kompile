import SwiftUI

struct ContentView: View {
    @EnvironmentObject var appSettings: AppSettings
    @EnvironmentObject var chatStorage: ChatStorageService
    @State private var selectedTab: Tab = .chat

    enum Tab: String, CaseIterable {
        case chat
        case sources
        case settings

        var title: String {
            switch self {
            case .chat: return "Chat"
            case .sources: return "Sources"
            case .settings: return "Settings"
            }
        }

        var iconName: String {
            switch self {
            case .chat: return "bubble.left.and.bubble.right.fill"
            case .sources: return "doc.text.fill"
            case .settings: return "gearshape.fill"
            }
        }
    }

    var body: some View {
        TabView(selection: $selectedTab) {
            ChatView()
                .tabItem {
                    Label(Tab.chat.title, systemImage: Tab.chat.iconName)
                }
                .tag(Tab.chat)

            SourceListView()
                .tabItem {
                    Label(Tab.sources.title, systemImage: Tab.sources.iconName)
                }
                .tag(Tab.sources)

            SettingsView()
                .tabItem {
                    Label(Tab.settings.title, systemImage: Tab.settings.iconName)
                }
                .tag(Tab.settings)
        }
        .tint(.blue)
    }
}

#Preview {
    ContentView()
        .environmentObject(AppSettings())
        .environmentObject(ChatStorageService())
}
