package ai.kompile.cli.main.chat.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Session-local immutable payloads. 64 KiB independently compressed blocks support bounded seeks. */
final class SessionResultPayloadStore {
    static final int BLOCK_BYTES = 65536;
    private static final int RECORD_BYTES = 8 + 4 + 4 + 1 + 32;
    record Stored(String digest, long bytes, long storedBytes) { }

    interface Access extends AutoCloseable {
        long size() throws IOException;
        int read(ByteBuffer destination, long offset) throws IOException;
        void close() throws IOException;
    }

    static Access raw(Path file) throws IOException {
        safe(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing result output");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        return new Access() {
            public long size() throws IOException { return channel.size(); }
            public int read(ByteBuffer target, long offset) throws IOException { return channel.read(target, offset); }
            public void close() throws IOException { channel.close(); }
        };
    }

    static void safe(Path file) throws IOException {
        for (Path p = file.toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("Symlinked payload path: " + p);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static Stored publish(Path resultCache, Path output) throws IOException {
        return ai.kompile.cli.common.metrics.ToolJournalFiles.locked(resultCache.resolve("payload-publication"),
                () -> publishLocked(resultCache, output));
    }

    private static Stored publishLocked(Path resultCache, Path output) throws IOException {
        Path blobs = resultCache.resolve(".blobs"); safe(blobs); Files.createDirectories(blobs);
        Path pending = Files.createDirectory(blobs.resolve(UUID.randomUUID() + ".tmp"));
        String hash;
        long bytes = 0, encodedOffset = 0;
        MessageDigest full = digest();
        try {
            try (Access input = raw(output);
                 var encoded = Files.newOutputStream(pending.resolve("data.bin"), StandardOpenOption.CREATE_NEW);
                 var index = new DataOutputStream(Files.newOutputStream(pending.resolve("blocks.idx"), StandardOpenOption.CREATE_NEW))) {
                ByteBuffer buffer = ByteBuffer.allocate(BLOCK_BYTES);
                while (bytes < input.size()) {
                    buffer.clear();
                    while (buffer.hasRemaining()) {
                        int n = input.read(buffer, bytes + buffer.position());
                        if (n < 0) break;
                    }
                    if (buffer.position() == 0) throw new IOException("Incomplete payload input");
                    byte[] original = Arrays.copyOf(buffer.array(), buffer.position());
                    full.update(original);
                    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                    try (var gzip = new GZIPOutputStream(compressed)) { gzip.write(original); }
                    byte[] zipped = compressed.toByteArray();
                    boolean useGzip = zipped.length < original.length;
                    byte[] data = useGzip ? zipped : original;
                    index.writeLong(encodedOffset); index.writeInt(data.length); index.writeInt(original.length);
                    index.writeBoolean(useGzip); index.write(digest().digest(original));
                    encoded.write(data);
                    bytes += original.length; encodedOffset += data.length;
                }
            }
            hash = HexFormat.of().formatHex(full.digest());
            for (String name : new String[]{"data.bin", "blocks.idx"}) {
                try (var channel = FileChannel.open(pending.resolve(name), StandardOpenOption.WRITE)) { channel.force(true); }
            }
            Path target = blobs.resolve(hash); safe(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                // Deduplicate only a verified identical immutable payload; never trust a damaged blob.
                MessageDigest verified = digest();
                try (Access existing = packed(resultCache, hash, bytes)) {
                    ByteBuffer buffer = ByteBuffer.allocate(BLOCK_BYTES);
                    long offset = 0;
                    while (offset < bytes) {
                        buffer.clear(); int n = existing.read(buffer, offset);
                        if (n <= 0) throw new IOException("Incomplete existing payload");
                        verified.update(buffer.array(), 0, n); offset += n;
                    }
                }
                if (!hash.equals(HexFormat.of().formatHex(verified.digest()))) throw new IOException("Damaged deduplication payload");
                encodedOffset = Files.size(target.resolve("data.bin"));
            } else {
                try (var directory = FileChannel.open(pending, StandardOpenOption.READ)) { directory.force(true); }
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
                try (var directory = FileChannel.open(blobs, StandardOpenOption.READ)) { directory.force(true); }
            }
            return new Stored(hash, bytes, encodedOffset);
        } finally {
            // Remove only this publication's unpublished temporary files, never shared payloads.
            if (Files.isDirectory(pending, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(pending.resolve("data.bin")); Files.deleteIfExists(pending.resolve("blocks.idx"));
                Files.deleteIfExists(pending);
            }
        }
    }

    static Access packed(Path resultCache, String hash, long bytes) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{64}") || bytes < 0 || bytes > Integer.MAX_VALUE * 4L)
            throw new IOException("Invalid result payload reference");
        Path path = resultCache.resolve(".blobs").resolve(hash); safe(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing shared session payload");
        return new Packed(path, bytes);
    }

    private static final class Packed implements Access {
        private final FileChannel data, index;
        private final long bytes;
        private byte[] cached;
        private long cachedBlock = -1;
        Packed(Path path, long bytes) throws IOException {
            this.bytes = bytes;
            safe(path.resolve("data.bin")); safe(path.resolve("blocks.idx"));
            data = FileChannel.open(path.resolve("data.bin"), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            FileChannel opened = null;
            try {
                opened = FileChannel.open(path.resolve("blocks.idx"), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                long blocks = (bytes + BLOCK_BYTES - 1) / BLOCK_BYTES;
                if (opened.size() != blocks * RECORD_BYTES) throw new IOException("Incomplete block index");
                index = opened;
            } catch (IOException failure) { data.close(); if (opened != null) opened.close(); throw failure; }
        }
        public long size() { return bytes; }
        public int read(ByteBuffer destination, long offset) throws IOException {
            if (offset < 0) throw new IOException("Negative payload offset");
            if (offset >= bytes) return -1;
            if (!destination.hasRemaining()) return 0;
            long block = offset / BLOCK_BYTES;
            if (cachedBlock != block) load(block);
            int start = (int) (offset % BLOCK_BYTES);
            int n = Math.min(destination.remaining(), cached.length - start);
            destination.put(cached, start, n);
            return n;
        }
        private void load(long block) throws IOException {
            ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES);
            readFully(index, record, block * RECORD_BYTES); record.flip();
            long offset = record.getLong(); int stored = record.getInt(), original = record.getInt();
            byte encoding = record.get(); byte[] hash = new byte[32]; record.get(hash);
            long expected = Math.min(BLOCK_BYTES, bytes - block * BLOCK_BYTES);
            if (original != expected || stored <= 0 || stored > original || offset < 0
                    || offset > data.size() - stored || (encoding != 0 && encoding != 1))
                throw new IOException("Invalid compressed block index");
            ByteBuffer payload = ByteBuffer.allocate(stored); readFully(data, payload, offset);
            byte[] decoded;
            if (encoding == 0) {
                if (stored != original) throw new IOException("Invalid raw block size");
                decoded = payload.array();
            } else {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(payload.array()))) {
                    decoded = gzip.readNBytes(original + 1);
                    if (decoded.length != original || gzip.read() != -1) throw new IOException("Compressed block exceeds declared size");
                }
            }
            if (!MessageDigest.isEqual(hash, digest().digest(decoded))) throw new IOException("Damaged compressed payload block");
            cached = decoded; cachedBlock = block;
        }
        public void close() throws IOException {
            try { data.close(); } finally { index.close(); cached = null; }
        }
    }

    private static void readFully(FileChannel file, ByteBuffer bytes, long offset) throws IOException {
        while (bytes.hasRemaining()) {
            int n = file.read(bytes, offset + bytes.position());
            if (n < 0) throw new IOException("Truncated payload file");
        }
    }
}
