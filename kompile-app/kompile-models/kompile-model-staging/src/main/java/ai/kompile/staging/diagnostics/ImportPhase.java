package ai.kompile.staging.diagnostics;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable phases for a model import attempt. Values are API contracts.
 */
public enum ImportPhase {
    PARSE("parse"),
    RESOLVE("resolve"),
    DISCOVER("discover"),
    SELECT("select"),
    DOWNLOAD("download"),
    VALIDATE("validate"),
    COMPILE("compile"),
    CACHE("cache");

    private final String value;

    ImportPhase(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
