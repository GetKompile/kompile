/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.core.crawl.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceCredentialRedactorTest {

    @Test
    void removesUriUserInfoAndSensitiveUrlOrJdbcParameters() {
        assertEquals("https://files.example/reports?access_token=<redacted>&page=2",
                SourceCredentialRedactor.redact(
                        "https://user:pass@files.example/reports?access_token=secret&page=2"));
        assertEquals("jdbc:postgresql://db.example/data;password=<redacted>;ssl=true",
                SourceCredentialRedactor.redact(
                        "jdbc:postgresql://admin:secret@db.example/data;password=hunter2;ssl=true"));
        assertEquals("Connection rejected: token=<redacted>",
                SourceCredentialRedactor.redact("Connection rejected: token=plain-secret"));
    }
}
