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
package ai.kompile.core.staging;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a training subprocess transitions between phases.
 * Shared between kompile-app-main and kompile-model-staging.
 */
@Getter
public class TrainingPhaseTransitionEvent extends ApplicationEvent {
    private final String jobId;
    private final String fromPhase;
    private final String toPhase;

    public TrainingPhaseTransitionEvent(Object source, String jobId, String fromPhase, String toPhase) {
        super(source);
        this.jobId = jobId;
        this.fromPhase = fromPhase;
        this.toPhase = toPhase;
    }
}
