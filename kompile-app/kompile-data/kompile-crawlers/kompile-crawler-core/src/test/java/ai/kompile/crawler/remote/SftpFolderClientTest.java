/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawler.remote;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SftpFolderClientTest {

    @Test
    void quotesBatchPathsAndRejectsCommandInjection() throws Exception {
        assertEquals("\"/reports/Quarter Four.xlsx\"",
                SftpFolderClient.quoteBatchPath("/reports/Quarter Four.xlsx"));
        assertThrows(IOException.class,
                () -> SftpFolderClient.quoteBatchPath("/reports\n!rm -rf /"));
        assertThrows(IOException.class,
                () -> SftpFolderClient.quoteBatchPath("/reports; quit"));
        assertThrows(IOException.class,
                () -> SftpFolderClient.quoteBatchPath("\"/reports\""));
    }
}
