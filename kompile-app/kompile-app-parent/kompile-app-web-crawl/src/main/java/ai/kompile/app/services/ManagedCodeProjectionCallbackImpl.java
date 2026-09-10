/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.services;

import ai.kompile.app.project.ProjectBackendService;
import ai.kompile.crawl.graph.ManagedCodeProjectionCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Application bridge that turns the crawl callback into a strict synchronous project projection. */
@Component
public class ManagedCodeProjectionCallbackImpl implements ManagedCodeProjectionCallback {

    private final ProjectBackendService projectBackendService;
    private final Map<String, Semaphore> projectLeases = new ConcurrentHashMap<>();

    public ManagedCodeProjectionCallbackImpl(ProjectBackendService projectBackendService) {
        this.projectBackendService = projectBackendService;
    }

    @Override
    public void awaitProjection(Long factSheetId, List<String> codeProjectIds) {
        if (codeProjectIds == null || codeProjectIds.isEmpty()) return;
        List<String> ordered = codeProjectIds.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        List<Semaphore> acquired = new ArrayList<>();
        try {
            for (String codeProjectId : ordered) {
                Semaphore lease = projectLeases.computeIfAbsent(
                        codeProjectId, ignored -> new Semaphore(1, true));
                lease.acquire();
                acquired.add(lease);
            }
            for (String codeProjectId : codeProjectIds) {
                projectBackendService.bindCodingProjectFactSheetAndProject(codeProjectId, factSheetId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired.forEach(Semaphore::release);
            throw new IllegalStateException("Interrupted while waiting for code projection lease", e);
        } catch (RuntimeException failure) {
            acquired.forEach(Semaphore::release);
            throw failure;
        }
    }

    @Override
    public void releaseProjection(List<String> codeProjectIds) {
        if (codeProjectIds == null) return;
        codeProjectIds.stream().distinct().sorted().forEach(id -> {
            Semaphore lease = projectLeases.get(id);
            if (lease != null) lease.release();
        });
    }
}
