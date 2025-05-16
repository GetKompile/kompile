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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The data type of an {@link NDArray}
 */
@Schema(description = "An enum that specifies the data type of an n-dimensional array.")
public enum NDArrayType {
    DOUBLE,
    FLOAT,
    FLOAT16,
    BFLOAT16,
    INT64,
    INT32,
    INT16,
    INT8,
    UINT64,
    UINT32,
    UINT16,
    UINT8,
    BOOL,
    UTF8;

    public boolean isFixedWidth(){
        return width() > 0;
    }

    public int width() {
        switch (this) {
            case DOUBLE:
            case INT64:
            case UINT64:
                return 8;
            case FLOAT:
            case INT32:
            case UINT32:
                return 4;
            case FLOAT16:
            case BFLOAT16:
            case INT16:
            case UINT16:
                return 2;
            case INT8:
            case UINT8:
            case BOOL:
                return 1;
            case UTF8:
            default:
                return 0;
        }
    }
}
