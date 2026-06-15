import SwiftUI

struct SessionListView: View {
    let sessions: [ChatSession]
    @Binding var selectedSession: ChatSession
    let onNewSession: () -> Void
    let onDelete: (ChatSession) -> Void

    var body: some View {
        List {
            ForEach(sortedSessions) { session in
                SessionRowView(
                    session: session,
                    isSelected: session.id == selectedSession.id
                )
                .contentShape(Rectangle())
                .onTapGesture {
                    selectedSession = session
                }
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            }
            .onDelete { indexSet in
                for index in indexSet {
                    let session = sortedSessions[index]
                    onDelete(session)
                }
            }
        }
        .listStyle(.plain)
        .overlay {
            if sessions.isEmpty {
                ContentUnavailableView(
                    "No Sessions",
                    systemImage: "bubble.left",
                    description: Text("Tap the button below to start a new chat.")
                )
            }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: onNewSession) {
                    Image(systemName: "plus")
                }
            }
        }
    }

    private var sortedSessions: [ChatSession] {
        sessions.sorted { $0.updatedAt > $1.updatedAt }
    }
}

struct SessionRowView: View {
    let session: ChatSession
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "bubble.left.fill")
                .font(.title3)
                .foregroundStyle(isSelected ? .blue : .secondary)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 4) {
                Text(session.title)
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .lineLimit(1)
                    .foregroundColor(.primary)

                HStack(spacing: 4) {
                    Text(session.lastMessagePreview)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 4) {
                Text(session.formattedDate)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)

                Text("\(session.messages.count)")
                    .font(.caption2)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color(.systemGray5))
                    .clipShape(Capsule())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(isSelected ? Color.blue.opacity(0.08) : Color.clear)
                .padding(.horizontal, -8)
                .padding(.vertical, -4)
        )
    }
}

#Preview {
    NavigationStack {
        SessionListView(
            sessions: [
                ChatSession(title: "Hello World", messages: [.userMessage("Hi!")]),
                ChatSession(title: "Another Chat")
            ],
            selectedSession: .constant(ChatSession()),
            onNewSession: {},
            onDelete: { _ in }
        )
        .navigationTitle("Sessions")
    }
}
