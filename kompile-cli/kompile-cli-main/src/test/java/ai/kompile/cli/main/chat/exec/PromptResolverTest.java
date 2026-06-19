package ai.kompile.cli.main.chat.exec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptResolverTest {

    private static InputStream stdin(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void resolve_fromSingleArg() throws IOException {
        assertEquals("hello world", PromptResolver.resolve(List.of("hello world"), stdin("")));
    }

    @Test
    void resolve_joinsMultipleArgsWithSpaces() throws IOException {
        assertEquals("count the files",
                PromptResolver.resolve(List.of("count", "the", "files"), stdin("ignored")));
    }

    @Test
    void resolve_emptyArgs_readsAndTrimsStdin() throws IOException {
        assertEquals("from stdin", PromptResolver.resolve(List.of(), stdin("  from stdin\n")));
    }

    @Test
    void resolve_dashSentinel_readsStdin() throws IOException {
        assertEquals("piped prompt", PromptResolver.resolve(List.of("-"), stdin("piped prompt\n")));
    }

    @Test
    void resolve_nullArgs_readsStdin() throws IOException {
        assertEquals("x", PromptResolver.resolve(null, stdin("x")));
    }

    @Test
    void resolve_argsTakePrecedenceOverStdin() throws IOException {
        assertEquals("arg wins", PromptResolver.resolve(List.of("arg wins"), stdin("stdin loses")));
    }

    @Test
    void resolve_noArgsNoStdin_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptResolver.resolve(List.of(), stdin("   ")));
    }

    @Test
    void joinArgs_blankOrEmptyOrNull_returnsNull() {
        assertNull(PromptResolver.joinArgs(List.of("   ")));
        assertNull(PromptResolver.joinArgs(List.of()));
        assertNull(PromptResolver.joinArgs(null));
    }

    @Test
    void joinArgs_trimsAndJoins() {
        assertEquals("a b", PromptResolver.joinArgs(List.of("a", "b")));
    }
}
