package ai.kompile.pipeline.serving.launcher;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineSubprocessLauncherTest {
    @Test
    void exposesOnlyManagedRuntimeExecution() {
        Set<String> publicMethods = Arrays.stream(PipelineSubprocessLauncher.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .collect(Collectors.toSet());

        assertTrue(publicMethods.contains("launch"));
        assertTrue(publicMethods.contains("applyPlacement"));
        assertEquals(Set.of("launch", "applyPlacement"), publicMethods);
    }
}
