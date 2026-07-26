package ai.kompile.staging.diagnostics;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory, server-owned import history. It deliberately stores no stack traces,
 * credentials, URL query strings, or unbounded downloader output.
 */
@Component
public class ImportDiagnosticJournal {

    public static final int DEFAULT_CAPACITY = 200;
    private static final int MAX_DETAILS = 32;
    private static final int MAX_TEXT = 768;
    private static final Pattern URL_PATTERN =
            Pattern.compile("(?i)https?://[^\\s<>\\\"']+");
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern HF_TOKEN_PATTERN =
            Pattern.compile("(?i)hf_[A-Za-z0-9_-]{6,}");
    private static final Pattern AUTHORIZATION_PATTERN =
            Pattern.compile("(?i)(authorization\\s*[=:]\\s*)(?:bearer\\s+)?[^\\s,;]+");
    private static final Pattern NAMED_SECRET_PATTERN =
            Pattern.compile("(?i)((?:auth|token|password|secret|credential|api[_-]?key)\\s*[=:]\\s*)[^\\s,;]+");

    private final int capacity;
    private final Deque<ImportDiagnosticEvent> events = new ArrayDeque<>();

    public ImportDiagnosticJournal() {
        this(DEFAULT_CAPACITY);
    }

    public ImportDiagnosticJournal(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Diagnostic journal capacity must be positive");
        }
        this.capacity = capacity;
    }

    public String start(String modelId, String source, Map<String, ?> details) {
        String attemptId = UUID.randomUUID().toString();
        record(
                attemptId,
                modelId,
                source,
                ImportPhase.PARSE,
                ImportDiagnosticCode.IMPORT_STARTED,
                ImportDiagnosticSeverity.INFO,
                "Import request accepted for processing.",
                "",
                details);
        return attemptId;
    }

    public ImportDiagnosticEvent record(
            String attemptId,
            String modelId,
            String source,
            ImportPhase phase,
            ImportDiagnosticCode code,
            ImportDiagnosticSeverity severity,
            String summary,
            String remediation,
            Map<String, ?> details) {
        ImportDiagnosticEvent event = new ImportDiagnosticEvent(
                safeIdentifier(attemptId),
                Instant.now(),
                Objects.requireNonNull(phase, "phase"),
                Objects.requireNonNull(code, "code"),
                Objects.requireNonNull(severity, "severity"),
                safeText(summary),
                safeText(remediation),
                safeIdentifier(modelId),
                safeText(source),
                safeDetails(details));
        synchronized (events) {
            events.addLast(event);
            while (events.size() > capacity) {
                events.removeFirst();
            }
        }
        return event;
    }

    public ImportDiagnosticEvent failure(
            String attemptId,
            String modelId,
            String source,
            ImportPhase phase,
            Throwable failure) {
        Throwable actual = failure == null ? new IllegalStateException("Unknown import failure") : failure;
        ImportDiagnosticCode code = failureCode(phase, actual.getMessage());
        String message = safeText(actual.getMessage());
        if (message.isBlank()) {
            message = "The import failed during " + phase.value() + ".";
        }
        return record(
                attemptId,
                modelId,
                source,
                phase,
                code,
                ImportDiagnosticSeverity.ERROR,
                message,
                remediationFor(code),
                Map.of(
                        "failureType", actual.getClass().getSimpleName(),
                        "failureMessage", message));
    }

    public List<ImportDiagnosticEvent> recent(int requestedLimit) {
        int limit = requestedLimit <= 0 ? 50 : Math.min(requestedLimit, capacity);
        List<ImportDiagnosticEvent> result = new ArrayList<>(limit);
        synchronized (events) {
            Iterator<ImportDiagnosticEvent> iterator = events.descendingIterator();
            while (iterator.hasNext() && result.size() < limit) {
                result.add(iterator.next());
            }
        }
        return List.copyOf(result);
    }

    public List<ImportDiagnosticEvent> attempt(String attemptId) {
        String safeAttemptId = safeIdentifier(attemptId);
        List<ImportDiagnosticEvent> result = new ArrayList<>();
        synchronized (events) {
            for (ImportDiagnosticEvent event : events) {
                if (event.attemptId().equals(safeAttemptId)) {
                    result.add(event);
                }
            }
        }
        return List.copyOf(result);
    }

    public int capacity() {
        return capacity;
    }

    static ImportDiagnosticCode failureCode(ImportPhase phase, String rawMessage) {
        String message = Objects.requireNonNullElse(rawMessage, "").toLowerCase(Locale.ROOT);
        if (message.contains("multiple")
                || message.contains("more than one")
                || message.contains("select one")
                || message.contains("selection required")) {
            return ImportDiagnosticCode.MODEL_SELECTION_REQUIRED;
        }
        if (message.contains("tokenizer")
                || message.contains("chat_template")
                || message.contains("chat template")
                || message.contains("model config")
                || message.contains("config.json")
                || message.contains("text-generation")) {
            return ImportDiagnosticCode.ASSET_BUNDLE_INCOMPLETE;
        }
        if (message.contains("model")
                && (message.contains("not found")
                    || message.contains("missing")
                    || message.contains("no gguf")
                    || message.contains("no ggml"))) {
            return ImportDiagnosticCode.MODEL_NOT_FOUND;
        }
        if (phase == ImportPhase.COMPILE) {
            return ImportDiagnosticCode.COMPILE_FAILED;
        }
        if (phase == ImportPhase.VALIDATE) {
            return ImportDiagnosticCode.VALIDATION_FAILED;
        }
        if (phase == ImportPhase.DOWNLOAD
                || phase == ImportPhase.DISCOVER
                || phase == ImportPhase.SELECT) {
            return ImportDiagnosticCode.DOWNLOAD_FAILED;
        }
        return ImportDiagnosticCode.IMPORT_FAILED;
    }

    private static String remediationFor(ImportDiagnosticCode code) {
        return switch (code) {
            case MODEL_NOT_FOUND ->
                    "Verify the repository and revision, or select an existing GGUF/GGML model file.";
            case MODEL_SELECTION_REQUIRED ->
                    "Select exactly one discovered GGUF/GGML candidate before staging.";
            case ASSET_BUNDLE_INCOMPLETE ->
                    "Include tokenizer.json, tokenizer_config.json or a chat template, and config.json or text-generation.json.";
            case DOWNLOAD_FAILED ->
                    "Check network access and the sanitized asset path, then retry the import.";
            case VALIDATION_FAILED ->
                    "Verify that the staged artifact and tokenizer/config files match the selected model.";
            case COMPILE_FAILED ->
                    "Review the target profile and quantization choice, then retry target compilation.";
            default ->
                    "Review the earlier events in this attempt and retry after correcting the reported input.";
        };
    }

    private static Map<String, String> safeDetails(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : details.entrySet()) {
            if (safe.size() >= MAX_DETAILS) {
                break;
            }
            String key = safeIdentifier(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (normalizedKey.contains("token")
                    || normalizedKey.contains("secret")
                    || normalizedKey.contains("password")
                    || normalizedKey.contains("credential")
                    || normalizedKey.contains("authorization")
                    || normalizedKey.contains("apikey")
                    || normalizedKey.contains("api_key")) {
                safe.put(key, "[redacted]");
            } else {
                safe.put(key, safeText(String.valueOf(entry.getValue())));
            }
        }
        return Map.copyOf(safe);
    }

    private static String safeIdentifier(String value) {
        if (value == null) {
            return "";
        }
        String bounded = value.trim();
        if (bounded.length() > 160) {
            bounded = bounded.substring(0, 160);
        }
        return bounded.replaceAll("[^A-Za-z0-9._:@/-]", "_");
    }

    static String safeText(String value) {
        if (value == null) {
            return "";
        }
        String bounded = value.replace('\r', ' ').replace('\n', ' ').trim();

        // Strip URL user-info/query/fragment before inserting any redaction markers;
        // otherwise marker punctuation can be mistaken for part of the URL.
        Matcher matcher = URL_PATTERN.matcher(bounded);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(
                    sanitized,
                    Matcher.quoteReplacement(safeUrl(matcher.group())));
        }
        matcher.appendTail(sanitized);
        bounded = sanitized.toString();

        bounded = AUTHORIZATION_PATTERN.matcher(bounded).replaceAll("$1[redacted]");
        bounded = BEARER_PATTERN.matcher(bounded).replaceAll("$1[redacted]");
        bounded = HF_TOKEN_PATTERN.matcher(bounded).replaceAll("hf_[redacted]");
        bounded = NAMED_SECRET_PATTERN.matcher(bounded).replaceAll("$1[redacted]");

        if (bounded.length() > MAX_TEXT) {
            bounded = bounded.substring(0, MAX_TEXT) + "…";
        }
        return bounded;
    }

    private static String safeUrl(String raw) {
        String candidate = raw;
        String suffix = "";
        while (!candidate.isEmpty()
                && ".,);]".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
            suffix = candidate.charAt(candidate.length() - 1) + suffix;
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        try {
            URI uri = URI.create(candidate);
            if (uri.getHost() == null) {
                return "[redacted-url]" + suffix;
            }
            URI sanitized = new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    sanitizeEncodedPath(uri.getRawPath()),
                    null,
                    null);
            return sanitized + suffix;
        } catch (IllegalArgumentException | URISyntaxException invalid) {
            return "[redacted-url]" + suffix;
        }
    }

    private static String sanitizeEncodedPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank() || !rawPath.contains("%")) {
            return rawPath;
        }
        String[] segments = rawPath.split("/", -1);
        StringBuilder safe = new StringBuilder(rawPath.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                safe.append('/');
            }
            String segment = segments[i];
            safe.append(segment.matches("(?i).*%[0-9a-f]{2}.*")
                    ? "_encoded_"
                    : segment);
        }
        return safe.toString();
    }
}
