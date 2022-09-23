/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.pomfileappender.impl;

import ai.konduit.pipelinegenerator.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

public class Nd4jJacksonAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.ND4J_JACKSON;
    }

    @Override
    public List<String> classesToAppend() {
        return Arrays.asList(
                "org.nd4j.shade.jackson.databind.ObjectMapper",
                "org.nd4j.shade.jackson.databind.deser.std.StdDeserializer",
                "org.nd4j.shade.jackson.databind.type.TypeFactory",
                "org.nd4j.shade.jackson.databind.cfg.MapperConfigBase",
                "org.nd4j.shade.jackson.databind.cfg.MapperConfig",
                "org.nd4j.shade.jackson.databind.util.ClassUtil",
                "org.nd4j.shade.jackson.core.JsonFactory",
                "org.nd4j.shade.jackson.dataformat.yaml.YAMLFactory",
                "org.nd4j.shade.jackson.databind.deser.BeanDeserializerFactory",
                "org.nd4j.shade.jackson.databind.DeserializationConfig",
                "oorg.nd4j.shade.jackson.annotation.JsonSetter$Value",
                "org.nd4j.shade.jackson.datatype.joda.deser.key.JodaKeyDeserializer",
                "org.nd4j.shade.jackson.databind.type.TypeBindings ",
                "org.nd4j.shade.jackson.datatype.joda.deser.key.PeriodKeyDeserializer",
                "org.nd4j.shade.jackson.datatype.joda.cfg.JacksonJodaPeriodFormat",
                "org.nd4j.shade.jackson.datatype.joda.deser.key.LocalTimeKeyDeserializer",
                "org.nd4j.shade.jackson.databind.introspect.AnnotatedClassResolver",
                "org.nd4j.shade.jackson.core.io.CharTypes",
                "org.nd4j.shade.jackson.datatype.joda.cfg.JacksonJodaFormatBase",
                "org.nd4j.shade.jackson.annotation.JsonSetter$Value ",
                "org.nd4j.shade.jackson.databind.SerializationConfig",
                "org.nd4j.shade.jackson.databind.type.TypeBase",
                "org.nd4j.shade.jackson.core.Base64Variants",
                "4j.shade.jackson.databind.cfg.BaseSettings",
                "org.nd4j.shade.jackson.databind.introspect.AnnotatedClass",
                "org.nd4j.shade.jackson.databind.cfg.ContextAttributes$Impl",
                "org.nd4j.shade.jackson.databind.introspect.AnnotatedClass",
                "org.nd4j.shade.jackson.datatype.joda.cfg.JacksonJodaFormatBase ",
                "org.nd4j.shade.jackson.databind.introspect.AnnotatedClass",
                "org.nd4j.shade.jackson.databind.ser.BasicSerializerFactory ",
                "org.nd4j.shade.jackson.databind.introspect.AnnotatedClass",
                "org.nd4j.shade.jackson.databind.SerializationConfig ",
                "org.nd4j.shade.jackson.databind.cfg.ContextAttributes$Impl",
                "org.nd4j.shade.jackson.annotation.JsonFormat$Value",
                "org.nd4j.shade.jackson.databind.cfg.BaseSettings",
                "org.nd4j.shade.jackson.core.util.DefaultIndenter",
                "org.nd4j.shade.jackson.databind.cfg.CoercionConfigs",
                "org.nd4j.shade.jackson.core.io.SerializedString",
                "org.nd4j.shade.jackson.databind.introspect.VisibilityChecker$Std",
                "org.nd4j.shade.jackson.core.util.VersionUtil",
                "org.nd4j.shade.jackson.databind.util.StdDateFormat",
                "org.nd4j.shade.jackson.datatype.joda.deser.key.LocalDateKeyDeserializer",
                "org.nd4j.shade.jackson.databind.ser.BeanSerializerFactory",
                "org.nd4j.shade.jackson.datatype.joda.deser.key.LocalDateTimeKeyDeserializer",
                "org.nd4j.shade.jackson.annotation.JsonInclude$Value",
                "org.nd4j.shade.jackson.databind.cfg.MutableCoercionConfig",
                "org.nd4j.shade.jackson.databind.ser.std.NumberSerializers$IntLikeSerializer",
                "org.nd4j.shade.jackson.databind.introspect.JacksonAnnotationIntrospector",
                "org.nd4j.shade.jackson.databind.cfg.CoercionConfig",
                "org.nd4j.shade.jackson.databind.introspect.BasicClassIntrospector",
                "org.nd4j.shade.jackson.databind.ser.std.NumberSerializers$ShortSerializer",
                "org.nd4j.shade.jackson.databind.ser.std.NumberSerializers$FloatSerializer",
                "org.nd4j.shade.jackson.databind.ser.std.UUIDSerializer",
                "org.nd4j.shade.jackson.databind.ext.Java7SupportImpl",
                "org.nd4j.shade.jackson.dataformat.yaml.util.StringQuotingChecker$Default",
                "org.nd4j.shade.jackson.core.io.JsonStringEncoder",
                "org.nd4j.shade.jackson.databind.introspect.VisibilityChecker$1 ",
                "org.nd4j.shade.jackson.datatype.joda.cfg.FormatConfig",
                "org.nd4j.shade.jackson.datatype.joda.PackageVersion",
                "org.nd4j.shade.jackson.databind.node.TreeTraversingParser$1",
                "org.nd4j.shade.jackson.annotation.JsonIgnoreProperties$Value",
                "org.nd4j.shade.jackson.databind.introspect.POJOPropertyBuilder$6",
                "org.nd4j.shade.jackson.databind.deser.BeanDeserializer$1",
                "org.nd4j.shade.jackson.databind.jsontype.impl.SubTypeValidator",
                "org.nd4j.shade.jackson.databind.deser.std.NumberDeserializers",
                "org.nd4j.shade.jackson.databind.deser.impl.NullsConstantProvider",
                "org.nd4j.shade.jackson.core.util.BufferRecyclers",
                "org.nd4j.shade.jackson.databind.node.TreeTraversingParser",
                "org.nd4j.shade.jackson.databind.deser.std.NumberDeserializers$BooleanDeserializer",
                "org.nd4j.shade.jackson.databind.cfg.ConstructorDetector",
                "org.nd4j.shade.jackson.databind.jsontype.impl.StdTypeResolverBuilder$1",
                "org.nd4j.shade.jackson.databind.deser.std.JdkDeserializers",
                "org.nd4j.shade.jackson.core.base.ParserBase",
                "org.nd4j.shade.jackson.databind.introspect.BeanPropertyDefinition",
                "org.nd4j.shade.jackson.databind.introspect.POJOPropertyBuilder",
                "org.nd4j.shade.jackson.core.json.ReaderBasedJsonParser",
                "org.nd4j.shade.jackson.annotation.JsonAutoDetect$1",
                "org.nd4j.shade.jackson.databind.ext.OptionalHandlerFactory",
                "org.nd4j.shade.jackson.databind.deser.BasicDeserializerFactory$ContainerDefaultMappings",
                "org.nd4j.shade.jackson.core.base.ParserMinimalBase",
                "org.nd4j.shade.jackson.databind.type.TypeBindings$TypeParamStash",
                "org.nd4j.shade.jackson.databind.PropertyMetadata",
                "org.nd4j.shade.jackson.core.JsonParser",
                "org.nd4j.shade.jackson.databind.ext.Java7HandlersImpl",
                "org.nd4j.shade.jackson.databind.ext.Java7Handlers"


        );
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.BUILD_TIME;
    }
}
