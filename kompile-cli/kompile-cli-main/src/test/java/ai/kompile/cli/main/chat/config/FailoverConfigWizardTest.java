package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.SessionModelLadder.ModelRung;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FailoverConfigWizardTest {
    private static final ModelRung FIRST = new ModelRung("openai", "fixture-a", "work", "Work account", "high");
    private static final ModelRung SECOND = new ModelRung("openai", "fixture-a", "personal", null, "low", "oauth");

    @Test void addsSameVendorDifferentAccountAndThinkingWithoutChangingSource() {
        var source = new ArrayList<>(List.of(FIRST));
        var current = new ChatConfig("openai", "transient-secret", "primary", null);
        var pages = new ArrayList<String>();
        var result = FailoverConfigWizard.edit(reader("1", "5"), true, source, current,
                (t, lines) -> pages.addAll(lines), (r, route, page) -> {
                    assertNotSame(current, route);
                    route.setModel("must-not-leak");
                    return SECOND;
                });
        assertNotNull(result);
        assertEquals(List.of(FIRST, SECOND), result.rungs());
        assertEquals(List.of(FIRST), source);
        assertEquals("primary", current.getModel());
        assertTrue(pages.stream().anyMatch(line -> line.contains("credential: personal") && line.contains("thinking: low")));
        assertTrue(pages.stream().anyMatch(line -> line.contains("auth: oauth")));
        assertFalse(String.join("\n", pages).contains("transient-secret"));
        assertThrows(UnsupportedOperationException.class, () -> result.rungs().clear());
    }

    @Test void removesReordersAndDisablesOnlyInReturnedDraft() {
        var third = new ModelRung("custom", "fixture-c", "backup", null, null);
        var original = List.of(FIRST, SECOND, third);
        var draft = FailoverConfigWizard.edit(reader("3", "3", "1", "2", "2", "4", "5"), true,
                original, null, (t, lines) -> {}, (r, c, p) -> fail("No picker expected"));
        assertNotNull(draft);
        assertFalse(draft.enabled());
        assertEquals(List.of(third, SECOND), draft.rungs());
        assertEquals(3, original.size());
    }

    @Test void cancelEofAndInterruptedSelectionDiscardDraftChanges() {
        for (String[] answers : List.of(new String[]{"4", "0"}, new String[]{"4"},
                new String[]{"2", "cancel"}, new String[]{"1"})) {
            var original = new ArrayList<>(List.of(FIRST));
            var result = FailoverConfigWizard.edit(reader(answers), true, original, null, (t, l) -> {},
                    (r, c, p) -> { throw new org.jline.reader.UserInterruptException(""); });
            assertNull(result);
            assertEquals(List.of(FIRST), original);
        }
    }

    @Test void invalidIndicesAndDuplicateOrCancelledSelectionDoNotChangeDraft() {
        var notices = new ArrayList<String>();
        var result = FailoverConfigWizard.edit(reader("2", "0", "3", "1", "9", "2", "abc", "1", "1", "5"),
                true, List.of(FIRST), null, (t, lines) -> notices.addAll(lines), new FailoverConfigWizard.RoutePicker() {
                    private int calls;
                    @Override public ModelRung pick(LineReader r, ChatConfig c, java.util.function.BiConsumer<String, List<String>> p) {
                        return calls++ == 0 ? new ModelRung("OPENAI", FIRST.model(), FIRST.credentialName(), "Different label", "low") : null;
                    }
                });
        assertNotNull(result);
        assertEquals(List.of(FIRST), result.rungs());
        assertTrue(notices.stream().anyMatch(line -> line.contains("out of range")));
        assertTrue(notices.stream().anyMatch(line -> line.contains("already configured")));
        assertTrue(notices.stream().anyMatch(line -> line.contains("selection cancelled")));
    }

    private static LineReader reader(String... answers) {
        var input = new ArrayDeque<>(List.of(answers));
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(), new Class<?>[]{LineReader.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("readLine")) {
                        if (input.isEmpty()) throw new EndOfFileException();
                        return input.removeFirst();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
