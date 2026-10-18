package ai.kompile.cli.main.chat.tui;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TerminalRowTextTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "\0337", "\0338", "\033D", "\033E", "\033M", "\033c", "\033(B",
            "\033[24;1H", "\033[2J", "\033[1;24r", "\033[?1049h", "\033[6n", "\033[?25l",
            "\u009b24;1H", "\033]0;foreign title\007", "\033]52;c;clipboard\033\\",
            "\033]8;;https://example.com\033\\", "\033Pforeign payload\033\\",
            "\033Xforeign payload\033\\", "\033^foreign payload\033\\", "\033_foreign payload\033\\",
            "\u009dforeign title\u009c", "\u0090foreign payload\u009c", "\b", "\u0000", "\u007f"
    })
    void removesCommandsAndTheirPayloads(String command) {
        AttributedString row = TerminalRowText.fromAnsi("before" + command + "after");
        assertEquals("beforeafter", row.toString());
        assertEquals("beforeafter", row.toAnsi());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\033", "\033[24;", "\033]foreign title", "\033Pforeign payload",
            "\u009dforeign title", "\u0090foreign payload"})
    void removesUnterminatedCommands(String command) {
        assertEquals("before", TerminalRowText.fromAnsi("before" + command).toString());
    }

    @Test
    void stylesTabsAndUnicodeSurviveWithoutHiddenCommands() {
        String source = "\033[1;32m界 é\033[0m\tend";
        AttributedString expected = AttributedString.fromAnsi(source.replace("\t", "    "));
        AttributedString row = TerminalRowText.fromAnsi(source);
        assertEquals(expected, row);
        assertEquals(expected.columnLength(), row.columnLength());
        assertTrue(row.toAnsi().contains("\033["), "SGR styling must not be flattened");
        assertFalse(row.toString().codePoints().anyMatch(Character::isISOControl));
    }

    @Test
    void dropsAllSingleCharacterControlsExceptExpandedTab() {
        for (char control = 0; control < 160; control++) {
            if (!Character.isISOControl(control) || control == '\t') continue;
            String plain = TerminalRowText.fromAnsi("text" + control).toString();
            assertEquals("text", plain, "control " + (int) control);
        }
        assertEquals(AttributedString.EMPTY, TerminalRowText.fromAnsi(null));
    }
}
