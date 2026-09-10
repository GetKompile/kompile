/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.core.loaders;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentSourceDescriptorTest {

    @Test
    void accountWideAndMetadataIdentifiedTypesAreLocatorOptional() {
        Set.of("GMAIL", "GOOGLE_WORKSPACE", "EMAIL", "IMAP", "POP3", "GDOCS",
                "DISCORD", "DISCORD_HISTORY", "SLACK_HISTORY")
                .forEach(type -> assertTrue(DocumentSourceDescriptor.locatorOptional(type),
                        type + " must be locator-optional"));
    }

    @Test
    void stringFormNormalizesCaseHyphensAndWhitespace() {
        assertTrue(DocumentSourceDescriptor.locatorOptional("gmail"));
        assertTrue(DocumentSourceDescriptor.locatorOptional(" google-workspace "));
        assertTrue(DocumentSourceDescriptor.locatorOptional("discord-history"));
        assertFalse(DocumentSourceDescriptor.locatorOptional("FILE"));
        assertFalse(DocumentSourceDescriptor.locatorOptional("GDRIVE"));
        assertFalse(DocumentSourceDescriptor.locatorOptional("unknown-type"));
        assertFalse(DocumentSourceDescriptor.locatorOptional((String) null));
    }

    @Test
    void locatorCentricTypesStayRequired() {
        Set.of("FILE", "DIRECTORY", "URL", "WEB_CRAWL", "SLACK", "CONFLUENCE", "JIRA",
                "REDDIT", "NOTION", "OBSIDIAN", "GDRIVE", "ONEDRIVE", "S3", "SFTP", "SQL", "SMB")
                .forEach(type -> assertFalse(DocumentSourceDescriptor.locatorOptional(type),
                        type + " must remain locator-required"));
    }

    @Test
    void enumAndStringFormsAgree() {
        for (DocumentSourceDescriptor.SourceType type : DocumentSourceDescriptor.SourceType.values()) {
            assertEquals(DocumentSourceDescriptor.locatorOptional(type),
                    DocumentSourceDescriptor.locatorOptional(type.name()));
        }
    }
}
