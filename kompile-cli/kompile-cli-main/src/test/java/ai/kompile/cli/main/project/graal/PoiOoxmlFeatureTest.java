/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project.graal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.xmlbeans.SchemaType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiOoxmlFeatureTest {
    private static final String RESOURCE_CONFIG = "/META-INF/native-image/ai.kompile/kompile-cli/resource-config.json";

    @Test
    void scansTheSchemaTypesXmlBeansResolvesByName() throws Exception {
        Set<String> names = Set.copyOf(PoiOoxmlFeature.schemaClassNames(
                Class.forName(PoiOoxmlFeature.OOXML_SCHEMA_ANCHOR), Class.forName(PoiOoxmlFeature.XMLBEANS_ANCHOR)));

        for (String expected : List.of(
                "org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbook",
                "org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.CTWorkbookImpl",
                "org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType$Enum",
                // Charts, comments and legacy drawings in richer workbooks.
                "org.openxmlformats.schemas.drawingml.x2006.chart.impl.CTChartSpaceImpl",
                "com.microsoft.schemas.vml.impl.CTShapeImpl",
                "org.apache.poi.schemas.vmldrawing.impl.XmlDocumentImpl",
                // XMLBeans' built-in type systems and types.
                "org.apache.xmlbeans.metadata.system.sXMLLANG.TypeSystemHolder",
                "org.apache.xmlbeans.XmlString")) {
            assertTrue(names.contains(expected), () -> "missing " + expected);
        }
        assertFalse(names.contains("org.apache.xmlbeans.impl.values.XmlObjectBase"),
                "XMLBeans runtime classes are not schema types");
        // poi-ooxml-lite strips this enclosing type but keeps its $Enum, which can never resolve.
        assertFalse(names.contains("com.microsoft.schemas.office.word.STVerticalAnchor$Enum"));
        for (String name : names) {
            if (name.indexOf('$') > 0) {
                String enclosing = name.substring(0, name.lastIndexOf('$'));
                assertTrue(names.contains(enclosing), () -> name + " registered without " + enclosing);
            }
        }
    }

    @Test
    void schemaTypesExposeTheMembersXmlBeansReflectsOn() throws Exception {
        String main = "org.openxmlformats.schemas.spreadsheetml.x2006.main.";
        Class.forName(main + "impl.CTWorkbookImpl").getDeclaredConstructor(SchemaType.class);
        Class.forName(main + "impl.STCellTypeImpl").getDeclaredConstructor(SchemaType.class, boolean.class);
        Class.forName(main + "STCellType$Enum").getDeclaredField("table");
        Class.forName(main + "CTWorkbook").getDeclaredField("type");
        Class.forName("org.apache.xmlbeans.metadata.system.sXMLLANG.TypeSystemHolder").getDeclaredField("typeSystem");
    }

    @Test
    void resourceConfigCarriesTheSchemaMetadataAndMessageBundles() throws Exception {
        JsonNode config;
        try (InputStream input = getClass().getResourceAsStream(RESOURCE_CONFIG)) {
            assertNotNull(input, "missing native-image resource configuration");
            config = new ObjectMapper().readTree(input);
        }
        List<Pattern> includes = new ArrayList<>();
        config.path("resources").path("includes").forEach(entry -> includes.add(Pattern.compile(entry.path("pattern").asText())));

        for (String resource : List.of(
                "org/apache/poi/schemas/ooxml/system/ooxml/index.xsb",
                "org/apache/xmlbeans/metadata/system/sXMLLANG/index.xsb",
                "org/apache/poi/ss/formula/function/functionMetadata.txt")) {
            assertNotNull(getClass().getClassLoader().getResource(resource), () -> resource + " is not on the classpath");
            assertTrue(includes.stream().anyMatch(pattern -> pattern.matcher(resource).matches()),
                    () -> resource + " is not included in the native image");
        }

        List<String> bundles = new ArrayList<>();
        config.path("bundles").forEach(entry -> bundles.add(entry.path("name").asText()));
        for (String bundle : List.of("org.apache.xmlbeans.impl.regex.message", "org.apache.xmlbeans.message")) {
            assertTrue(bundles.contains(bundle), () -> bundle + " is not included in the native image");
            assertNotNull(ResourceBundle.getBundle(bundle));
        }
    }

    @Test
    void nativeImagePinsTheLog4jBindingPoiLogsThrough() throws Exception {
        // POI logs through log4j-api. Without this file LogManager enumerates providers, fails to
        // construct log4j-core's unregistered context factory, and prints the error to stderr.
        String resource = "log4j2.component.properties";
        Properties log4j = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, () -> resource + " is not on the classpath");
            log4j.load(input);
        }
        assertEquals("org.apache.logging.slf4j.SLF4JLoggerContextFactory", log4j.getProperty("log4j2.loggerContextFactory"));

        JsonNode config;
        try (InputStream input = getClass().getResourceAsStream(RESOURCE_CONFIG)) {
            config = new ObjectMapper().readTree(input);
        }
        boolean included = false;
        for (JsonNode entry : config.path("resources").path("includes")) {
            included |= Pattern.compile(entry.path("pattern").asText()).matcher(resource).matches();
        }
        assertTrue(included, () -> resource + " is not included in the native image");
    }
}
