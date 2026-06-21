package ai.kompile.project.server.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface XorbRecordRepository extends JpaRepository<XorbRecord, UUID> {

    Optional<XorbRecord> findByXorbHash(String xorbHash);

    boolean existsByXorbHash(String xorbHash);
}
