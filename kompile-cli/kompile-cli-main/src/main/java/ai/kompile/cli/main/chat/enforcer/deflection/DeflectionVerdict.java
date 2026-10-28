/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use it except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer.deflection;

import java.util.ArrayList;
import java.util.List;

/**
 * Final outcome of one deflection check: whether the output deflects
 * (non-ownership claim without receipts), and why.
 */
public record DeflectionVerdict(
        boolean deflecting,
        List<DeflectionSignal> signals,
        DeflectionEvidence evidence,
        String reasoning,
        Action action) {

    /** What the caller should do about a verdict. */
    public enum Action {
        /** No signal or receipts present — nothing to do. */
        NONE,
        /** Signal present but receipts shown — pass with a note. */
        PASS_NOTED,
        /** Signal, no receipts, below the correction threshold — log-only. */
        LOG_ONLY,
        /** Signal, no receipts, at/above threshold — send the ownership mandate. */
        CORRECT,
        /** Repeated uncorrected deflection — end the turn via supervisor feedback. */
        STOP
    }

    public static DeflectionVerdict none() {
        return new DeflectionVerdict(false, List.of(), DeflectionEvidence.empty(),
                "no deflection signal", Action.NONE);
    }

    public static DeflectionVerdict passNoted(List<DeflectionSignal> signals,
                                               DeflectionEvidence evidence, String reasoning) {
        return new DeflectionVerdict(false, signals, evidence, reasoning, Action.PASS_NOTED);
    }

    public static DeflectionVerdict logOnly(List<DeflectionSignal> signals,
                                            DeflectionEvidence evidence, String reasoning) {
        return new DeflectionVerdict(false, signals, evidence, reasoning, Action.LOG_ONLY);
    }

    public static DeflectionVerdict correct(List<DeflectionSignal> signals,
                                            DeflectionEvidence evidence, String reasoning) {
        return new DeflectionVerdict(true, signals, evidence, reasoning, Action.CORRECT);
    }

    public static DeflectionVerdict stop(List<DeflectionSignal> signals,
                                         DeflectionEvidence evidence, String reasoning) {
        return new DeflectionVerdict(true, signals, evidence, reasoning, Action.STOP);
    }

    public boolean isActionable() {
        return action == Action.CORRECT || action == Action.STOP;
    }

    public String signalSummary() {
        if (signals.isEmpty()) {
            return "no signals";
        }
        List<String> parts = new ArrayList<>();
        for (DeflectionSignal s : signals) {
            parts.add(s.describe());
        }
        return String.join("; ", parts);
    }
}
