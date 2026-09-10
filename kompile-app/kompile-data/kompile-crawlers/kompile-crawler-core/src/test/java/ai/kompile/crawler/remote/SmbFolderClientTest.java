/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawler.remote;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmbFolderClientTest {

    @Test
    void quotesSpacesWithoutAllowingSmbCommandSeparatorsOrShellEscapes() throws Exception {
        assertEquals("\"reports/Quarter Four.xlsx\"",
                SmbFolderClient.quoteCommandArgument("reports/Quarter Four.xlsx"));
        assertThrows(IOException.class,
                () -> SmbFolderClient.quoteCommandArgument("reports;!rm"));
        assertThrows(IOException.class,
                () -> SmbFolderClient.quoteCommandArgument("reports\nquit"));
        assertThrows(IOException.class,
                () -> SmbFolderClient.quoteCommandArgument("reports\" quit"));
    }
}
