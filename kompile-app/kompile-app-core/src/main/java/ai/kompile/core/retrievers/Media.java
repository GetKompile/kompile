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

package ai.kompile.core.retrievers;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;

/**
 * Represents media content with a specified media type.
 * This class encapsulates binary data along with its media type information.
 */
@Getter
@EqualsAndHashCode
@ToString
public class Media {
    
    /**
     * The media type (MIME type) of the content
     */
    private final MediaType mediaType;
    
    /**
     * The binary data content
     */
    private final byte[] data;
    
    /**
     * Creates a new Media instance.
     * 
     * @param mediaType the media type of the content
     * @param data the binary data content
     * @throws IllegalArgumentException if mediaType or data is null
     */
    public Media(MediaType mediaType, byte[] data) {
        if (mediaType == null) {
            throw new IllegalArgumentException("mediaType cannot be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("data cannot be null");
        }
        
        this.mediaType = mediaType;
        this.data = data.clone(); // Defensive copy
    }
    
    /**
     * Returns a copy of the media data.
     * 
     * @return a copy of the binary data
     */
    public byte[] getData() {
        return data.clone(); // Defensive copy
    }
    
    /**
     * Returns the size of the media data in bytes.
     * 
     * @return the size of the data
     */
    public int getSize() {
        return data.length;
    }
}
