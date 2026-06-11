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

package ai.kompile.toolgateway.model;

/**
 * The action the gateway can take on an intercepted tool call.
 */
public enum GatewayAction {
    /** Allow the tool call to proceed with original arguments. */
    ALLOW,
    /** Rewrite the tool call arguments before forwarding. */
    REWRITE,
    /** Block the tool call entirely and return an error to the caller. */
    BLOCK
}
