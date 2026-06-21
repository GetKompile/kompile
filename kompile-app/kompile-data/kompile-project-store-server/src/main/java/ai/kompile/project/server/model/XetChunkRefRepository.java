package ai.kompile.project.server.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface XetChunkRefRepository extends JpaRepository<XetChunkRef, UUID> {

    Optional<XetChunkRef> findFirstByChunkHash(String chunkHash);

    boolean existsByChunkHash(String chunkHash);
}
