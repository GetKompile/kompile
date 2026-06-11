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
        CONFLICT
    }

    private final String lockId;
    private final Status status;
    private final EditLockEntry conflictEntry;
    private final String conflictMessage;

    private EditLockResult(String lockId, Status status,
                           EditLockEntry conflictEntry, String conflictMessage) {
        this.lockId = lockId;
        this.status = status;
        this.conflictEntry = conflictEntry;
        this.conflictMessage = conflictMessage;
    }

    public static EditLockResult acquired(String lockId) {
        return new EditLockResult(lockId, Status.ACQUIRED, null, null);
    }

    public static EditLockResult conflict(EditLockEntry conflictEntry, String message) {
        return new EditLockResult(null, Status.CONFLICT, conflictEntry, message);
    }

    public String getLockId() { return lockId; }
    public Status getStatus() { return status; }
    public EditLockEntry getConflictEntry() { return conflictEntry; }
    public String getConflictMessage() { return conflictMessage; }

    public boolean hasConflict() {
        return status == Status.CONFLICT;
    }

    public boolean isAcquired() {
        return status == Status.ACQUIRED;
    }
}
