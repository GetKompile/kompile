import SwiftUI

struct ChatInputView: View {
    @Binding var text: String
    let isGenerating: Bool
    let onSend: () -> Void

    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Type a message...", text: $text, axis: .vertical)
                .lineLimit(1...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color(.systemGray6))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .focused($isFocused)
                .submitLabel(.send)
                .onSubmit {
                    if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        onSend()
                    }
                }

            Button(action: onSend) {
                Group {
                    if isGenerating {
                        ProgressView()
                            .tint(.white)
                    } else {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 16, weight: .semibold))
                    }
                }
                .frame(width: 36, height: 36)
                .foregroundColor(.white)
                .background(sendButtonColor)
                .clipShape(Circle())
            }
            .disabled(isSendDisabled)
            .animation(.easeInOut(duration: 0.15), value: isSendDisabled)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(
            Color(.systemBackground)
                .shadow(color: .black.opacity(0.06), radius: 8, x: 0, y: -2)
        )
    }

    private var isSendDisabled: Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isGenerating
    }

    private var sendButtonColor: Color {
        isSendDisabled ? Color(.systemGray4) : .blue
    }
}

#Preview {
    VStack {
        Spacer()
        ChatInputView(
            text: .constant("Hello"),
            isGenerating: false,
            onSend: {}
        )
    }
}
