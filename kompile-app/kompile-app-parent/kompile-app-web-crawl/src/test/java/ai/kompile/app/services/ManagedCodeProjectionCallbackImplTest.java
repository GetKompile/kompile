/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.services;

import ai.kompile.app.project.ProjectBackendService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class ManagedCodeProjectionCallbackImplTest {

    @Test
    void projectsEveryCodeProjectInInputOrder() {
        ProjectBackendService projects = mock(ProjectBackendService.class);
        ManagedCodeProjectionCallbackImpl callback = new ManagedCodeProjectionCallbackImpl(projects);

        callback.awaitProjection(42L, List.of("app", "library"));

        InOrder order = inOrder(projects);
        order.verify(projects).bindCodingProjectFactSheetAndProject("app", 42L);
        order.verify(projects).bindCodingProjectFactSheetAndProject("library", 42L);
    }

    @Test
    void projectionFailureIsNotSuppressed() {
        ProjectBackendService projects = mock(ProjectBackendService.class);
        doThrow(new IllegalStateException("projection failed"))
                .when(projects).bindCodingProjectFactSheetAndProject("app", 42L);
        ManagedCodeProjectionCallbackImpl callback = new ManagedCodeProjectionCallbackImpl(projects);

        assertThrows(IllegalStateException.class,
                () -> callback.awaitProjection(42L, List.of("app")));
    }
}
