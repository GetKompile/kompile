/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.project;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.SyncAuthMode;
import ai.kompile.app.sync.domain.SyncDirection;
import ai.kompile.app.sync.domain.SyncProvider;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import ai.kompile.project.KompileProjectFactSheet;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectNoteSyncConnection;
import ai.kompile.project.KompileProjectStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Preflights and safely rehydrates runtime catalogs from a portable project.
 *
 * <p>Staged imports are deliberately inspection-only. A project becomes eligible for catalog
 * restoration only after the application is restarted with that project as its configured root.
 * Recreated sync connections are disabled and secret-free.</p>
 */
@Service
public class ProjectRestorationService {

    private static final String RESTART_MODE = "RESTART_REQUIRED";
    private static final String ACTIVE_MODE = "ACTIVE";

    private final ProjectBackendService backend;
    private final FactSheetService factSheets;
    private final NoteSyncConnectionRepository connections;
    private final KompileProjectStore store;

    public ProjectRestorationService(
            ProjectBackendService backend,
            FactSheetService factSheets,
            NoteSyncConnectionRepository connections) {
        this(backend, factSheets, connections, new KompileProjectStore());
    }

    ProjectRestorationService(
            ProjectBackendService backend,
            FactSheetService factSheets,
            NoteSyncConnectionRepository connections,
            KompileProjectStore store) {
        this.backend = backend;
        this.factSheets = factSheets;
        this.connections = connections;
        this.store = store;
    }

    public RestorationReadiness readinessForCurrent() {
        return readiness(backend.currentProjectRoot(), true);
    }

    public RestorationReadiness readinessForStaged(Path root) {
        Path normalized = requireProjectDirectory(root);
        Path current = backend.currentProjectRoot().toAbsolutePath().normalize();
        if (normalized.equals(current)) {
            return readiness(normalized, true);
        }
        return readiness(normalized, false);
    }

    @Transactional
    public RestorationResult restoreCurrent(boolean dryRun) {
        Path root = requireProjectDirectory(backend.currentProjectRoot());
        CatalogState catalog = loadCatalog(root);
        Map<String, FactSheet> sheetsByName = runtimeSheetsByName();
        List<String> warnings = new ArrayList<>();
        int plannedFactSheets = 0;
        int restoredFactSheets = 0;
        int skipped = 0;
        Set<String> plannedSheetNames = new HashSet<>();

        for (KompileProjectFactSheet portable : catalog.factSheets()) {
            String name = trimToNull(portable.getName());
            if (name == null) {
                warnings.add("Skipped a fact sheet catalog entry without a name.");
                skipped++;
                continue;
            }
            if (sheetsByName.containsKey(name)) continue;
            plannedFactSheets++;
            plannedSheetNames.add(name);
            if (dryRun) continue;
            try {
                FactSheet created = factSheets.createSheet(
                        name,
                        portable.getDescription(),
                        portable.getColor(),
                        portable.getIcon(),
                        portableRelativePath(root, portable.getVectorStorePath()),
                        portableRelativePath(root, portable.getKeywordIndexPath()),
                        portable.getEmbeddingModel(),
                        portable.getEmbeddingModelSource(),
                        null,
                        portable.isRerankingEnabled(),
                        portable.getRerankerType(),
                        null,
                        null,
                        null,
                        null,
                        null);
                if (created != null) {
                    created.setEnableGraphBuilding(true);
                    created.setGraphBuilderType(portable.getGraphBuilderType());
                    created.setGraphStorageType(portable.getGraphStorageType());
                    sheetsByName.put(name, created);
                }
                restoredFactSheets++;
            } catch (RuntimeException failure) {
                warnings.add("Could not restore fact sheet '" + name + "': " + failure.getMessage());
                skipped++;
            }
        }

        if (!dryRun) {
            sheetsByName = runtimeSheetsByName();
        }

        List<NoteSyncConnection> runtimeConnections = connections.findAll();
        int plannedSourceConnections = 0;
        int restoredSourceConnections = 0;
        for (KompileProjectNoteSyncConnection portable : catalog.sourceConnections()) {
            String factSheetName = trimToNull(portable.getFactSheetName());
            FactSheet sheet = factSheetName == null ? null : sheetsByName.get(factSheetName);
            if (sheet == null || sheet.getId() == null) {
                if (dryRun && factSheetName != null && plannedSheetNames.contains(factSheetName)
                        && parseProvider(portable.getProvider()) != null
                        && parseDirection(portable.getDirection()) != null
                        && trimToNull(portable.getExternalScope()) != null) {
                    plannedSourceConnections++;
                    continue;
                }
                warnings.add("Skipped source '" + sourceLabel(portable)
                        + "' because its fact sheet is not available.");
                skipped++;
                continue;
            }

            SyncProvider provider = parseProvider(portable.getProvider());
            SyncDirection direction = parseDirection(portable.getDirection());
            if (provider == null || direction == null || trimToNull(portable.getExternalScope()) == null) {
                warnings.add("Skipped invalid source connection '" + sourceLabel(portable) + "'.");
                skipped++;
                continue;
            }
            if (findRuntimeConnection(runtimeConnections, sheet.getId(), provider,
                    portable.getExternalScope()) != null) {
                continue;
            }

            plannedSourceConnections++;
            if (dryRun) continue;
            SyncAuthMode authMode = parseAuthMode(portable.getAuthMode(), provider);
            boolean authRequired = provider == SyncProvider.NOTION || authMode != SyncAuthMode.NONE;
            NoteSyncConnection restored = NoteSyncConnection.builder()
                    .factSheetId(sheet.getId())
                    .provider(provider)
                    .externalScope(portable.getExternalScope())
                    .direction(direction)
                    .pollCron(null)
                    .webhookId(null)
                    .obsidianApiUrl(trimToNull(portable.getObsidianApiUrl()))
                    .repositoryUrl(sanitizeRepositoryUrl(portable.getRepositoryUrl()))
                    .gitBranch(trimToNull(portable.getGitBranch()))
                    .gitUsername(trimToNull(portable.getGitUsername()))
                    .authMode(authMode)
                    .authStatus(authRequired ? "MISSING" : "NOT_REQUIRED")
                    .authStatusMessage(authRequired
                            ? "Bind credentials and pass Test Auth before enabling this restored source."
                            : "No credentials are required; verify the external path before enabling.")
                    .autoCommit(false)
                    .remoteSyncEnabled(false)
                    .enabled(false)
                    .lastSyncStatus("NEVER")
                    .build();
            restored = connections.save(restored);
            runtimeConnections.add(restored);
            restoredSourceConnections++;
        }

        RestorationReadiness after = readiness(root, true);
        return new RestorationResult(
                dryRun,
                !dryRun && (restoredFactSheets > 0 || restoredSourceConnections > 0),
                plannedFactSheets,
                plannedSourceConnections,
                restoredFactSheets,
                restoredSourceConnections,
                skipped,
                List.copyOf(warnings),
                after);
    }

    private RestorationReadiness readiness(Path root, boolean active) {
        Path normalized = requireProjectDirectory(root);
        CatalogState catalog = loadCatalog(normalized);
        Map<String, FactSheet> sheetsByName = active ? runtimeSheetsByName() : Map.of();
        List<NoteSyncConnection> runtimeConnections =
                active ? connections.findAll() : List.of();
        List<RestorationItem> items = new ArrayList<>();

        for (KompileProjectFactSheet portable : catalog.factSheets()) {
            String name = trimToNull(portable.getName());
            boolean restored = active && name != null && sheetsByName.containsKey(name);
            items.add(new RestorationItem(
                    "fact-sheet-" + stablePart(name),
                    "FACT_SHEET",
                    true,
                    active ? (restored ? "RESTORED" : "MISSING") : "STAGED",
                    name == null ? "Unnamed fact sheet" : name,
                    null,
                    name,
                    null,
                    restored ? List.of() : List.of(active ? "RESTORE_CATALOG" : "ACTIVATE_PROJECT"),
                    restored
                            ? "Runtime fact sheet is present."
                            : active
                                    ? "Restore this portable catalog entry as an inactive fact sheet."
                                    : "Activate this staged project before restoring runtime catalogs."));
        }

        for (KompileProjectNoteSyncConnection portable : catalog.sourceConnections()) {
            items.add(sourceItem(normalized, active, portable, sheetsByName, runtimeConnections));
        }

        for (KompileProjectModel model : catalog.manifest().getModels()) {
            items.add(modelItem(normalized, active, model));
        }

        boolean graphPresent = Files.isDirectory(normalized.resolve("data/graph"));
        items.add(new RestorationItem(
                "knowledge-graph",
                "KNOWLEDGE_GRAPH",
                false,
                graphPresent ? "PRESERVED" : "REBUILDABLE",
                "Knowledge graph",
                null,
                "data/graph",
                null,
                graphPresent ? List.of() : List.of("RUN_GRAPH_MAINTENANCE"),
                graphPresent
                        ? "Portable graph data is present and is loaded when the project opens."
                        : "No portable graph data was found; a crawl or source sync can rebuild it."));

        if (!catalog.chatSessions().isEmpty()) {
            items.add(new RestorationItem(
                    "chat-catalog", "CATALOG", false, "PRESERVED",
                    "Chat session catalog", null, "data/chats", null, List.of(),
                    catalog.chatSessions().size() + " chat session summaries are preserved."));
        }
        if (!catalog.indexedDocuments().isEmpty()) {
            items.add(new RestorationItem(
                    "indexed-document-catalog", "CATALOG", false, "REBUILDABLE",
                    "Indexed document catalog", null, "data/indexed-documents", null,
                    List.of("REINDEX_SOURCES"),
                    catalog.indexedDocuments().size()
                            + " indexed document summaries are preserved; indexes can be rebuilt."));
        }

        items.sort(Comparator.comparing(RestorationItem::kind).thenComparing(RestorationItem::label));
        boolean requiredBlocked = items.stream()
                .filter(RestorationItem::required)
                .anyMatch(item -> !readyStatus(item.status()));
        RestorationInventory inventory = new RestorationInventory(
                catalog.factSheets().size(),
                sheetsByName.size(),
                catalog.sourceConnections().size(),
                runtimeConnections.size(),
                catalog.chatSessions().size(),
                catalog.indexedDocuments().size(),
                catalog.manifest().getModels().size());

        String instruction = active
                ? "Runtime is using this project. Review the checklist, restore missing catalogs, "
                        + "then bind, test, and enable each source."
                : "Restart Kompile with -Dkompile.project.root=" + normalized
                        + " (or set kompile.project.root to this path), then restore its catalogs.";
        return new RestorationReadiness(
                normalized.toString(),
                active,
                active && !requiredBlocked,
                active ? ACTIVE_MODE : RESTART_MODE,
                instruction,
                inventory,
                List.copyOf(items));
    }

    private RestorationItem sourceItem(
            Path root,
            boolean active,
            KompileProjectNoteSyncConnection portable,
            Map<String, FactSheet> sheetsByName,
            List<NoteSyncConnection> runtimeConnections) {
        String id = trimToNull(portable.getBindingId());
        if (id == null) id = stableBindingId(portable);
        String label = sourceLabel(portable);
        String bindingType = trimToNull(portable.getCredentialBinding());
        List<String> stagedActions = new ArrayList<>();
        stagedActions.add("ACTIVATE_PROJECT");
        stagedActions.add("RESTORE_CATALOG");
        if (bindingType != null) {
            stagedActions.add("BIND_CREDENTIAL");
            stagedActions.add("TEST_AUTH");
        }
        stagedActions.add("ENABLE");
        if (!active) {
            return new RestorationItem(
                    id, "SOURCE_CONNECTION", true, "STAGED", label, bindingType,
                    portable.getExternalScope(), null, List.copyOf(stagedActions),
                    "Source metadata is portable; credentials and activation are machine-local.");
        }

        FactSheet sheet = sheetsByName.get(portable.getFactSheetName());
        if (sheet == null || sheet.getId() == null) {
            return new RestorationItem(
                    id, "SOURCE_CONNECTION", true, "BLOCKED", label, bindingType,
                    portable.getExternalScope(), null, List.of("RESTORE_CATALOG"),
                    "Restore the referenced fact sheet before restoring this source.");
        }
        SyncProvider provider = parseProvider(portable.getProvider());
        if (provider == null) {
            return new RestorationItem(
                    id, "SOURCE_CONNECTION", true, "INVALID", label, bindingType,
                    portable.getExternalScope(), null, List.of("EDIT_SOURCE"),
                    "The portable source provider is not supported.");
        }
        NoteSyncConnection runtime = findRuntimeConnection(
                runtimeConnections, sheet.getId(), provider, portable.getExternalScope());
        if (runtime == null) {
            return new RestorationItem(
                    id, "SOURCE_CONNECTION", true, "MISSING", label, bindingType,
                    portable.getExternalScope(), null, List.of("RESTORE_CATALOG"),
                    "Restore a disabled, secret-free runtime connection from this catalog entry.");
        }

        boolean localPathRequired = provider == SyncProvider.LOCAL_FOLDER
                || (provider == SyncProvider.OBSIDIAN
                        && runtime.getAuthMode() == SyncAuthMode.NONE);
        if (localPathRequired && !externalPathExists(root, runtime.getExternalScope())) {
            return new RestorationItem(
                    id, "SOURCE_CONNECTION", true, "MISSING_EXTERNAL", label, bindingType,
                    runtime.getExternalScope(), runtime.getId(),
                    List.of("BIND_EXTERNAL_PATH", "TEST_AUTH", "ENABLE"),
                    "The external folder or vault path is not available on this machine.");
        }

        SyncAuthMode mode = runtime.getAuthMode() == null ? SyncAuthMode.NONE : runtime.getAuthMode();
        boolean authRequired = provider == SyncProvider.NOTION || mode != SyncAuthMode.NONE;
        String auth = trimToNull(runtime.getAuthStatus());
        if (authRequired && (auth == null || "MISSING".equalsIgnoreCase(auth))) {
            return sourceRuntimeItem(id, label, bindingType, runtime, "UNBOUND",
                    List.of("BIND_CREDENTIAL", "TEST_AUTH", "ENABLE"),
                    "Bind machine-local credentials; portable archives never contain secrets.");
        }
        if (authRequired && "INVALID".equalsIgnoreCase(auth)) {
            return sourceRuntimeItem(id, label, bindingType, runtime, "INVALID",
                    List.of("BIND_CREDENTIAL", "TEST_AUTH"),
                    "The last authentication test failed.");
        }
        if (authRequired && !"VALID".equalsIgnoreCase(auth)) {
            return sourceRuntimeItem(id, label, bindingType, runtime, "BOUND_UNVERIFIED",
                    List.of("TEST_AUTH", "ENABLE"),
                    "Credentials are configured but must pass Test Auth.");
        }
        if (!Boolean.TRUE.equals(runtime.getEnabled())) {
            return sourceRuntimeItem(id, label, bindingType, runtime, "DISABLED",
                    List.of("ENABLE"),
                    "Authentication and bindings are ready; enable the source when maintenance should run.");
        }
        return sourceRuntimeItem(id, label, bindingType, runtime, "READY", List.of(),
                "The source is bound, tested, and enabled.");
    }

    private RestorationItem sourceRuntimeItem(
            String id,
            String label,
            String bindingType,
            NoteSyncConnection runtime,
            String status,
            List<String> actions,
            String message) {
        return new RestorationItem(
                id, "SOURCE_CONNECTION", true, status, label, bindingType,
                runtime.getExternalScope(), runtime.getId(), actions, message);
    }

    private RestorationItem modelItem(Path root, boolean active, KompileProjectModel model) {
        String label = firstNonBlank(model.getModelId(), model.getId(), "Unnamed model");
        String path = trimToNull(model.getPath());
        boolean available = path != null && portablePathExists(root, path);
        String status;
        List<String> actions;
        String message;
        if (!active) {
            status = "STAGED";
            actions = List.of("ACTIVATE_PROJECT");
            message = "Model availability is checked after the staged project is activated.";
        } else if (available) {
            status = "READY";
            actions = List.of();
            message = "The model asset is available.";
        } else {
            status = "MISSING_EXTERNAL";
            actions = List.of("BIND_OR_FETCH_MODEL");
            message = "Bind or fetch this model on the current machine.";
        }
        return new RestorationItem(
                "model-" + stablePart(firstNonBlank(model.getId(), model.getModelId(), label)),
                "MODEL", model.isRequired(), status, label, "MODEL_ASSET",
                firstNonBlank(path, model.getSourceRepository(), model.getRegistryModelId()),
                null, actions, message);
    }

    private CatalogState loadCatalog(Path root) {
        KompileProjectManifest manifest = store.load(root);
        return new CatalogState(
                manifest,
                store.listFactSheets(root),
                store.listNoteSyncConnections(root),
                store.listChatSessions(root),
                store.listIndexedDocuments(root));
    }

    private Map<String, FactSheet> runtimeSheetsByName() {
        return factSheets.getAllSheets().stream()
                .filter(sheet -> trimToNull(sheet.getName()) != null)
                .collect(Collectors.toMap(
                        FactSheet::getName,
                        sheet -> sheet,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private NoteSyncConnection findRuntimeConnection(
            List<NoteSyncConnection> values,
            Long factSheetId,
            SyncProvider provider,
            String scope) {
        return values.stream()
                .filter(value -> Objects.equals(value.getFactSheetId(), factSheetId))
                .filter(value -> value.getProvider() == provider)
                .filter(value -> Objects.equals(
                        trimToNull(value.getExternalScope()), trimToNull(scope)))
                .findFirst()
                .orElse(null);
    }

    private static SyncProvider parseProvider(String value) {
        try {
            return value == null ? null : SyncProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static SyncDirection parseDirection(String value) {
        try {
            return value == null || value.isBlank()
                    ? SyncDirection.BIDIRECTIONAL
                    : SyncDirection.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static SyncAuthMode parseAuthMode(String value, SyncProvider provider) {
        try {
            if (value != null && !value.isBlank()) {
                return SyncAuthMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException ignored) {
            // Fall back to the safest provider-specific mode.
        }
        if (provider == SyncProvider.OBSIDIAN) return SyncAuthMode.OBSIDIAN_REST_TOKEN;
        if (provider == SyncProvider.GIT_REPOSITORY) return SyncAuthMode.SYSTEM_GIT;
        return SyncAuthMode.NONE;
    }

    private static boolean readyStatus(String status) {
        return "READY".equals(status)
                || "RESTORED".equals(status)
                || "PRESERVED".equals(status)
                || "REBUILDABLE".equals(status);
    }

    private static boolean externalPathExists(Path root, String value) {
        return portablePathExists(root, value);
    }

    private static boolean portablePathExists(Path root, String value) {
        String pathValue = trimToNull(value);
        if (pathValue == null) return false;
        try {
            Path path = Path.of(pathValue);
            Path resolved = path.isAbsolute() ? path : root.resolve(path).normalize();
            return Files.exists(resolved);
        } catch (InvalidPathException invalid) {
            return false;
        }
    }

    private static String portableRelativePath(Path root, String value) {
        String pathValue = trimToNull(value);
        if (pathValue == null) return null;
        try {
            Path path = Path.of(pathValue);
            if (path.isAbsolute()) return null;
            Path normalized = root.resolve(path).normalize();
            return normalized.startsWith(root) ? pathValue : null;
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    private static Path requireProjectDirectory(Path root) {
        if (root == null) throw new IllegalArgumentException("Project root is required");
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)
                || !Files.isRegularFile(normalized.resolve("kompile.project.json"))) {
            throw new IllegalArgumentException("Not a Kompile project directory: " + normalized);
        }
        return normalized;
    }

    private static String sourceLabel(KompileProjectNoteSyncConnection source) {
        return firstNonBlank(source.getProvider(), "SOURCE") + " · "
                + firstNonBlank(source.getFactSheetName(), "unassigned") + " · "
                + firstNonBlank(source.getExternalScope(), "unbound");
    }

    private static String stableBindingId(KompileProjectNoteSyncConnection source) {
        String identity = String.join("|",
                firstNonBlank(source.getProvider(), ""),
                firstNonBlank(source.getFactSheetName(), ""),
                firstNonBlank(source.getExternalScope(), ""),
                firstNonBlank(sanitizeRepositoryUrl(source.getRepositoryUrl()), ""));
        return "source-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitizeRepositoryUrl(String value) {
        String candidate = trimToNull(value);
        if (candidate == null) return null;
        int scheme = candidate.indexOf("://");
        int at = candidate.indexOf('@', Math.max(0, scheme + 3));
        if (scheme >= 0 && at > scheme) {
            candidate = candidate.substring(0, scheme + 3) + candidate.substring(at + 1);
        }
        int query = candidate.indexOf('?');
        if (query >= 0) candidate = candidate.substring(0, query);
        int fragment = candidate.indexOf('#');
        if (fragment >= 0) candidate = candidate.substring(0, fragment);
        return candidate;
    }

    private static String stablePart(String value) {
        String normalized = firstNonBlank(value, "unknown").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) return trimmed;
        }
        return "";
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record CatalogState(
            KompileProjectManifest manifest,
            List<KompileProjectFactSheet> factSheets,
            List<KompileProjectNoteSyncConnection> sourceConnections,
            List<?> chatSessions,
            List<?> indexedDocuments) {
    }

    public record RestorationInventory(
            int catalogFactSheets,
            int runtimeFactSheets,
            int catalogSourceConnections,
            int runtimeSourceConnections,
            int chatSessions,
            int indexedDocuments,
            int models) {
    }

    public record RestorationItem(
            String id,
            String kind,
            boolean required,
            String status,
            String label,
            String bindingType,
            String targetRef,
            Long runtimeConnectionId,
            List<String> actions,
            String message) {

        public RestorationItem {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record RestorationReadiness(
            String projectRoot,
            boolean active,
            boolean ready,
            String activationMode,
            String activationInstruction,
            RestorationInventory inventory,
            List<RestorationItem> items) {

        public RestorationReadiness {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record RestorationResult(
            boolean dryRun,
            boolean applied,
            int plannedFactSheets,
            int plannedSourceConnections,
            int restoredFactSheets,
            int restoredSourceConnections,
            int skipped,
            List<String> warnings,
            RestorationReadiness readiness) {

        public RestorationResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
