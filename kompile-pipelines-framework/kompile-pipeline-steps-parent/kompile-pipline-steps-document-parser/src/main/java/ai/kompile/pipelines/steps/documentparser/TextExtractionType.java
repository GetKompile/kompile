package ai.kompile.pipelines.steps.documentparser;

import java.io.Serializable;

/**
 * Defines the types of text extraction that can be performed by the DocumentParserRunner.
 */
public enum TextExtractionType implements Serializable {
    PLAIN_TEXT,
    LAYOUT_TEXT, // May depend on specific Tika parser capabilities
    HTML,
    TABLES,      // Tika's table extraction can be basic; might need post-processing
    METADATA
}