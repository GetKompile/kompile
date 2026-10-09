/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.coordination;

/**
 * Result of attempting to acquire an advisory edit lock on a file.
 */
public class EditLockResult {

    public enum Status {
        ACQUIRED,
        CONFLICT,
        /** Batch acquire only: no conflict on this file, but the all-or-nothing batch aborted. */
        SKIPPED,
        /**
         * Agent-scoped acquire only: the coordinator stayed busy for the whole bounded wait,
         * so nothing was acquired or renewed. Session-level acquires keep failing open.
         */
        BUSY
    }

    private final String lockId;
    private final Status status;
    private final EditLockEntry conflictEntry;
    private final String conflictMessage;
    private final boolean alreadyHeld;

    private EditLockResult(String lockId, Status status,
                           EditLockEntry conflictEntry, String conflictMessage,
                           boolean alreadyHeld) {
        this.lockId = lockId;
        this.status = status;
        this.conflictEntry = conflictEntry;
        this.conflictMessage = conflictMessage;
        this.alreadyHeld = alreadyHeld;
    }

    public static EditLockResult acquired(String lockId) {
        return acquired(lockId, false);
    }

    /**
     * @param alreadyHeld true when the same owner already held this exact lock and the
     *                    call only refreshed it (idempotent re-acquire)
     */
    public static EditLockResult acquired(String lockId, boolean alreadyHeld) {
        return new EditLockResult(lockId, Status.ACQUIRED, null, null, alreadyHeld);
    }

    public static EditLockResult conflict(EditLockEntry conflictEntry, String message) {
        return new EditLockResult(null, Status.CONFLICT, conflictEntry, message, false);
    }

    public static EditLockResult skipped(String message) {
        return new EditLockResult(null, Status.SKIPPED, null, message, false);
    }

    public static EditLockResult busy(String message) {
        return new EditLockResult(null, Status.BUSY, null, message, false);
    }

    public String getLockId() { return lockId; }
    public Status getStatus() { return status; }
    public EditLockEntry getConflictEntry() { return conflictEntry; }
    public String getConflictMessage() { return conflictMessage; }

    /**
     * True when the owner already held this lock before the call. Internal auto-lockers
     * (for example the LSP workspace-edit applier) must not release a lock they did not
     * create, or they would drop the caller's own explicitly registered lock.
     */
    public boolean isAlreadyHeld() { return alreadyHeld; }

    public boolean hasConflict() {
        return status == Status.CONFLICT;
    }

    public boolean isAcquired() {
        return status == Status.ACQUIRED;
    }

    public boolean isBusy() {
        return status == Status.BUSY;
    }
}
