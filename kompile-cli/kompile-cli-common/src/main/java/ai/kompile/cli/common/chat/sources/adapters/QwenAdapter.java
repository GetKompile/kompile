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

package ai.kompile.cli.common.chat.sources.adapters;

import ai.kompile.cli.common.chat.sources.ChatAdapterSupport;

import java.nio.file.Path;

public class QwenAdapter extends ClaudeCodeAdapter {

    public static final String ID = "qwen";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Qwen";
    }

    @Override
    protected Path rootDir() {
        return ChatAdapterSupport.userHome().resolve(".qwen").resolve("projects");
    }
}
