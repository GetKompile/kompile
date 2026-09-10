/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.crawl.graph;

import java.util.List;

/** Hard barrier that projects managed code structure into the crawl's resolved fact sheet. */
public interface ManagedCodeProjectionCallback {

    /** Returns only after every project is fully projected; failures must be thrown. */
    void awaitProjection(Long factSheetId, List<String> codeProjectIds);

    /** Release any crawl-duration leases acquired by {@link #awaitProjection}. */
    default void releaseProjection(List<String> codeProjectIds) { }
}
