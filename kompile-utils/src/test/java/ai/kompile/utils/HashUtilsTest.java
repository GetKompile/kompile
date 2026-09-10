/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class HashUtilsTest {
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path temp;

    @Test
    void hashesBytesStringsAndFilesWithTheSameCanonicalEncoding() throws Exception {
        byte[] value = "abc".getBytes(StandardCharsets.UTF_8);
        Path file = temp.resolve("value.bin");
        Files.write(file, value);

        assertEquals(ABC_SHA256, HashUtils.sha256Hex(value));
        assertEquals(ABC_SHA256, HashUtils.sha256Hex("abc"));
        assertEquals(ABC_SHA256, HashUtils.sha256Hex(file));
    }

    @Test
    void incrementalDigestsAreIndependentAndUseCanonicalHex() {
        var first = HashUtils.newSha256Digest();
        var second = HashUtils.newSha256Digest();
        assertNotSame(first, second);

        first.update("a".getBytes(StandardCharsets.UTF_8));
        first.update("bc".getBytes(StandardCharsets.UTF_8));
        assertEquals(ABC_SHA256, HashUtils.toHex(first.digest()));

        second.update("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(ABC_SHA256, HashUtils.toHex(second.digest()));
    }
}
