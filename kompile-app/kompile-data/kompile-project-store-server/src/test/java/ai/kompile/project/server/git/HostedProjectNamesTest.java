package ai.kompile.project.server.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HostedProjectNamesTest {

    @Test
    void acceptsPortableIdentifiersAndRefs() {
        for (String value : new String[]{"a", "Alice-2", "team_name", "project.v1",
                "0123456789", "a" + "x".repeat(63)}) {
            assertTrue(HostedProjectNames.isValidIdentifier(value), value);
        }
        for (String value : new String[]{"main", "release-1.2", "deadbeef", "tag_name"}) {
            assertTrue(HostedProjectNames.isValidRef(value), value);
        }
    }

    @Test
    void rejectsTraversalSeparatorsDotSegmentsUnicodeControlsAndBounds() {
        for (String value : new String[]{null, "", ".", "..", ".hidden", "a..b", "../x",
                "/absolute", "C:\\absolute", "a/b", "a\\b", "é", "name\nInjected",
                "a".repeat(65)}) {
            assertFalse(HostedProjectNames.isValidIdentifier(value), String.valueOf(value));
        }
        for (String value : new String[]{null, "", ".", "..", ".hidden", "a..b", "refs/heads/main",
                "a\\b", "é", "main\r\nX-Test: injected", "topic.lock", "a".repeat(129)}) {
            assertFalse(HostedProjectNames.isValidRef(value), String.valueOf(value));
        }
    }
}
