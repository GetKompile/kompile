package ai.kompile.staging.diagnostics;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ImportDiagnosticSeverity {
    INFO("info"),
    ERROR("error");

    private final String value;

    ImportDiagnosticSeverity(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
