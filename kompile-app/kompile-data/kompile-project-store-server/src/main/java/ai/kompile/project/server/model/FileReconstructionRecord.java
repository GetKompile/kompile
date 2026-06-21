package ai.kompile.project.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A file's Xet reconstruction recipe: the ordered list of {@code (xorbHash, chunk range)} terms that
 * rebuild the file. Registered on shard upload, read back on {@code GET /v1/reconstructions/{fileId}}.
 */
@Entity
@Table(name = "xet_file_reconstruction", indexes = @Index(name = "idx_xet_file_id", columnList = "file_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileReconstructionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Optional owning project id. */
    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "file_id", nullable = false, length = 64)
    private String fileId;

    @Column(name = "file_path", length = 2000)
    private String filePath;

    @Column(length = 200)
    private String ref;

    /** JSON array of terms: {@code [{"hash","unpacked_length","start","end"}, ...]} in order. */
    @Column(name = "terms_json", columnDefinition = "TEXT")
    private String termsJson;

    private long totalUnpackedLength;

    /** Blob-store key of the raw uploaded shard (fallback / audit). */
    private String shardStorageKey;

    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
