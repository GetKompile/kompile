/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.insights;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Folds an append-only log into a running tally, reading only the bytes appended since the
 * previous {@link #read()}. The live session panel refreshes after every tool call, and a
 * session's tool-call log runs to megabytes, so it cannot re-read the file each time.
 *
 * <p>The first read takes at most {@code maxBytes} from the end of the file, as {@link TailLines}
 * does, and {@link #partial()} then tells the caller that older lines were never counted. A file
 * that shrank or was replaced (another file key) starts a new tally. A line the writer has not
 * finished waits for its newline. A line longer than {@link #MAX_LINE}, and one the parser
 * rejects, is skipped, as the reports skip malformed lines.</p>
 *
 * <p>Not thread-safe: the owner calls it under its own lock.</p>
 */
final class LogFollower<T> {

    static final int CHUNK = 1 << 16;
    static final int MAX_LINE = 1 << 20;

    /** Folds one non-blank line, without its line ending, into the tally. */
    @FunctionalInterface
    interface LineParser<T> {
        void line(T tally, byte[] buffer, int offset, int length) throws IOException;
    }

    private final Path file;
    private final long maxBytes;
    private final Supplier<T> fresh;
    private final LineParser<T> parser;

    private T tally;
    private boolean started;
    private Object fileKey;
    /** Bytes consumed so far: folded into the tally, held in {@link #pending}, or skipped. */
    private long offset;
    /** The start of a line whose newline has not been read yet. */
    private byte[] pending = new byte[0];
    private int pendingLength;
    /** True while dropping bytes up to the next newline: a line read from its middle, or one too long. */
    private boolean skipping;
    private boolean partial;

    /**
     * @param maxBytes bytes the first read takes from the end of the file; 0 or less reads it all
     * @param fresh    an empty tally, for the first read and whenever the file starts over
     */
    LogFollower(Path file, long maxBytes, Supplier<T> fresh, LineParser<T> parser) {
        this.file = Objects.requireNonNull(file, "file");
        this.maxBytes = maxBytes;
        this.fresh = Objects.requireNonNull(fresh, "fresh");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.tally = fresh.get();
    }

    Path file() {
        return file;
    }

    /** True when the tally starts past the start of the file: the first read hit the byte budget. */
    boolean partial() {
        return partial;
    }

    /** The tally, with every complete line appended since the previous call folded in. */
    T read() throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            if (started) {
                restart();
            }
            return tally;
        }
        long size = attributes.size();
        Object key = attributes.fileKey();
        if (started && (size < offset || !Objects.equals(key, fileKey))) {
            restart();
        }
        if (!started) {
            started = true;
            fileKey = key;
            if (maxBytes > 0 && size > maxBytes) {
                // One byte early: when that byte ends a line, the line after it is whole and kept.
                offset = size - maxBytes - 1;
                skipping = true;
                partial = true;
            }
        }
        if (size > offset) {
            readTo(size);
        }
        return tally;
    }

    private void restart() {
        tally = fresh.get();
        started = false;
        fileKey = null;
        offset = 0;
        pendingLength = 0;
        skipping = false;
        partial = false;
    }

    private void readTo(long size) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(CHUNK, size - offset));
            while (offset < size) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), size - offset));
                int read = channel.read(buffer, offset);
                if (read <= 0) {
                    // The file shrank after its size was read; the next read starts over.
                    return;
                }
                fold(buffer.array(), read);
                offset += read;
            }
        } catch (NoSuchFileException e) {
            // Deleted after its size was read; the next read starts over.
        }
    }

    private void fold(byte[] bytes, int length) {
        int start = 0;
        for (int i = 0; i < length; i++) {
            if (bytes[i] != '\n') {
                continue;
            }
            if (skipping) {
                skipping = false;
            } else if (pendingLength == 0) {
                line(bytes, start, i);
            } else if (pendingLength + (i - start) <= MAX_LINE) {
                append(bytes, start, i);
                line(pending, 0, pendingLength);
            }
            pendingLength = 0;
            start = i + 1;
        }
        if (start < length && !skipping) {
            if (pendingLength + (length - start) > MAX_LINE) {
                pendingLength = 0;
                skipping = true;
            } else {
                append(bytes, start, length);
            }
        }
    }

    private void append(byte[] bytes, int from, int to) {
        int needed = pendingLength + (to - from);
        if (needed > pending.length) {
            pending = Arrays.copyOf(pending, Math.max(needed, Math.min(MAX_LINE, pending.length * 2)));
        }
        System.arraycopy(bytes, from, pending, pendingLength, to - from);
        pendingLength = needed;
    }

    private void line(byte[] bytes, int from, int to) {
        if (to > from && bytes[to - 1] == '\r') {
            to--;
        }
        for (int i = from; i < to; i++) {
            if (bytes[i] != ' ' && bytes[i] != '\t') {
                try {
                    parser.line(tally, bytes, from, to - from);
                } catch (IOException | RuntimeException e) {
                    // A malformed line is skipped, as the reports skip it.
                }
                return;
            }
        }
    }
}
