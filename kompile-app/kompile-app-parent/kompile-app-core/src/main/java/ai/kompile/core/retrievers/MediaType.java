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

/**
 * Enumeration of common media types (MIME types) for media content.
 */
public enum MediaType {
    
    // Image types
    IMAGE_PNG("image/png"),
    IMAGE_JPEG("image/jpeg"),
    IMAGE_GIF("image/gif"),
    IMAGE_WEBP("image/webp"),
    IMAGE_BMP("image/bmp"),
    IMAGE_TIFF("image/tiff"),
    IMAGE_SVG("image/svg+xml"),
    
    // Audio types
    AUDIO_MP3("audio/mpeg"),
    AUDIO_WAV("audio/wav"),
    AUDIO_OGG("audio/ogg"),
    AUDIO_AAC("audio/aac"),
    AUDIO_FLAC("audio/flac"),
    
    // Video types
    VIDEO_MP4("video/mp4"),
    VIDEO_AVI("video/x-msvideo"),
    VIDEO_MOV("video/quicktime"),
    VIDEO_WMV("video/x-ms-wmv"),
    VIDEO_FLV("video/x-flv"),
    VIDEO_WEBM("video/webm"),
    
    // Document types
    APPLICATION_PDF("application/pdf"),
    APPLICATION_MSWORD("application/msword"),
    APPLICATION_DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    APPLICATION_XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    APPLICATION_PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    
    // Text types
    TEXT_PLAIN("text/plain"),
    TEXT_HTML("text/html"),
    TEXT_CSS("text/css"),
    TEXT_JAVASCRIPT("text/javascript"),
    TEXT_XML("text/xml"),
    TEXT_CSV("text/csv"),
    
    // Other
    APPLICATION_JSON("application/json"),
    APPLICATION_XML("application/xml"),
    APPLICATION_OCTET_STREAM("application/octet-stream");
    
    private final String mimeType;
    
    MediaType(String mimeType) {
        this.mimeType = mimeType;
    }
    
    /**
     * Returns the MIME type string for this media type.
     * 
     * @return the MIME type string
     */
    public String getMimeType() {
        return mimeType;
    }
    
    /**
     * Finds a MediaType by its MIME type string.
     * 
     * @param mimeType the MIME type string to search for
     * @return the matching MediaType, or APPLICATION_OCTET_STREAM if not found
     */
    public static MediaType fromMimeType(String mimeType) {
        if (mimeType == null) {
            return APPLICATION_OCTET_STREAM;
        }
        
        for (MediaType type : values()) {
            if (type.mimeType.equalsIgnoreCase(mimeType)) {
                return type;
            }
        }
        
        return APPLICATION_OCTET_STREAM;
    }
    
    @Override
    public String toString() {
        return mimeType;
    }
}
