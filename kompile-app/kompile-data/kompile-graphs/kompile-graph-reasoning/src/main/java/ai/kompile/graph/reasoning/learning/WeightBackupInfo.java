/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

/**
 * Descriptor for a single weight-store backup snapshot produced by {@link WeightStore#backup}.
 *
 * <p>Instances are returned from {@link WeightStore#listBackups} so callers can enumerate
 * available backups and restore one via {@link WeightStore#restoreBackup}.</p>
 *
 * @param backupId    opaque identifier for this backup — pass to {@link WeightStore#restoreBackup}
 * @param programId   the program/ruleset this backup covers
 * @param timestamp   ISO-8601 UTC timestamp when the backup was taken (e.g. {@code "2026-06-23T14:05:30Z"})
 * @param versionAt   the learning-version number that was active at backup time (0 if unknown)
 * @param entryCount  number of rule/edge weight entries stored in the backup
 */
public record WeightBackupInfo(
        String backupId,
        String programId,
        String timestamp,
        int versionAt,
        int entryCount
) {}
