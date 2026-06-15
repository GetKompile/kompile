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

package ai.kompile.cli.main.pomfileappender.impl;

import ai.kompile.cli.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

/**
 * GraalVM native image configuration appender for ND4J's shaded Jackson components.
 * Jackson makes heavy use of reflection, so many classes need to be initialized
 * at build time to allow GraalVM to see the reflective calls.
 */
public class KompileNd4jJacksonPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.ND4J_CORE; // Or a more specific ND4J_JACKSON if kept
    }

    @Override
    public List<String> classesToAppend() {
        // The original list is very long. This indicates deep reflection use.
        // It's crucial that these are correct. Any changes in ND4J's shading
        // or Jackson's internals could require updates here.
        // Package-level initialization "org.eclipse.deeplearning4j.shade.jackson.*" might be too broad
        // and pull in too much, or miss specific nested classes if not careful.
        // The explicit list is safer if maintained.
        return Arrays.asList(
                // Core ObjectMapper and factories
                "org.eclipse.deeplearning4j.shade.jackson.databind.ObjectMapper",
                "org.eclipse.deeplearning4j.shade.jackson.core.JsonFactory",
                "org.eclipse.deeplearning4j.shade.jackson.dataformat.yaml.YAMLFactory", // If YAML is used with ND4J configs

                // Core configuration objects
                "org.eclipse.deeplearning4j.shade.jackson.databind.SerializationConfig",
                "org.eclipse.deeplearning4j.shade.jackson.databind.DeserializationConfig",
                "org.eclipse.deeplearning4j.shade.jackson.databind.cfg.MapperConfig",
                "org.eclipse.deeplearning4j.shade.jackson.databind.cfg.MapperConfigBase",
                "org.eclipse.deeplearning4j.shade.jackson.databind.cfg.BaseSettings",
                "org.eclipse.deeplearning4j.shade.jackson.databind.cfg.ContextAttributes$Impl",
                "org.eclipse.deeplearning4j.shade.jackson.databind.cfg.ConstructorDetector",
                "org.eclipse.deeplearning4j.shade.jackson.databind.cfg.CoercionConfigs",
                "org.eclipse.deeplearning4j.shade.jackson.databind.cfg.MutableCoercionConfig",
                "org.eclipse.deeplearning4j.shade.jackson.databind.cfg.CoercionConfig",


                // Introspection and Annotations
                "org.eclipse.deeplearning4j.shade.jackson.databind.introspect.VisibilityChecker$Std",
                "org.eclipse.deeplearning4j.shade.jackson.databind.introspect.VisibilityChecker$1",
                "org.eclipse.deeplearning4j.shade.jackson.databind.introspect.JacksonAnnotationIntrospector",
                "org.eclipse.deeplearning4j.shade.jackson.databind.introspect.BasicClassIntrospector",
                "org.eclipse.deeplearning4j.shade.jackson.databind.introspect.AnnotatedClass",
                "org.eclipse.deeplearning4j.shade.jackson.databind.introspect.AnnotatedClassResolver",
                "org.eclipse.deeplearning4j.shade.jackson.databind.introspect.POJOPropertyBuilder",
                "org.eclipse.deeplearning4j.shade.jackson.databind.PropertyMetadata",
                "org.eclipse.deeplearning4j.shade.jackson.annotation.JsonFormat$Value",
                "org.eclipse.deeplearning4j.shade.jackson.annotation.JsonInclude$Value",
                "org.eclipse.deeplearning4j.shade.jackson.annotation.JsonSetter$Value",
                "org.eclipse.deeplearning4j.shade.jackson.annotation.JsonIgnoreProperties$Value",
                "org.eclipse.deeplearning4j.shade.jackson.annotation.JsonAutoDetect$1",


                // Types
                "org.eclipse.deeplearning4j.shade.jackson.databind.type.TypeFactory",
                "org.eclipse.deeplearning4j.shade.jackson.databind.type.TypeBindings",
                "org.eclipse.deeplearning4j.shade.jackson.databind.type.TypeBindings$TypeParamStash",
                "org.eclipse.deeplearning4j.shade.jackson.databind.type.TypeBase",
                // Add specific TypeModifier, TypeParser if used by ND4J models

                // Serializers & Deserializers (Factories and specific ones if problematic)
                "org.eclipse.deeplearning4j.shade.jackson.databind.ser.BeanSerializerFactory",
                "org.eclipse.deeplearning4j.shade.jackson.databind.deser.BeanDeserializerFactory",
                "org.eclipse.deeplearning4j.shade.jackson.databind.deser.BasicDeserializerFactory$ContainerDefaultMappings",
                "org.eclipse.deeplearning4j.shade.jackson.databind.deser.std.StdDeserializer",
                "org.eclipse.deeplearning4j.shade.jackson.databind.deser.std.NumberDeserializers",
                "org.eclipse.deeplearning4j.shade.jackson.databind.deser.std.NumberDeserializers$BooleanDeserializer", // Example
                "org.eclipse.deeplearning4j.shade.jackson.databind.deser.std.JdkDeserializers",
                "org.eclipse.deeplearning4j.shade.jackson.databind.ser.std.NumberSerializers$IntLikeSerializer", // Example
                "org.eclipse.deeplearning4j.shade.jackson.databind.ser.std.NumberSerializers$ShortSerializer",
                "org.eclipse.deeplearning4j.shade.jackson.databind.ser.std.NumberSerializers$FloatSerializer",
                "org.eclipse.deeplearning4j.shade.jackson.databind.ser.std.UUIDSerializer",
                "org.eclipse.deeplearning4j.shade.jackson.databind.deser.BeanDeserializer$1", // Inner class
                "org.eclipse.deeplearning4j.shade.jackson.databind.deser.impl.NullsConstantProvider",


                // Core IO / Utils
                "org.eclipse.deeplearning4j.shade.jackson.core.io.CharTypes",
                "org.eclipse.deeplearning4j.shade.jackson.core.io.SerializedString",
                "org.eclipse.deeplearning4j.shade.jackson.core.io.JsonStringEncoder",
                "org.eclipse.deeplearning4j.shade.jackson.core.util.VersionUtil",
                "org.eclipse.deeplearning4j.shade.jackson.core.util.DefaultIndenter",
                "org.eclipse.deeplearning4j.shade.jackson.core.util.BufferRecyclers",
                "org.eclipse.deeplearning4j.shade.jackson.core.Base64Variants",
                "org.eclipse.deeplearning4j.shade.jackson.databind.util.StdDateFormat",
                "org.eclipse.deeplearning4j.shade.jackson.databind.util.ClassUtil",

                // Parsers
                "org.eclipse.deeplearning4j.shade.jackson.core.base.ParserBase",
                "org.eclipse.deeplearning4j.shade.jackson.core.base.ParserMinimalBase",
                "org.eclipse.deeplearning4j.shade.jackson.core.json.ReaderBasedJsonParser",
                "org.eclipse.deeplearning4j.shade.jackson.databind.node.TreeTraversingParser",
                "org.eclipse.deeplearning4j.shade.jackson.databind.node.TreeTraversingParser$1",


                // Joda time integration if still used within ND4J's Jackson shaded parts
                "org.eclipse.deeplearning4j.shade.jackson.datatype.joda.PackageVersion", // Joda module version class
                "org.eclipse.deeplearning4j.shade.jackson.datatype.joda.cfg.FormatConfig",
                "org.eclipse.deeplearning4j.shade.jackson.datatype.joda.cfg.JacksonJodaFormatBase",
                "org.eclipse.deeplearning4j.shade.jackson.datatype.joda.cfg.JacksonJodaPeriodFormat",
                "org.eclipse.deeplearning4j.shade.jackson.datatype.joda.deser.key.JodaKeyDeserializer",
                "org.eclipse.deeplearning4j.shade.jackson.datatype.joda.deser.key.LocalDateKeyDeserializer",
                "org.eclipse.deeplearning4j.shade.jackson.datatype.joda.deser.key.LocalDateTimeKeyDeserializer",
                "org.eclipse.deeplearning4j.shade.jackson.datatype.joda.deser.key.LocalTimeKeyDeserializer",
                "org.eclipse.deeplearning4j.shade.jackson.datatype.joda.deser.key.PeriodKeyDeserializer",


                // Other specific internal classes that might be reflectively accessed
                "org.eclipse.deeplearning4j.shade.jackson.databind.jsontype.impl.StdTypeResolverBuilder$1",
                "org.eclipse.deeplearning4j.shade.jackson.databind.jsontype.impl.SubTypeValidator",
                "org.eclipse.deeplearning4j.shade.jackson.databind.ext.OptionalHandlerFactory",
                "org.eclipse.deeplearning4j.shade.jackson.databind.ext.Java7SupportImpl", // If Java 7+ specific features are used
                "org.eclipse.deeplearning4j.shade.jackson.databind.ext.Java7Handlers",
                "org.eclipse.deeplearning4j.shade.jackson.databind.ext.Java7HandlersImpl",
                "org.eclipse.deeplearning4j.shade.jackson.dataformat.yaml.util.StringQuotingChecker$Default" // If YAML format is used for ND4J configs
        );
    }

    @Override
    public InitializeType initializeType() {
        // Jackson heavily uses reflection. Initializing its core components at
        // build time allows GraalVM to detect and configure many reflective accesses.
        return InitializeType.BUILD_TIME;
    }

    @Override
    public boolean isNative() {
        return false; // Jackson itself is pure Java, though it serializes native-backed ND4J objects.
    }
}
