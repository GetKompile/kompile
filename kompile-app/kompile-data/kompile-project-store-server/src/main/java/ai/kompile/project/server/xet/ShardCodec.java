package ai.kompile.project.server.xet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decoder for the Xet <em>MDB (Merkle Database) shard</em> binary format, version 2.
 *
 * <p>A shard is the wire format a client uploads via {@code POST /v1/shards} (and that the global
 * dedupe API returns). It carries two things we care about server-side:
 * <ul>
 *   <li><b>File Info</b> — for each file, its hash plus the ordered list of reconstruction
 *       <em>terms</em> {@code (xorbHash, [chunkStart, chunkEnd))}; the recipe we persist and replay
 *       when answering {@code GET /v1/reconstructions/{fileId}}.</li>
 *   <li><b>CAS Info</b> — for each new xorb, its hash, total serialized size and per-chunk metadata.</li>
 * </ul>
 *
 * <p>All multi-byte integers are little-endian. Faithful port of the layout at
 * <a href="https://huggingface.co/docs/xet/shard">huggingface.co/docs/xet/shard</a> (the
 * {@code huggingface/xet-core} {@code metadata_shard} crate). Decode-only plus a small serializer for
 * global-dedupe responses; chunking/hashing of data is done by the client, never the server.
 *
 * <p>Hashes render to canonical 64-char hex via {@link #hashToHex(byte[])} (the Xet "4 little-endian
 * u64 groups" rule, NOT plain byte-to-hex).
 */
public final class ShardCodec {

    private ShardCodec() {
    }

    // ---- Constants (see spec "Constants") ----
    public static final long MDB_SHARD_HEADER_VERSION = 2L;
    public static final long MDB_SHARD_FOOTER_VERSION = 1L;
    public static final int MDB_SHARD_HEADER_SIZE = 48;
    public static final int MDB_SHARD_FOOTER_SIZE = 200;
    public static final int MDB_ENTRY_SIZE = 48; // file-info and cas-info entries are both 48 bytes

    /** 32-byte magic identifier at the start of every shard ("HFRepoMetaData\0" + version bytes). */
    public static final byte[] MDB_SHARD_HEADER_TAG = plainHexToBytes(
            "48465265706f4d6574614461746100556967456a7b815783a5bdd95ccdd14aa9");

    /** File flag: this file's entries are followed by one FileVerificationEntry each. */
    public static final int MDB_FILE_FLAG_WITH_VERIFICATION = 1 << 31; // 0x80000000
    /** File flag: this file ends with a FileMetadataExt (carries the file's SHA256). */
    public static final int MDB_FILE_FLAG_WITH_METADATA_EXT = 1 << 30;  // 0x40000000

    // ============================================================
    // Parsed model
    // ============================================================

    /** A fully-parsed shard. {@code files} may be empty (e.g. global-dedupe response shards). */
    public static final class ParsedShard {
        public final List<FileInfo> files;
        public final List<XorbInfo> xorbs;
        public final boolean hasFooter;
        /** HMAC key from the footer (all-zero / null when absent — i.e. unprotected hashes). */
        public final byte[] chunkHashHmacKey;

        ParsedShard(List<FileInfo> files, List<XorbInfo> xorbs, boolean hasFooter, byte[] chunkHashHmacKey) {
            this.files = files;
            this.xorbs = xorbs;
            this.hasFooter = hasFooter;
            this.chunkHashHmacKey = chunkHashHmacKey;
        }
    }

    /** One file reconstruction: its id (file hash hex) and ordered terms. */
    public static final class FileInfo {
        public String fileId;          // 64-hex Xet file hash
        public long fileFlags;
        public final List<Term> terms = new ArrayList<>();
        public String sha256Hex;       // plain SHA256 hex if FileMetadataExt present, else null
    }

    /** One reconstruction term: a chunk-index range within a xorb. End index is exclusive. */
    public static final class Term {
        public String xorbHash;        // 64-hex
        public long unpackedSegmentBytes;
        public int chunkIndexStart;
        public int chunkIndexEnd;      // exclusive
        public String rangeVerificationHex; // optional verification hash, else null
    }

    /** CAS info for one xorb: its hash, sizes, and per-chunk metadata. */
    public static final class XorbInfo {
        public String xorbHash;        // 64-hex
        public long numBytesInCas;     // sum of raw (unpacked) chunk bytes
        public long numBytesOnDisk;    // serialized xorb length on disk
        public final List<ChunkEntry> chunks = new ArrayList<>();
        /** Raw bytes of this xorb's CAS-info block (header + entries), for replay in dedupe responses. */
        public byte[] casInfoBlockBytes;

        /** Byte offset of each chunk within the serialized xorb, in chunk order. */
        public long[] chunkByteOffsets() {
            long[] out = new long[chunks.size()];
            for (int i = 0; i < chunks.size(); i++) {
                out[i] = chunks.get(i).chunkByteRangeStart;
            }
            return out;
        }

        /** Per-chunk serialized byte lengths derived from consecutive start offsets + total size. */
        public long[] chunkSerializedLengths() {
            int n = chunks.size();
            long[] lengths = new long[n];
            for (int i = 0; i < n; i++) {
                long start = chunks.get(i).chunkByteRangeStart;
                long end = (i + 1 < n) ? chunks.get(i + 1).chunkByteRangeStart : numBytesOnDisk;
                lengths[i] = Math.max(0, end - start);
            }
            return lengths;
        }
    }

    /** One chunk inside a xorb. */
    public static final class ChunkEntry {
        public String chunkHash;       // 64-hex
        public long chunkByteRangeStart; // byte offset within the serialized xorb
        public long unpackedSegmentBytes;
    }

    // ============================================================
    // Parsing
    // ============================================================

    public static ParsedShard parse(byte[] data) {
        if (data == null || data.length < MDB_SHARD_HEADER_SIZE) {
            throw new IllegalArgumentException("Shard too small to contain a header");
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // ---- Header (48 bytes) ----
        byte[] tag = new byte[32];
        buf.get(tag);
        long headerVersion = buf.getLong();
        if (headerVersion != MDB_SHARD_HEADER_VERSION) {
            throw new IllegalArgumentException("Unsupported shard header version: " + headerVersion);
        }
        long footerSize = buf.getLong();

        // ---- File Info Section (until bookend) ----
        List<FileInfo> files = new ArrayList<>();
        while (true) {
            byte[] fileHash = new byte[32];
            buf.get(fileHash);
            int fileFlags = buf.getInt();
            int numEntries = buf.getInt();
            buf.position(buf.position() + 8); // _unused[8]
            if (isAllFF(fileHash)) {
                break; // bookend: consumed full 48-byte entry
            }
            FileInfo fi = new FileInfo();
            fi.fileId = hashToHex(fileHash);
            fi.fileFlags = fileFlags & 0xFFFFFFFFL;

            for (int i = 0; i < numEntries; i++) {
                byte[] casHash = new byte[32];
                buf.get(casHash);
                buf.getInt(); // cas_flags (reserved)
                long unpacked = buf.getInt() & 0xFFFFFFFFL;
                int chunkStart = buf.getInt();
                int chunkEnd = buf.getInt();
                Term t = new Term();
                t.xorbHash = hashToHex(casHash);
                t.unpackedSegmentBytes = unpacked;
                t.chunkIndexStart = chunkStart;
                t.chunkIndexEnd = chunkEnd;
                fi.terms.add(t);
            }

            if ((fileFlags & MDB_FILE_FLAG_WITH_VERIFICATION) != 0) {
                for (int i = 0; i < numEntries; i++) {
                    byte[] rangeHash = new byte[32];
                    buf.get(rangeHash);
                    buf.position(buf.position() + 16); // _unused[16]
                    fi.terms.get(i).rangeVerificationHex = hashToHex(rangeHash);
                }
            }

            if ((fileFlags & MDB_FILE_FLAG_WITH_METADATA_EXT) != 0) {
                byte[] sha = new byte[32];
                buf.get(sha);
                buf.position(buf.position() + 16); // _unused[16]
                fi.sha256Hex = plainHex(sha); // SHA256 for LFS pointers — standard hex, not the u64 rule
            }

            files.add(fi);
        }

        // ---- CAS Info Section (until bookend) ----
        List<XorbInfo> xorbs = new ArrayList<>();
        while (true) {
            int blockStart = buf.position();
            byte[] casHash = new byte[32];
            buf.get(casHash);
            buf.getInt(); // cas_flags (reserved)
            int numEntries = buf.getInt();
            long numBytesInCas = buf.getInt() & 0xFFFFFFFFL;
            long numBytesOnDisk = buf.getInt() & 0xFFFFFFFFL;
            if (isAllFF(casHash)) {
                break; // bookend
            }
            XorbInfo xi = new XorbInfo();
            xi.xorbHash = hashToHex(casHash);
            xi.numBytesInCas = numBytesInCas;
            xi.numBytesOnDisk = numBytesOnDisk;
            for (int i = 0; i < numEntries; i++) {
                byte[] chunkHash = new byte[32];
                buf.get(chunkHash);
                long start = buf.getInt() & 0xFFFFFFFFL;
                long unpacked = buf.getInt() & 0xFFFFFFFFL;
                buf.position(buf.position() + 8); // _unused[8]
                ChunkEntry ce = new ChunkEntry();
                ce.chunkHash = hashToHex(chunkHash);
                ce.chunkByteRangeStart = start;
                ce.unpackedSegmentBytes = unpacked;
                xi.chunks.add(ce);
            }
            xi.casInfoBlockBytes = Arrays.copyOfRange(data, blockStart, buf.position());
            xorbs.add(xi);
        }

        // ---- Footer (optional; omitted on shard upload) ----
        boolean hasFooter = footerSize > 0 && buf.remaining() >= MDB_SHARD_FOOTER_SIZE;
        byte[] hmacKey = null;
        if (hasFooter) {
            int footerStart = buf.position();
            // version(8) file_info_offset(8) cas_info_offset(8) _buffer(48) => hmac key at +72
            buf.position(footerStart + 72);
            byte[] key = new byte[32];
            buf.get(key);
            if (!isAllZero(key)) {
                hmacKey = key;
            }
        }

        return new ParsedShard(files, xorbs, hasFooter, hmacKey);
    }

    // ============================================================
    // Hash encoding
    // ============================================================

    /**
     * Render a 32-byte Xet MerkleHash to its canonical 64-char lowercase hex string: each 8-byte group
     * is interpreted as a little-endian {@code u64} and formatted as 16 zero-padded hex chars, the four
     * groups concatenated. (NOT a direct byte-to-hex.)
     */
    public static String hashToHex(byte[] hash32) {
        if (hash32 == null || hash32.length != 32) {
            throw new IllegalArgumentException("Xet hash must be exactly 32 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(hash32).order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 4; i++) {
            long v = bb.getLong();
            sb.append(String.format("%016x", v));
        }
        return sb.toString();
    }

    /** Inverse of {@link #hashToHex(byte[])}: parse a 64-char hex string back to the 32-byte hash. */
    public static byte[] hexToHash(String hex) {
        if (hex == null || hex.length() != 64) {
            throw new IllegalArgumentException("Xet hash hex must be exactly 64 chars");
        }
        ByteBuffer bb = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) {
            String group = hex.substring(i * 16, i * 16 + 16);
            bb.putLong(Long.parseUnsignedLong(group, 16));
        }
        return bb.array();
    }

    /** Plain lowercase hex of raw bytes (used for the SHA256 metadata field, LFS-pointer style). */
    public static String plainHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static boolean isAllFF(byte[] b) {
        for (byte x : b) {
            if (x != (byte) 0xFF) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] plainHexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static void putBookend(ByteBuffer b) {
        byte[] ff = new byte[32];
        Arrays.fill(ff, (byte) 0xFF);
        b.put(ff);
        b.put(new byte[16]);
    }

    // ============================================================
    // Serialization (global-dedupe responses)
    // ============================================================

    /**
     * Assemble a global-deduplication response shard: an empty File-Info section, a CAS-Info section
     * containing a single xorb's previously-captured {@link XorbInfo#casInfoBlockBytes}, and a footer.
     * The HMAC key is left zero ("unprotected"), so chunk hashes pass through verbatim — spec-compliant
     * and appropriate for a self-hosted, single-realm CAS.
     */
    public static byte[] serializeGlobalDedupe(byte[] casInfoBlock, long creationTs, long expiryTs) {
        int total = MDB_SHARD_HEADER_SIZE      // header
                + MDB_ENTRY_SIZE               // empty file-info section == just the bookend
                + casInfoBlock.length          // the xorb's cas-info block
                + MDB_ENTRY_SIZE               // cas-info bookend
                + MDB_SHARD_FOOTER_SIZE;       // footer
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);

        // Header
        b.put(MDB_SHARD_HEADER_TAG);
        b.putLong(MDB_SHARD_HEADER_VERSION);
        b.putLong(MDB_SHARD_FOOTER_SIZE);

        long fileInfoOffset = b.position();    // == 48
        putBookend(b);                         // empty file-info section
        long casInfoOffset = b.position();     // == 96
        b.put(casInfoBlock);
        putBookend(b);                         // cas-info bookend
        long footerOffset = b.position();

        // Footer (200 bytes)
        b.putLong(MDB_SHARD_FOOTER_VERSION);
        b.putLong(fileInfoOffset);
        b.putLong(casInfoOffset);
        b.put(new byte[48]);                   // _buffer
        b.put(new byte[32]);                   // chunk_hash_hmac_key = 0 (unprotected)
        b.putLong(creationTs);
        b.putLong(expiryTs);
        b.put(new byte[72]);                   // _buffer2
        b.putLong(footerOffset);

        return b.array();
    }
}
