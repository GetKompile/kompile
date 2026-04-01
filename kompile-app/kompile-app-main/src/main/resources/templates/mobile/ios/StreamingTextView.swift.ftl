import SwiftUI

struct StreamingTextView: View {
    let text: String
    @State private var displayedCount: Int = 0
    @State private var animationTimer: Timer?

    private let characterDelay: TimeInterval = 0.015

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    TypingIndicator()
                    Text("Assistant")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                HStack(alignment: .bottom, spacing: 0) {
                    Text(displayedText)
                        .font(.body)
                        .foregroundColor(.primary)

                    if displayedCount < text.count {
                        CursorView()
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color(.systemGray5))
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            }

            Spacer(minLength: 48)
        }
        .padding(.horizontal, 16)
        .onChange(of: text) { newValue in
            animateText(newValue)
        }
        .onAppear {
            displayedCount = 0
            animateText(text)
        }
        .onDisappear {
            animationTimer?.invalidate()
            animationTimer = nil
        }
    }

    private var displayedText: String {
        guard !text.isEmpty else { return "" }
        let endIndex = text.index(text.startIndex, offsetBy: min(displayedCount, text.count))
        return String(text[text.startIndex..<endIndex])
    }

    private func animateText(_ target: String) {
        animationTimer?.invalidate()

        guard displayedCount < target.count else { return }

        animationTimer = Timer.scheduledTimer(withTimeInterval: characterDelay, repeats: true) { timer in
            if displayedCount < target.count {
                displayedCount += 1
            } else {
                timer.invalidate()
                animationTimer = nil
            }
        }
    }
}

struct TypingIndicator: View {
    @State private var animating = false

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Color(.systemGray3))
                    .frame(width: 5, height: 5)
                    .scaleEffect(animating ? 1.0 : 0.5)
                    .animation(
                        .easeInOut(duration: 0.5)
                            .repeatForever(autoreverses: true)
                            .delay(Double(index) * 0.15),
                        value: animating
                    )
            }
        }
        .onAppear {
            animating = true
        }
    }
}

struct CursorView: View {
    @State private var visible = true

    var body: some View {
        Rectangle()
            .fill(Color.blue)
            .frame(width: 2, height: 16)
            .opacity(visible ? 1.0 : 0.0)
            .animation(
                .easeInOut(duration: 0.5)
                    .repeatForever(autoreverses: true),
                value: visible
            )
            .onAppear {
                visible = false
            }
    }
}

#Preview {
    StreamingTextView(text: "Hello, I am generating a response for you right now...")
        .padding()
}
