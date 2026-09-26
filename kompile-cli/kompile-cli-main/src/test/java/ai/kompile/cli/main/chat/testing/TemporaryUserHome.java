/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.testing;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Runs a test class with {@code user.home} set to a new temporary directory. Chat
 * loops, clients, ledgers and session configs keep their state (conversation
 * ledgers, credentials, settings) under {@code ~/.kompile}, so without this a test
 * writes into the developer's real home. JavaCPP's native-library cache stays in the
 * real home so native libraries are not extracted again into every temporary one.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(TemporaryUserHome.Callbacks.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public @interface TemporaryUserHome {

    /** Swaps {@code user.home} before the class's first test and restores it after its last. */
    final class Callbacks implements BeforeAllCallback, AfterAllCallback {
        private static final ExtensionContext.Namespace NAMESPACE =
                ExtensionContext.Namespace.create(TemporaryUserHome.class);
        private static final String JAVACPP_CACHE = "org.bytedeco.javacpp.cachedir";

        @Override
        public void beforeAll(ExtensionContext context) throws IOException {
            ExtensionContext.Store store = context.getStore(NAMESPACE);
            Path home = Files.createTempDirectory("kompile-test-home-");
            String original = System.getProperty("user.home");
            store.put("original", original);
            store.put("home", home);
            if (System.getProperty(JAVACPP_CACHE) == null && original != null) {
                System.setProperty(JAVACPP_CACHE,
                        Path.of(original, ".javacpp", "cache").toString());
                store.put("pinnedCache", Boolean.TRUE);
            }
            System.setProperty("user.home", home.toString());
        }

        @Override
        public void afterAll(ExtensionContext context) {
            ExtensionContext.Store store = context.getStore(NAMESPACE);
            String original = store.remove("original", String.class);
            if (original == null) System.clearProperty("user.home");
            else System.setProperty("user.home", original);
            if (store.remove("pinnedCache", Boolean.class) != null) {
                System.clearProperty(JAVACPP_CACHE);
            }
            deleteQuietly(store.remove("home", Path.class));
        }

        private static void deleteQuietly(Path root) {
            if (root == null) return;
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
            } catch (IOException | UncheckedIOException ignored) {
                // best-effort cleanup
            }
        }
    }
}
