/* Copyright 2025 Kompile Inc. Licensed under Apache-2.0. */
package ai.kompile.modelmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegistryBasedModelManagerTest {

    @Test
    void jsonNullIsNotConvertedToLiteralNullChecksum() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertNull(RegistryBasedModelManager.nullableText(
                mapper.readTree("{\"checksum\":null}"), "checksum", null));
        assertNull(RegistryBasedModelManager.nullableText(
                mapper.readTree("{}"), "checksum", null));
        assertEquals("abc", RegistryBasedModelManager.nullableText(
                mapper.readTree("{\"checksum\":\"abc\"}"), "checksum", null));
    }
}
