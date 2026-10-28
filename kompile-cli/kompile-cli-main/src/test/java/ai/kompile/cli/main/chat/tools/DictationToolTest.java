package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DictationToolTest {
    @TempDir Path temporary;

    @Test void successfulListenDeletesRecordingAndOmitsDeadPath() throws Exception { listen(false, false); }
    @Test void failedTranscriptionDeletesRecording() throws Exception { listen(true, false); }
    @Test void recordingTimeoutStopsProcessAndDeletesRecording() throws Exception { listen(false, true); }

    private void listen(boolean failTranscription, boolean timeout) throws Exception {
        AtomicReference<Path> recording = new AtomicReference<>();
        Process recorder = process("", 0);
        when(recorder.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(!timeout);
        if (timeout) {
            when(recorder.isAlive()).thenReturn(true);
            when(recorder.descendants()).thenAnswer(invocation -> Stream.empty());
        }
        try (MockedConstruction<ProcessBuilder> ignored = mockConstruction(ProcessBuilder.class,
                (builder, construction) -> {
                    Object first = construction.arguments().get(0);
                    List<?> command = first instanceof List<?> ? (List<?>) first : List.of((String[]) first);
                    if ("arecord".equals(command.get(0))) {
                        Path path = Path.of(command.get(command.size() - 1).toString());
                        assertTrue(Files.exists(path));
                        Files.writeString(path, "recorded audio");
                        recording.set(path);
                        when(builder.start()).thenReturn(recorder);
                    } else if ("which".equals(command.get(0))) {
                        when(builder.redirectErrorStream(anyBoolean())).thenReturn(builder);
                        Process probe = process("arecord", 0);
                        when(builder.start()).thenReturn(probe);
                    } else {
                        assertNotNull(recording.get());
                        assertTrue(Files.exists(recording.get()), "transcriber still needs the recording");
                        Process transcriber = process("recognized words", failTranscription ? 1 : 0);
                        when(builder.start()).thenReturn(transcriber);
                    }
                })) {
            ToolResult result = invoke("recordAndTranscribe", new Class<?>[]{String.class, int.class, ToolContext.class},
                    "speech-tool", 1, null);
            assertEquals(failTranscription || timeout, result.isError(), result.getOutput());
            assertFalse(result.getMetadata().containsKey("audio_file"));
            assertNotNull(recording.get());
            assertFalse(Files.exists(recording.get()));
            if (timeout) verify(recorder).destroyForcibly();
        } finally {
            if (recording.get() != null) Files.deleteIfExists(recording.get());
        }
    }

    @Test void transcribePreservesCallerFile() throws Exception {
        Path source = temporary.resolve("caller recording.wav");
        Files.writeString(source, "caller-owned");
        Process transcriber = process("recognized", 0);
        try (MockedConstruction<ProcessBuilder> ignored = mockConstruction(ProcessBuilder.class,
                (builder, context) -> when(builder.start()).thenReturn(transcriber))) {
            ToolResult result = invoke("transcribeFile", new Class<?>[]{String.class, String.class, ToolContext.class},
                    "speech-tool", source.toString(), null);
            assertFalse(result.isError(), result.getOutput());
            assertEquals(source.toString(), result.getMetadata().get("audio_file"));
            assertEquals("caller-owned", Files.readString(source));
        }
    }

    private static Process process(String stdout, int exit) throws Exception {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8)));
        when(process.getErrorStream()).thenReturn(java.io.InputStream.nullInputStream());
        when(process.waitFor()).thenReturn(exit);
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(exit);
        return process;
    }

    private ToolResult invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = DictationTool.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return (ToolResult) method.invoke(new DictationTool(), args);
    }
}
