/* Copyright 2026 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.chat.sources.adapters.ClaudeCodeAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Exposes the native JSONL in a Kompile conversation without copying it, polling,
 * or persisting stream-json deltas a second time. Claude still owns retention and
 * resume; the independent Kompile text transcript remains the durable local record.
 */
public final class ClaudeTranscriptLink {
    private final Path conversations;
    private final String owner;
    private final ClaudeCodeAdapter adapter;
    private final LinkCreator linkCreator;
    private String published;

    @FunctionalInterface
    interface LinkCreator {
        void create(Path link, Path target) throws IOException;
    }
    private boolean warned;

    public ClaudeTranscriptLink(String owner) {
        this(KompileHome.homeDirectory().toPath().resolve("conversations"), owner,
                new ClaudeCodeAdapter());
    }

    ClaudeTranscriptLink(Path conversations, String owner, ClaudeCodeAdapter adapter) {
        this(conversations, owner, adapter, Files::createSymbolicLink);
    }

    ClaudeTranscriptLink(Path conversations, String owner, ClaudeCodeAdapter adapter, LinkCreator linkCreator) {
        this.conversations = conversations;
        this.owner = owner;
        this.adapter = adapter;
        this.linkCreator = linkCreator;
    }

    /** Retry at turn completion if Claude had not flushed its file at initialization. */
    public synchronized boolean publish(String nativeId) {
        if (!safeId(owner) || !safeId(nativeId)) return false;
        if (nativeId.equals(published)) {
            // A resume/reconstruction writer may have changed the last marker since
            // this link was published. Cache the file lookup, not the association.
            try {
                ChatHistory.recordNativeSessionId(owner, nativeId);
                return true;
            } catch (IOException | SecurityException failure) {
                warn("Could not record Claude session association: " + failure.getMessage());
                return false;
            }
        }
        var source = adapter.nativeTranscriptFile(nativeId);
        if (source.isEmpty()) return false;
        Path target = source.get();
        Path directory = conversations.resolve(owner).resolve("claude-code");
        Path link = directory.resolve(nativeId + ".jsonl");
        Path pointer = directory.resolve(nativeId + ".path");
        try {
            Files.createDirectories(directory);
            // The small pointer works even on Windows/filesystems that deny symlinks.
            if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
                Files.writeString(pointer, target + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            } else if (!Files.readString(pointer, StandardCharsets.UTF_8).strip().equals(target.toString())) {
                throw new IOException("existing transcript pointer refers to a different file: " + pointer);
            }
            if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    linkCreator.create(link, target);
                } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                    warn("Cannot create live Claude transcript link; read the source path in "
                            + pointer + " (" + unavailable.getMessage() + ")");
                }
            } else if (!Files.isSymbolicLink(link) || !Files.readSymbolicLink(link).equals(target)) {
                throw new IOException("refusing to replace existing transcript: " + link);
            }
            ChatHistory.recordNativeSessionId(owner, nativeId);
            published = nativeId;
            return true;
        } catch (IOException | SecurityException failure) {
            warn("Could not expose Claude transcript: " + failure.getMessage());
            return false;
        }
    }

    private void warn(String message) {
        if (!warned) {
            System.err.println("Warning: " + message);
            warned = true;
        }
    }

    private static boolean safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]+");
    }
}
