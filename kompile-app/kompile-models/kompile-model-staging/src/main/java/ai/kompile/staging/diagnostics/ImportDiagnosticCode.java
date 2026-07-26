package ai.kompile.staging.diagnostics;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable machine-readable import diagnostic codes.
 */
public enum ImportDiagnosticCode {
    IMPORT_STARTED("import.started"),
    SOURCE_RESOLVED("source.resolved"),
    DISCOVERY_STARTED("discovery.started"),
    DISCOVERY_COMPLETE("discovery.complete"),
    MODEL_NOT_FOUND("model.not_found"),
    MODEL_SELECTION_REQUIRED("model.selection_required"),
    ASSET_BUNDLE_INCOMPLETE("asset.bundle_incomplete"),
    ASSETS_SELECTED("assets.selected"),
    DOWNLOAD_STARTED("download.started"),
    DOWNLOAD_COMPLETE("download.complete"),
    DOWNLOAD_FAILED("download.failed"),
    VALIDATION_STARTED("validation.started"),
    VALIDATION_COMPLETE("validation.complete"),
    VALIDATION_FAILED("validation.failed"),
    COMPILE_STARTED("compile.started"),
    COMPILE_COMPLETE("compile.complete"),
    COMPILE_FAILED("compile.failed"),
    CACHE_COMPLETE("cache.complete"),
    IMPORT_COMPLETE("import.complete"),
    IMPORT_CANCELLED("import.cancelled"),
    IMPORT_FAILED("import.failed");

    private final String value;

    ImportDiagnosticCode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
