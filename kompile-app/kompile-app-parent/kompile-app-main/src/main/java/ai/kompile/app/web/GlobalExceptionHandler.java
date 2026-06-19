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

package ai.kompile.app.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for REST controllers.
 * Ensures proper error responses are sent even when unexpected exceptions occur.
     * Applies to controllers in the web.controllers package and the event-attribution
     * controllers (Bayesian / MEBN / causal attribution, package
     * ai.kompile.event.attribution.controller) so their failures return a structured
     * error body ({error, message, type, ...}) instead of an opaque 500 — static
     * resources use Spring's default error handling.
     */
    @ControllerAdvice(basePackages = {
            "ai.kompile.app.web.controllers",
            "ai.kompile.event.attribution.controller",
            "ai.kompile.event.observation.controller",
            "ai.kompile.process.attribution.controller"
    })
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle all uncaught exceptions to ensure a response is always sent.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAllExceptions(Exception ex, WebRequest request) {
        logger.error("Unhandled exception in request {}: {}",
                request.getDescription(false), ex.getMessage(), ex);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Internal server error");
        errorResponse.put("message", ex.getMessage());
        errorResponse.put("type", ex.getClass().getSimpleName());
        errorResponse.put("timestamp", System.currentTimeMillis());

        // Include stack trace for debugging (in production, you might want to disable this)
        if (ex.getCause() != null) {
            errorResponse.put("cause", ex.getCause().getMessage());
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    /**
     * Handle OutOfMemoryError to provide a meaningful response.
     */
    @ExceptionHandler(OutOfMemoryError.class)
    public ResponseEntity<Map<String, Object>> handleOutOfMemoryError(OutOfMemoryError ex, WebRequest request) {
        logger.error("OutOfMemoryError in request {}: {}",
                request.getDescription(false), ex.getMessage(), ex);

        // Try to trigger GC
        System.gc();

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Out of memory");
        errorResponse.put("message", "The server ran out of memory while processing your request. " +
                "Try uploading a smaller file or using async upload mode.");
        errorResponse.put("type", "OutOfMemoryError");
        errorResponse.put("timestamp", System.currentTimeMillis());
        errorResponse.put("suggestion", "Use the async upload endpoint (/api/documents/upload-async) " +
                "for large files to avoid blocking the server.");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
    }

    /**
     * Handle file upload size exceeded.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, WebRequest request) {
        logger.warn("Upload size exceeded in request {}: {}",
                request.getDescription(false), ex.getMessage());

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "File too large");
        errorResponse.put("message", "The uploaded file exceeds the maximum allowed size.");
        errorResponse.put("type", "MaxUploadSizeExceededException");
        errorResponse.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(errorResponse);
    }

    /**
     * Handle IllegalArgumentException for bad request parameters.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        logger.warn("Bad request parameter in {}: {}",
                request.getDescription(false), ex.getMessage());

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Bad request");
        errorResponse.put("message", ex.getMessage());
        errorResponse.put("type", "IllegalArgumentException");
        errorResponse.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handle NoHandlerFoundException - when no handler is registered for a request.
     * This helps diagnose when controllers aren't properly mapped.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoHandlerFound(
            NoHandlerFoundException ex, WebRequest request) {
        logger.error("NO HANDLER FOUND for {} {} - This usually means the controller is not properly registered. " +
                     "Check if the controller bean is created and has @RestController annotation.",
                ex.getHttpMethod(), ex.getRequestURL());

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Endpoint not found");
        errorResponse.put("message", "No handler found for " + ex.getHttpMethod() + " " + ex.getRequestURL());
        errorResponse.put("type", "NoHandlerFoundException");
        errorResponse.put("timestamp", System.currentTimeMillis());
        errorResponse.put("suggestion", "Check application logs for 'REQUEST MAPPING DIAGNOSTIC' to see registered endpoints");

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    /**
     * Handle NoResourceFoundException - when a static resource is not found.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(
            NoResourceFoundException ex, WebRequest request) {
        logger.warn("No resource found for {}: {}",
                request.getDescription(false), ex.getMessage());

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Resource not found");
        errorResponse.put("message", ex.getMessage());
        errorResponse.put("type", "NoResourceFoundException");
        errorResponse.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    /**
     * Handle RuntimeExceptions to capture detailed error info.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(
            RuntimeException ex, WebRequest request) {
        logger.error("RuntimeException in request {}: {}",
                request.getDescription(false), ex.getMessage(), ex);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Processing error");
        errorResponse.put("message", ex.getMessage());
        errorResponse.put("type", ex.getClass().getSimpleName());
        errorResponse.put("timestamp", System.currentTimeMillis());

        // Check for specific error types that might indicate system issues
        String message = ex.getMessage();
        if (message != null) {
            if (message.contains("memory") || message.contains("heap")) {
                errorResponse.put("suggestion", "System may be under memory pressure. " +
                        "Try using async upload or uploading a smaller file.");
            } else if (message.contains("timeout") || message.contains("timed out")) {
                errorResponse.put("suggestion", "Operation timed out. " +
                        "Try using async upload for large files.");
            }
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}
