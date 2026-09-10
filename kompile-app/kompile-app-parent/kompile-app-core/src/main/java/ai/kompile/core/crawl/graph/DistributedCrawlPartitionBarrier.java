/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.core.crawl.graph;

/** Worker-side transport-neutral barrier before corpus-global graph finalization. */
public interface DistributedCrawlPartitionBarrier {

    enum Decision {
        COMPLETE_PARTITION,
        RUN_CORPUS_FINALIZATION,
        ABORT
    }

    Decision await(DistributedGraphExecution execution,
                   DistributedGraphRuntimeContext runtime,
                   UnifiedCrawlJob.ProgressSnapshot snapshot);
}
