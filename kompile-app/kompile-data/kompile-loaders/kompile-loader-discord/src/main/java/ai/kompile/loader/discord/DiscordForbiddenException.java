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

package ai.kompile.loader.discord;

import java.io.IOException;

/**
 * Thrown when the Discord API returns 403 Forbidden (e.g. "Missing Access" on a channel the bot
 * hasn't been granted into). Kept distinct from other {@link IOException}s so callers can treat
 * missing access to a single channel or thread as a skip rather than a fatal crawl failure.
 */
public class DiscordForbiddenException extends IOException {
    public DiscordForbiddenException(String message) {
        super(message);
    }
}
