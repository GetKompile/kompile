package ai.kompile.pipelines.framework.api;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.Serializable;

/**
 * A marker interface for configuration objects.
 * Implementations are typically POJOs or classes that hold configuration
 * parameters and are serializable to/from JSON/YAML.
 *
 * {@code @JsonTypeInfo} is added to assist Jackson with polymorphism if
 * configurations are used in lists or generic types where the concrete
 * class needs to be identified during deserialization.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public interface Configuration extends Serializable {
    // Marker interface
}