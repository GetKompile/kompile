/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.core.crawl.graph;

import java.util.regex.Pattern;

/** Redacts credentials from source URLs/JDBC strings before logs, status, or metadata persistence. */
public final class SourceCredentialRedactor {

    private static final Pattern URI_USER_INFO = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@");
    private static final Pattern SENSITIVE_PARAMETER = Pattern.compile(
            "(?i)((?:^|[?;&#\\s])(?:password|pwd|token|access[_-]?token|refresh[_-]?token|"
                    + "secret|secret[_-]?key|api[_-]?key|access[_-]?key|authorization)=)[^&#;\\s]*");

    private SourceCredentialRedactor() {
    }

    public static String redact(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String withoutUserInfo = URI_USER_INFO.matcher(raw).replaceAll("$1");
        return SENSITIVE_PARAMETER.matcher(withoutUserInfo).replaceAll("$1<redacted>");
    }
}
