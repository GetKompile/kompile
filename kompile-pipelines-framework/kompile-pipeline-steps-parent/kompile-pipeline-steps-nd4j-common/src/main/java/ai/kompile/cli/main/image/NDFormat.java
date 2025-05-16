/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.cli.main.image;


/**
 * The format to be used when converting an Image to an NDArray<br>
 * CHANNELS_FIRST (output shape: [1, c, h, w] or [c, h, w]) or CHANNELS_LAST (output shape: [1, h, w, c] or [h, w, c])<br>
 * See {@link ai.konduit.serving.data.image.convert.ImageToNDArrayConfig}
 */

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The format to be used when converting an Image to an NDArray. " +
        "CHANNELS_FIRST -> (output shape: [1, c, h, w] or [c, h, w]), " +
        "CHANNELS_LAST -> (output shape: [1, h, w, c] or [h, w, c]).")
public enum NDFormat {
    CHANNELS_FIRST,
    CHANNELS_LAST
}
