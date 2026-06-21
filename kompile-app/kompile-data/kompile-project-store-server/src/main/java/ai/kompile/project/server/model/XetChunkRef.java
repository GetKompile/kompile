package ai.kompile.project.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Index from a chunk hash to a xorb that contains it, backing the global-deduplication API
 * ({@code GET /v1/chunks/default-merkledb/{hash}}). Populated from shard CAS-info at upload time.
 */
@Entity
@Table(name = "xet_chunk_ref", indexes = @Index(name = "idx_chunk_hash", columnList = "chunk_hash"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class XetChunkRef {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chunk_hash", nullable = false, length = 64)
    private String chunkHash;

    @Column(name = "xorb_hash", nullable = false, length = 64)
    private String xorbHash;
}
