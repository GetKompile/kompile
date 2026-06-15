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

package ai.kompile.core.source.provider;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Describes a form field for source provider configuration UI.
 * The frontend uses this to dynamically render form inputs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceFormField {

    /**
     * Field types supported in the UI.
     */
    public enum FieldType {
        TEXT,           // Single-line text input
        TEXTAREA,       // Multi-line text input
        PASSWORD,       // Password input (masked)
        NUMBER,         // Numeric input
        CHECKBOX,       // Boolean checkbox
        SELECT,         // Dropdown select
        MULTI_SELECT,   // Multi-select dropdown or chips
        FILE,           // File upload input
        DATE,           // Date picker
        DATE_RANGE,     // Date range picker
        URL,            // URL input with validation
        EMAIL,          // Email input with validation
        SLIDER,         // Numeric slider
        TOGGLE,         // Toggle switch
        HIDDEN          // Hidden field (not shown in UI)
    }

    /**
     * Unique field identifier (used as form control name).
     * Examples: "url", "channelId", "apiToken", "messageLimit"
     */
    private String id;

    /**
     * Display label for the field.
     * Examples: "URL", "Channel ID", "API Token", "Message Limit"
     */
    private String label;

    /**
     * The type of form input.
     */
    private FieldType type;

    /**
     * Placeholder text for the input.
     */
    private String placeholder;

    /**
     * Help text or description shown below the field.
     */
    private String helpText;

    /**
     * Whether this field is required.
     */
    @Builder.Default
    private boolean required = false;

    /**
     * Default value for the field.
     */
    private Object defaultValue;

    /**
     * Validation pattern (regex) for text inputs.
     */
    private String pattern;

    /**
     * Error message to show when pattern validation fails.
     */
    private String patternError;

    /**
     * Minimum value for number inputs.
     */
    private Number min;

    /**
     * Maximum value for number inputs.
     */
    private Number max;

    /**
     * Step value for number/slider inputs.
     */
    private Number step;

    /**
     * Minimum length for text inputs.
     */
    private Integer minLength;

    /**
     * Maximum length for text inputs.
     */
    private Integer maxLength;

    /**
     * Options for SELECT and MULTI_SELECT fields.
     * Each option should have 'value' and 'label' keys.
     */
    private List<SelectOption> options;

    /**
     * For FILE type: accepted file types (MIME types or extensions).
     * Examples: ".pdf,.doc,.docx" or "application/pdf,text/*"
     */
    private String accept;

    /**
     * For FILE type: whether to allow multiple file selection.
     */
    @Builder.Default
    private boolean multiple = false;

    /**
     * Display order within the form (lower = higher priority).
     */
    @Builder.Default
    private int order = 100;

    /**
     * Group/section this field belongs to.
     * Used to organize fields in collapsible sections.
     */
    private String group;

    /**
     * Conditions for showing this field.
     * Map of field ID -> expected value for conditional display.
     */
    private Map<String, Object> showWhen;

    /**
     * Additional attributes to pass to the input element.
     */
    private Map<String, Object> attributes;

    /**
     * Material icon to show as a prefix in the input.
     */
    private String prefixIcon;

    /**
     * Material icon to show as a suffix in the input.
     */
    private String suffixIcon;

    /**
     * Select option for dropdown fields.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SelectOption {
        private String value;
        private String label;
        private String description;
        private boolean disabled;
    }

    // Static factory methods for common field types

    public static SourceFormField text(String id, String label) {
        return SourceFormField.builder()
                .id(id)
                .label(label)
                .type(FieldType.TEXT)
                .build();
    }

    public static SourceFormField requiredText(String id, String label) {
        return SourceFormField.builder()
                .id(id)
                .label(label)
                .type(FieldType.TEXT)
                .required(true)
                .build();
    }

    public static SourceFormField url(String id, String label) {
        return SourceFormField.builder()
                .id(id)
                .label(label)
                .type(FieldType.URL)
                .pattern("^(https?|ftp)://[^\\s/$.?#].[^\\s]*$")
                .patternError("Please enter a valid URL")
                .prefixIcon("link")
                .build();
    }

    public static SourceFormField password(String id, String label) {
        return SourceFormField.builder()
                .id(id)
                .label(label)
                .type(FieldType.PASSWORD)
                .prefixIcon("key")
                .build();
    }

    public static SourceFormField number(String id, String label, Number defaultValue) {
        return SourceFormField.builder()
                .id(id)
                .label(label)
                .type(FieldType.NUMBER)
                .defaultValue(defaultValue)
                .build();
    }

    public static SourceFormField checkbox(String id, String label, boolean defaultValue) {
        return SourceFormField.builder()
                .id(id)
                .label(label)
                .type(FieldType.CHECKBOX)
                .defaultValue(defaultValue)
                .build();
    }

    public static SourceFormField toggle(String id, String label, boolean defaultValue) {
        return SourceFormField.builder()
                .id(id)
                .label(label)
                .type(FieldType.TOGGLE)
                .defaultValue(defaultValue)
                .build();
    }

    public static SourceFormField select(String id, String label, List<SelectOption> options) {
        return SourceFormField.builder()
                .id(id)
                .label(label)
                .type(FieldType.SELECT)
                .options(options)
                .build();
    }

    public static SourceFormField file(String id, String label, String accept, boolean multiple) {
        return SourceFormField.builder()
                .id(id)
                .label(label)
                .type(FieldType.FILE)
                .accept(accept)
                .multiple(multiple)
                .build();
    }
}
