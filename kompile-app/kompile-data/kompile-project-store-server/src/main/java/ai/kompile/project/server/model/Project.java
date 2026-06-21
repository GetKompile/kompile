package ai.kompile.project.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * A hosted Kompile project / repository — a git repo (served over Git Smart-HTTP) whose large data
 * directories are backed by the Xet content-addressed store. The pair {@code (namespace, slug)}
 * uniquely identifies it and forms both the git URL path and the Xet {@code repoId}.
 */
@Entity
@Table(name = "kompile_project",
        uniqueConstraints = @UniqueConstraint(name = "uq_project_ns_slug", columnNames = {"namespace", "slug"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String namespace;

    @Column(nullable = false)
    private String slug;

    /** "model", "dataset" or "space" (mirrors the Hugging Face Xet repo types). */
    @Column(nullable = false)
    @Builder.Default
    private String repoType = "model";

    /** "public" or "private". */
    @Column(nullable = false)
    @Builder.Default
    private String visibility = "public";

    @Column(nullable = false)
    @Builder.Default
    private String defaultBranch = "main";

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Relative path of the bare git repo ({@code namespace/slug.git}) under the data dir. */
    private String gitPrefix;

    /** Cached {@code kompile.project.json} contents for fast manifest rendering. */
    @Column(columnDefinition = "TEXT")
    private String manifestCacheJson;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String fullName() {
        return namespace + "/" + slug;
    }

    public boolean publiclyVisible() {
        return "public".equalsIgnoreCase(visibility);
    }
}
