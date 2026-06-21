package ai.kompile.project.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for a stored Xet xorb. The xorb bytes live in the blob store keyed by hash; here we keep
 * the serialized chunk layout (for reconstruction byte ranges) and the raw CAS-info block (for global
 * dedupe responses).
 */
@Entity
@Table(name = "xet_xorb",
        uniqueConstraints = @UniqueConstraint(name = "uq_xorb_hash", columnNames = "xorb_hash"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class XorbRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "xorb_hash", nullable = false, length = 64)
    private String xorbHash;

    /** Total serialized length of the stored xorb, in bytes. */
    private long serializedLength;

    private int chunkCount;

    /**
     * Comma-separated per-chunk <em>serialized</em> byte lengths ({@code 8 + compressed_size}), in
     * chunk order, computed by parsing the stored xorb (see XorbCodec). Cumulative sums give each
     * chunk's byte offset within the serialized xorb — what a reconstruction {@code url_range} needs.
     */
    @Column(name = "chunk_lengths_csv", columnDefinition = "TEXT")
    private String chunkLengthsCsv;

    /** Base64 of the xorb's CAS-info block (chunk hashes + raw offsets), for global-dedupe replay. */
    @Column(name = "cas_info_b64", columnDefinition = "TEXT")
    private String casInfoB64;

    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public long[] chunkLengths() {
        if (chunkLengthsCsv == null || chunkLengthsCsv.isEmpty()) {
            return new long[0];
        }
        String[] parts = chunkLengthsCsv.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Long.parseLong(parts[i].trim());
        }
        return out;
    }

    public static String toChunkLengthsCsv(long[] lengths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lengths.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(lengths[i]);
        }
        return sb.toString();
    }

    /** Inclusive {@code [start, end]} byte range within the serialized xorb for chunk range [start,end). */
    public long[] byteRangeForChunks(int chunkStart, int chunkEnd) {
        long[] lengths = chunkLengths();
        long start = 0;
        for (int i = 0; i < chunkStart && i < lengths.length; i++) {
            start += lengths[i];
        }
        long end = start;
        for (int i = chunkStart; i < chunkEnd && i < lengths.length; i++) {
            end += lengths[i];
        }
        return new long[]{start, Math.max(start, end - 1)};
    }
}
