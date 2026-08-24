/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.skill;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Cross-process lock for managed instruction-file read/modify/write operations. */
public final class ManagedFileLock {

    private ManagedFileLock() { }

    @FunctionalInterface
    public interface Operation<T> {
        T run() throws IOException;
    }

    public static <T> T withLock(Path target, Operation<T> operation) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null || SkillPathPolicy.hasSymlinkComponent(parent)) {
            throw new IOException("Unsafe managed-file parent: " + target);
        }
        Files.createDirectories(parent);
        Path lockFile = parent.resolve("." + target.getFileName() + ".kompile.lock");
        if (Files.isSymbolicLink(lockFile)) {
            throw new IOException("Managed-file lock must not be a symbolic link: " + lockFile);
        }
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
             var ignored = channel.lock()) {
            return operation.run();
        }
    }
}
