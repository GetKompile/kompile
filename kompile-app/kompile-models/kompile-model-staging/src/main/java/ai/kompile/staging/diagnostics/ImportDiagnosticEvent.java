package ai.kompile.staging.diagnostics;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One sanitized, bounded event in a server-owned import attempt journal.
 */
public record ImportDiagnosticEvent(
        String attemptId,
        Instant timestamp,
        ImportPhase phase,
        ImportDiagnosticCode code,
        ImportDiagnosticSeverity severity,
        String summary,
        String remediation,
        String modelId,
        String source,
        Map<String, String> details) {

    public ImportDiagnosticEvent {
        attemptId = Objects.requireNonNull(attemptId, "attemptId");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        phase = Objects.requireNonNull(phase, "phase");
        code = Objects.requireNonNull(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        summary = Objects.requireNonNullElse(summary, "");
        remediation = Objects.requireNonNullElse(remediation, "");
        modelId = Objects.requireNonNullElse(modelId, "");
        source = Objects.requireNonNullElse(source, "");
        details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
