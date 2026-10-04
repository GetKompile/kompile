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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Reads the lines of an append-only log newest first, so a report over a recent window stops
 * as soon as it reaches older records instead of parsing the whole file. Reads at most a byte
 * budget from the end of the file; bytes appended after the read starts are not seen.
 */
public final class TailLines {

    static final int CHUNK = 1 << 20;

    /** Receives one line (without its line ending); returns false to stop reading. */
    @FunctionalInterface
    public interface Visitor {
        boolean line(byte[] buffer, int offset, int length) throws IOException;
    }

    private TailLines() {
    }

    /**
     * Visits the non-blank lines of {@code file}, last line first.
     *
     * @param maxBytes bytes to read from the end of the file; 0 or less reads it all
     * @return true when the byte budget ran out before the start of the file, so older lines
     *         were never visited; false when the file was read to its start or the visitor stopped
     */
    public static boolean newestFirst(Path file, long maxBytes, Visitor visitor) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long floor = maxBytes > 0 && size > maxBytes ? size - maxBytes : 0;
            long position = size;
            // The start of the oldest line seen so far, whose beginning may lie in an earlier chunk.
            byte[] partial = new byte[0];
            while (position > floor) {
                int length = (int) Math.min(CHUNK, position - floor);
                long chunkStart = position - length;
                byte[] buffer = new byte[length + partial.length];
                readFully(channel, buffer, length, chunkStart);
                System.arraycopy(partial, 0, buffer, length, partial.length);
                int end = buffer.length;
                for (int i = buffer.length - 1; i >= 0; i--) {
                    if (buffer[i] == '\n') {
                        if (!visit(buffer, i + 1, end, visitor)) {
                            return false;
                        }
                        end = i;
                    }
                }
                partial = Arrays.copyOf(buffer, end);
                position = chunkStart;
            }
            boolean budgetSpent = floor > 0;
            // The first line read is whole only if the file starts there or a line ends just before it.
            if (partial.length > 0 && (!budgetSpent || byteAt(channel, floor - 1) == '\n')
                    && !visit(partial, 0, partial.length, visitor)) {
                return false;
            }
            return budgetSpent;
        }
    }

    private static boolean visit(byte[] buffer, int from, int to, Visitor visitor) throws IOException {
        if (to > from && buffer[to - 1] == '\r') {
            to--;
        }
        for (int i = from; i < to; i++) {
            if (buffer[i] != ' ' && buffer[i] != '\t') {
                return visitor.line(buffer, from, to - from);
            }
        }
        return true;
    }

    private static void readFully(FileChannel channel, byte[] buffer, int length, long position) throws IOException {
        ByteBuffer target = ByteBuffer.wrap(buffer, 0, length);
        while (target.hasRemaining()) {
            if (channel.read(target, position + target.position()) < 0) {
                throw new EOFException("File shrank while reading at " + (position + target.position()));
            }
        }
    }

    private static byte byteAt(FileChannel channel, long position) throws IOException {
        byte[] one = new byte[1];
        readFully(channel, one, 1, position);
        return one[0];
    }
}
