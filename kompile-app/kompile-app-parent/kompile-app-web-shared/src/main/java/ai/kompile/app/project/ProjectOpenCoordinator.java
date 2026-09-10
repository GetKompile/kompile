/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.project;

import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Opens a portable project in dependency order: metadata, runtime catalogs, then graph state.
 * Catalog restoration returns from its local transaction before external graph stores are touched.
 */
@Service
public class ProjectOpenCoordinator {

    private final ProjectBackendService backend;
    private final ProjectRestorationService restoration;
    private final ProjectGraphPortabilityService graphs;

    public ProjectOpenCoordinator(ProjectBackendService backend,
                                  ProjectRestorationService restoration,
                                  ProjectGraphPortabilityService graphs) {
        this.backend = backend;
        this.restoration = restoration;
        this.graphs = graphs;
    }

    public synchronized ProjectResponse open() {
        backend.open();
        Path root = backend.currentProjectRoot();
        ProjectRestorationService.RestorationResult restored = restoration.restoreCurrent(false);
        boolean missingFactSheet = restored.readiness().items().stream()
                .anyMatch(item -> "FACT_SHEET".equals(item.kind())
                        && !"RESTORED".equals(item.status()));
        if (missingFactSheet) {
            throw new IllegalStateException("Portable fact-sheet catalog restoration is incomplete: "
                    + String.join("; ", restored.warnings()));
        }
        graphs.importAllGraphs(root);
        return backend.current();
    }
}
