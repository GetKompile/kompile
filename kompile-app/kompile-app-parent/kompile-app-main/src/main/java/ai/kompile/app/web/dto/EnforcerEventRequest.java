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

package ai.kompile.app.web.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Request body for pushing an interrupt event from a CLI-side enforcer.
 */
@Getter
@Setter
public class EnforcerEventRequest {

    private String eventId;
    private String type;
    private String severity;
    private double score;
    private List<String> violations;
    private String reason;
    private String correctionPrompt;
    private String action;

}
