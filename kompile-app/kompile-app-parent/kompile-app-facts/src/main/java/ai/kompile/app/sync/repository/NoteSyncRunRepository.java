/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.repository;

import ai.kompile.app.sync.domain.NoteSyncRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoteSyncRunRepository extends JpaRepository<NoteSyncRun, String> {

    List<NoteSyncRun> findTop50ByOrderByQueuedAtDesc();

    List<NoteSyncRun> findTop50ByConnectionIdOrderByQueuedAtDesc(Long connectionId);

    List<NoteSyncRun> findTop50ByFactSheetIdOrderByQueuedAtDesc(Long factSheetId);
}
