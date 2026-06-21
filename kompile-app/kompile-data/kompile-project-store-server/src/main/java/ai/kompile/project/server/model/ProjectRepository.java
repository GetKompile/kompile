package ai.kompile.project.server.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByNamespaceAndSlug(String namespace, String slug);

    boolean existsByNamespaceAndSlug(String namespace, String slug);

    List<Project> findByNamespace(String namespace);

    List<Project> findByVisibility(String visibility);
}
