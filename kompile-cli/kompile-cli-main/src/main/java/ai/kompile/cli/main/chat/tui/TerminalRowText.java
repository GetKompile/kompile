package ai.kompile.cli.main.chat.tui;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

/** Text in a cursor-addressed row may set styles, but may never control the terminal. */
final class TerminalRowText {
    private TerminalRowText() { }

    static AttributedString fromAnsi(String text) {
        if (text == null || text.isEmpty()) return AttributedString.EMPTY;
        StringBuilder safe = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            int start = i;
            char ch = text.charAt(i++);
            boolean escape = ch == '\033';
            if (escape) {
                if (i == text.length()) break;
                ch = text.charAt(i++);
            }
            if ((escape && ch == '[') || (!escape && ch == '\u009b')) {
                // Consume CSI; only numeric SGR is safe to hand to JLine. It
                // preserves some other escapes as hidden text and re-emits them.
                boolean style = escape;
                while (i < text.length()) {
                    char part = text.charAt(i);
                    if (part == '\033') break; // Parse a new escape independently.
                    i++;
                    if (part >= '@' && part <= '~') {
                        if (style && part == 'm') safe.append(text, start, i);
                        break;
                    }
                    style &= (part >= '0' && part <= '9') || part == ';' || part == ':';
                }
            } else if ((escape && (ch == ']' || ch == 'P' || ch == 'X' || ch == '^' || ch == '_'))
                    || (!escape && (ch == '\u009d' || ch == '\u0090' || ch == '\u0098'
                    || ch == '\u009e' || ch == '\u009f'))) {
                // OSC/DCS/SOS/PM/APC payloads are not row text. Discard even an
                // unterminated command instead of leaking its payload or state.
                while (i < text.length()) {
                    char part = text.charAt(i++);
                    if (part == '\007' || part == '\u009c') break;
                    if (part == '\033' && i < text.length() && text.charAt(i) == '\\') {
                        i++;
                        break;
                    }
                }
            } else if (escape) {
                // Two-byte commands (notably ESC 7/8 save/restore cursor),
                // optionally preceded by intermediate bytes, are never text.
                while (ch >= ' ' && ch <= '/' && i < text.length()) ch = text.charAt(i++);
            } else if (!Character.isISOControl(ch) || ch == '\t') {
                // Expand tabs below before measuring/wrapping. Other C0/C1
                // controls can move the cursor or invalidate column accounting.
                safe.append(ch);
            }
        }
        // fromAnsi(String) leaves literal tabs intact; terminals expand them
        // based on the physical column, defeating wrapping and pane boundaries.
        return new AttributedStringBuilder().tabs(8).ansiAppend(safe.toString()).toAttributedString();
    }
}
