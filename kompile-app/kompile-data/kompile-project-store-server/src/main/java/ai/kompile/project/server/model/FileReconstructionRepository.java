package ai.kompile.project.server.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileReconstructionRepository extends JpaRepository<FileReconstructionRecord, UUID> {

    Optional<FileReconstructionRecord> findByFileId(String fileId);

    boolean existsByFileId(String fileId);
}
