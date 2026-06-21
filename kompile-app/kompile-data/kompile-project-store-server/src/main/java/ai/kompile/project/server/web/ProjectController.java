package ai.kompile.project.server.web;

import ai.kompile.project.server.ProjectStoreServerProperties;
import ai.kompile.project.server.git.GitRepoService;
import ai.kompile.project.server.model.Project;
import ai.kompile.project.server.model.ProjectRepository;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Project hosting control-plane API: list/create projects and browse files at a ref. Git transport is
 * served by {@link GitHttpController}; large data is materialized via the Xet CAS endpoints.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectRepository projectRepo;
    private final GitRepoService gitRepoService;
    private final ProjectStoreServerProperties props;

    public ProjectController(ProjectRepository projectRepo, GitRepoService gitRepoService,
                             ProjectStoreServerProperties props) {
        this.projectRepo = projectRepo;
        this.gitRepoService = gitRepoService;
        this.props = props;
    }

    @GetMapping
    public List<ProjectDto> list() {
        return projectRepo.findAll().stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/public")
    public List<ProjectDto> listPublic() {
        return projectRepo.findByVisibility("public").stream().map(this::toDto).collect(Collectors.toList());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ProjectDto> create(@RequestBody CreateProjectRequest req) {
        if (req == null || isBlank(req.namespace) || isBlank(req.slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "namespace and slug are required");
        }
        if (projectRepo.existsByNamespaceAndSlug(req.namespace, req.slug)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "project already exists");
        }
        Project p = Project.builder()
                .namespace(req.namespace)
                .slug(req.slug)
                .repoType(req.repoType != null ? req.repoType : "model")
                .visibility(req.visibility != null ? req.visibility : "public")
                .defaultBranch(req.defaultBranch != null ? req.defaultBranch : "main")
                .description(req.description)
                .gitPrefix(req.namespace + "/" + req.slug + ".git")
                .build();
        projectRepo.save(p);
        gitRepoService.initBareRepo(p.getNamespace(), p.getSlug(), p.getDefaultBranch());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(p));
    }

    @GetMapping("/{namespace}/{slug}")
    public ProjectDto get(@PathVariable String namespace, @PathVariable String slug) {
        Project p = projectRepo.findByNamespaceAndSlug(namespace, slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ProjectDto dto = toDto(p);
        try (Repository repo = gitRepoService.openRepo(namespace, slug)) {
            byte[] manifest = gitRepoService.getBlob(repo, p.getDefaultBranch(), "kompile.project.json");
            if (manifest != null) {
                dto.manifest = new String(manifest, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOG.debug("No manifest for {}/{}: {}", namespace, slug, e.getMessage());
        }
        return dto;
    }

    @GetMapping("/{namespace}/{slug}/tree/{ref}")
    public List<GitRepoService.TreeEntry> tree(@PathVariable String namespace, @PathVariable String slug,
                                               @PathVariable String ref,
                                               @RequestParam(required = false) String path) {
        projectRepo.findByNamespaceAndSlug(namespace, slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!gitRepoService.repoExists(namespace, slug)) {
            return List.of();
        }
        try (Repository repo = gitRepoService.openRepo(namespace, slug)) {
            return gitRepoService.listTree(repo, ref, path);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping("/{namespace}/{slug}/blob/{ref}")
    public ResponseEntity<byte[]> blob(@PathVariable String namespace, @PathVariable String slug,
                                       @PathVariable String ref, @RequestParam String path) {
        projectRepo.findByNamespaceAndSlug(namespace, slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try (Repository repo = gitRepoService.openRepo(namespace, slug)) {
            byte[] content = gitRepoService.getBlob(repo, ref, path);
            if (content == null) {
                return ResponseEntity.notFound().build();
            }
            String filename = path.substring(path.lastIndexOf('/') + 1);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(content);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Download the whole project tree at {@code ref} as a streamed ZIP archive. */
    @GetMapping("/{namespace}/{slug}/archive/{ref}")
    public ResponseEntity<StreamingResponseBody> archive(@PathVariable String namespace,
                                                         @PathVariable String slug,
                                                         @PathVariable String ref) {
        projectRepo.findByNamespaceAndSlug(namespace, slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!gitRepoService.repoExists(namespace, slug)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        StreamingResponseBody body = out -> {
            try (Repository repo = gitRepoService.openRepo(namespace, slug)) {
                gitRepoService.writeArchive(repo, ref, out);
            }
        };
        String filename = slug + "-" + ref + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.valueOf("application/zip"))
                .body(body);
    }

    private ProjectDto toDto(Project p) {
        ProjectDto d = new ProjectDto();
        d.id = p.getId() != null ? p.getId().toString() : null;
        d.namespace = p.getNamespace();
        d.slug = p.getSlug();
        d.fullName = p.fullName();
        d.repoType = p.getRepoType();
        d.visibility = p.getVisibility();
        d.defaultBranch = p.getDefaultBranch();
        d.description = p.getDescription();
        d.cloneUrl = props.getGitBase() + "/" + p.getNamespace() + "/" + p.getSlug() + ".git";
        d.cliCloneCommand = "kompile clone " + p.fullName();
        return d;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Request body for creating a project. */
    public static class CreateProjectRequest {
        public String namespace;
        public String slug;
        public String repoType;
        public String visibility;
        public String defaultBranch;
        public String description;
    }
}
