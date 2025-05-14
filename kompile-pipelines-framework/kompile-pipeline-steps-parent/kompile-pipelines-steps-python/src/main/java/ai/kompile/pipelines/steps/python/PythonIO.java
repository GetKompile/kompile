package ai.kompile.pipelines.steps.python;

import ai.kompile.pipelines.framework.api.data.ValueType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor; // Added for class-level builder
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(fluent = true)
@NoArgsConstructor
@AllArgsConstructor // Ensures an all-args constructor for the builder
@Builder // Moved to class level
@Schema(description = "Python base")
public class PythonIO implements Serializable {

    private String name;
    private String pythonType;

    @Builder.Default // For class-level builder to respect default
    private ValueType secondaryType = ValueType.NONE;
    @Builder.Default // For class-level builder to respect default
    private ValueType type = ValueType.NONE;

    // Custom constructor that was previously annotated with @Builder is no longer needed
    // if @AllArgsConstructor and class-level @Builder with @Builder.Default are used.
    // Lombok's @AllArgsConstructor will handle creating the constructor that @Builder will use.
    // The validate() call could be done in a private constructor called by Lombok if needed,
    // or by ensuring fields are initialized correctly by the builder/constructors.
    // For simplicity, relying on @Builder.Default and direct field initialization.

    public boolean isDictWithType() {
        if(type == null)
            return false;
        return type == ValueType.BOUNDING_BOX
                || type == ValueType.POINT
                && secondaryType != ValueType.NONE;
    }

    public boolean isDictWithUndefinedType() {
        return !isDictWithType();
    }

    public boolean isListWithType() {
        if(type == null)
            return false;
        return type == ValueType.LIST && secondaryType != ValueType.NONE;
    }

    /**
     * Returns true if this is a list type
     * with no secondary type defined (or explicitly NONE).
     * @return boolean
     */
    public boolean isListWithUndefinedType() {
        if(type == null)
            return false;
        // Original logic: return !isListWithType();
        // This could be true if type is LIST and secondaryType is NONE.
        // Or if type is not LIST at all.
        // A more precise check if it's a list *meant* to have an undefined element type:
        return type == ValueType.LIST && (secondaryType == ValueType.NONE || secondaryType == null);
    }

    private void validate() {
        // If specific validations are needed upon construction, they could be added here
        // or handled by constraints if using bean validation, or within setters if not using fluent accessors.
    }
}