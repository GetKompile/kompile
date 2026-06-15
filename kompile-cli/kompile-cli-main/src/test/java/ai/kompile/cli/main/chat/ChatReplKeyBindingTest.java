package ai.kompile.cli.main.chat;

import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TTY key binding test — verifies that normal typing characters (a-z, A-Z)
 * are NOT intercepted by widget chord bindings.
 *
 * The root cause of the bug: JLine's {@code KeyMap.bind(fn, CharSequence... keySeqs)}
 * treats each vararg as a SEPARATE binding. So:
 * <pre>
 *   keymap.bind(ref, KeyMap.ctrl('X'), "a")  // WRONG: binds Ctrl+X AND "a" independently
 *   keymap.bind(ref, KeyMap.ctrl('X') + "a") // RIGHT: binds the chord Ctrl+X followed by a
 * </pre>
 */
class ChatReplKeyBindingTest {

    private KeyMap<Object> keyMap;

    @BeforeEach
    void setUp() {
        keyMap = new KeyMap<>();
        // Set default binding — JLine's EMACS keymap uses self-insert as default
        keyMap.setUnicode(new Reference(LineReader.SELF_INSERT));

        // Register the same chord bindings that ChatRepl uses (the FIXED version)
        keyMap.bind(new Reference("toggle-plan-mode"), KeyMap.ctrl('X') + "p");
        keyMap.bind(new Reference("toggle-plan-mode"), KeyMap.ctrl('X') + "P");
        keyMap.bind(new Reference("show-todos"), KeyMap.ctrl('X') + "t");
        keyMap.bind(new Reference("show-todos"), KeyMap.ctrl('X') + "T");
        keyMap.bind(new Reference("cycle-agent"), KeyMap.ctrl('X') + "a");
        keyMap.bind(new Reference("cycle-agent"), KeyMap.ctrl('X') + "A");
        keyMap.bind(new Reference("background-task"), KeyMap.ctrl('B'));
        keyMap.bind(new Reference("cancel-operation"), KeyMap.ctrl('G'));
    }

    @Test
    void bareLettersShouldNotTriggerWidgets() {
        // These letters were previously hijacked by the broken chord bindings.
        // getBound returns null for keys that fall through to the unicode default (self-insert),
        // which is correct — the key assertion is that they must NOT be bound to a widget.
        String[] problemLetters = {"a", "A", "t", "T", "p", "P"};
        String[] widgetNames = {"cycle-agent", "cycle-agent", "show-todos", "show-todos",
                "toggle-plan-mode", "toggle-plan-mode"};
        for (int i = 0; i < problemLetters.length; i++) {
            Object bound = keyMap.getBound(problemLetters[i]);
            if (bound instanceof Reference ref) {
                assertNotEquals(widgetNames[i], ref.name(),
                        "Bare '" + problemLetters[i] + "' must NOT trigger widget '" + widgetNames[i] + "'");
            }
            // null means the key falls through to the unicode default (self-insert) — correct
        }
    }

    @Test
    void noPrintableAsciiShouldTriggerWidget() {
        // Every printable character must type normally — none should trigger a custom widget
        Set<String> widgetNames = Set.of("toggle-plan-mode", "show-todos", "cycle-agent",
                "background-task", "cancel-operation");
        for (char c = ' '; c <= '~'; c++) {
            String key = String.valueOf(c);
            Object bound = keyMap.getBound(key);
            if (bound instanceof Reference ref) {
                assertFalse(widgetNames.contains(ref.name()),
                        "Printable char '" + c + "' (0x" + Integer.toHexString(c)
                                + ") should not trigger widget '" + ref.name() + "'");
            }
            // null means no explicit binding — falls through to unicode default, which is fine
        }
    }

    @Test
    void ctrlXChordsShouldBindToWidgets() {
        // The two-key chord Ctrl+X followed by a letter should resolve to the correct widget
        assertEquals("toggle-plan-mode",
                ((Reference) keyMap.getBound(KeyMap.ctrl('X') + "p")).name());
        assertEquals("toggle-plan-mode",
                ((Reference) keyMap.getBound(KeyMap.ctrl('X') + "P")).name());
        assertEquals("show-todos",
                ((Reference) keyMap.getBound(KeyMap.ctrl('X') + "t")).name());
        assertEquals("show-todos",
                ((Reference) keyMap.getBound(KeyMap.ctrl('X') + "T")).name());
        assertEquals("cycle-agent",
                ((Reference) keyMap.getBound(KeyMap.ctrl('X') + "a")).name());
        assertEquals("cycle-agent",
                ((Reference) keyMap.getBound(KeyMap.ctrl('X') + "A")).name());
    }

    @Test
    void ctrlBShouldBindToBackgroundTask() {
        Object bound = keyMap.getBound(KeyMap.ctrl('B'));
        assertInstanceOf(Reference.class, bound);
        assertEquals("background-task", ((Reference) bound).name());
    }

    @Test
    void ctrlGShouldBindToCancelOperation() {
        Object bound = keyMap.getBound(KeyMap.ctrl('G'));
        assertInstanceOf(Reference.class, bound);
        assertEquals("cancel-operation", ((Reference) bound).name());
    }

    @Test
    void rawEscapeShouldNotBebound() {
        // ESC (0x1B) must NOT be bound to cancel — it's the Meta prefix in EMACS mode
        Object bound = keyMap.getBound("\033");
        if (bound instanceof Reference ref) {
            assertNotEquals("cancel-operation", ref.name(),
                    "Raw ESC should not be bound to cancel-operation — it corrupts EMACS Meta prefix");
        }
    }

    @Test
    void brokenVarargBindingWouldHijackBareLetters() {
        // Demonstrate the bug: if you use varargs instead of concatenation,
        // bare letters get hijacked
        KeyMap<Object> brokenMap = new KeyMap<>();
        brokenMap.setUnicode(new Reference(LineReader.SELF_INSERT));

        // This is the BROKEN pattern — each vararg is a separate binding
        brokenMap.bind(new Reference("cycle-agent"), KeyMap.ctrl('X'), "a");

        // "a" is now independently bound to cycle-agent — this is the bug
        Object boundA = brokenMap.getBound("a");
        assertInstanceOf(Reference.class, boundA);
        assertEquals("cycle-agent", ((Reference) boundA).name(),
                "Varargs bind should (incorrectly) bind bare 'a' — proving the bug exists");

        // Ctrl+X is also independently bound
        Object boundCtrlX = brokenMap.getBound(KeyMap.ctrl('X'));
        assertInstanceOf(Reference.class, boundCtrlX);
        assertEquals("cycle-agent", ((Reference) boundCtrlX).name(),
                "Varargs bind should (incorrectly) bind bare Ctrl+X — proving the bug exists");
    }
}
