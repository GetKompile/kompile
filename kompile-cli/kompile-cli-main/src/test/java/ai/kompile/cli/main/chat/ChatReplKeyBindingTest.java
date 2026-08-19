package ai.kompile.cli.main.chat;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

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
        keyMap.bind(new Reference("cancel-operation"), "\033");
        keyMap.bind(new Reference(ChatRepl.STANDARD_CHAT_DOWN_WIDGET), "\033[B", "\033OB");
        keyMap.bind(new Reference(ChatRepl.STANDARD_CHAT_PARENT_WIDGET), "\033[D", "\033OD");
        keyMap.bind(new Reference(ChatRepl.STANDARD_CHAT_PAGE_UP_WIDGET),
                "\033[5~", "\033[5;2~", "\033[1;2A");
        keyMap.bind(new Reference(ChatRepl.STANDARD_CHAT_PAGE_DOWN_WIDGET),
                "\033[6~", "\033[6;2~", "\033[1;2B");
        keyMap.bind(new Reference(ChatRepl.STANDARD_CHAT_SCROLL_TOP_WIDGET),
                "\033[1;5H", "\033[5H");
        keyMap.bind(new Reference(ChatRepl.STANDARD_CHAT_SCROLL_BOTTOM_WIDGET),
                "\033[1;5F", "\033[5F");
        keyMap.bind(new Reference(ChatRepl.STANDARD_CHAT_SCROLL_MOUSE_WIDGET), "\033[M");
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
                "background-task", "cancel-operation", ChatRepl.STANDARD_CHAT_DOWN_WIDGET,
                ChatRepl.STANDARD_CHAT_PARENT_WIDGET, ChatRepl.STANDARD_CHAT_PAGE_UP_WIDGET,
                ChatRepl.STANDARD_CHAT_PAGE_DOWN_WIDGET);
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
    void escapeShouldBindToCancelOperation() {
        Object bound = keyMap.getBound("\033");
        assertInstanceOf(Reference.class, bound);
        assertEquals("cancel-operation", ((Reference) bound).name());
    }

    @Test
    void cancelKeyIsBoundInEveryPossibleActiveKeymap() {
        Map<String, KeyMap<Binding>> keyMaps = new LinkedHashMap<>();
        keyMaps.put(LineReader.EMACS, new KeyMap<>());
        keyMaps.put(LineReader.VIINS, new KeyMap<>());
        keyMaps.put(LineReader.VICMD, new KeyMap<>());

        ChatRepl.bindCancelKey(keyMaps, "\033");

        for (KeyMap<Binding> map : keyMaps.values()) {
            Object bound = map.getBound("\033");
            assertInstanceOf(Reference.class, bound);
            assertEquals("cancel-operation", ((Reference) bound).name());
        }
    }

    @Test
    void downArrowEntersActivityWithoutInterceptingPrintableInput() {
        assertEquals(ChatRepl.STANDARD_CHAT_DOWN_WIDGET,
                ((Reference) keyMap.getBound("\033[B")).name());
        assertEquals(ChatRepl.STANDARD_CHAT_DOWN_WIDGET,
                ((Reference) keyMap.getBound("\033OB")).name());
        assertNull(keyMap.getBound("B"),
                "The printable letter B must remain a normal self-insert key");
    }

    @Test
    void leftArrowNavigatesToActivityParentWithoutInterceptingPrintableInput() {
        assertEquals(ChatRepl.STANDARD_CHAT_PARENT_WIDGET,
                ((Reference) keyMap.getBound("\033[D")).name());
        assertEquals(ChatRepl.STANDARD_CHAT_PARENT_WIDGET,
                ((Reference) keyMap.getBound("\033OD")).name());
        assertNull(keyMap.getBound("D"),
                "The printable letter D must remain a normal self-insert key");
    }

    @Test
    void pageAndShiftArrowKeysScrollTranscriptWithoutPrintableInterception() {
        assertEquals(ChatRepl.STANDARD_CHAT_PAGE_UP_WIDGET,
                ((Reference) keyMap.getBound("\033[5~")).name());
        assertEquals(ChatRepl.STANDARD_CHAT_PAGE_UP_WIDGET,
                ((Reference) keyMap.getBound("\033[1;2A")).name());
        assertEquals(ChatRepl.STANDARD_CHAT_PAGE_DOWN_WIDGET,
                ((Reference) keyMap.getBound("\033[6~")).name());
        assertEquals(ChatRepl.STANDARD_CHAT_PAGE_DOWN_WIDGET,
                ((Reference) keyMap.getBound("\033[1;2B")).name());
        assertNull(keyMap.getBound("5"));
        assertNull(keyMap.getBound("6"));
    }

    @Test
    void transcriptTopBottomAndMouseBindingsAreAvailable() {
        assertEquals(ChatRepl.STANDARD_CHAT_SCROLL_TOP_WIDGET,
                ((Reference) keyMap.getBound("\033[1;5H")).name());
        assertEquals(ChatRepl.STANDARD_CHAT_SCROLL_BOTTOM_WIDGET,
                ((Reference) keyMap.getBound("\033[1;5F")).name());
        assertEquals(ChatRepl.STANDARD_CHAT_SCROLL_MOUSE_WIDGET,
                ((Reference) keyMap.getBound("\033[M")).name());
    }

    @Test
    void contextualUpArrowHasOneActionPerPromptState() {
        assertEquals(ChatRepl.StandardUpAction.MOVE_WITHIN_DRAFT,
                ChatRepl.resolveStandardUpAction("first\nsecond", 12, true, false));
        assertEquals(ChatRepl.StandardUpAction.EDIT_LATEST_QUEUED,
                ChatRepl.resolveStandardUpAction("", 0, true, false));
        assertEquals(ChatRepl.StandardUpAction.PREVIOUS_HISTORY,
                ChatRepl.resolveStandardUpAction("", 0, false, false));
        assertEquals(ChatRepl.StandardUpAction.PREVIOUS_HISTORY,
                ChatRepl.resolveStandardUpAction("recalled", 8, false, true));
        assertEquals(ChatRepl.StandardUpAction.KEEP_DRAFT,
                ChatRepl.resolveStandardUpAction("unsent draft", 12, false, false));
    }

    @Test
    void firstLineOfMultilineDraftDoesNotFallIntoHistory() {
        assertEquals(ChatRepl.StandardUpAction.KEEP_DRAFT,
                ChatRepl.resolveStandardUpAction("first\nsecond", 3, false, false));
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
