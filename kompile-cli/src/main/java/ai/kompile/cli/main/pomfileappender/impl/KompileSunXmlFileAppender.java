/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.cli.main.pomfileappender.impl; // Assuming package remains

import ai.kompile.cli.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

/**
 * GraalVM native image configuration appender for internal Sun/JDK XML processing classes.
 * These are often needed for XML parsing/serialization and are generally initialized at build time.
 */
public class KompileSunXmlFileAppender implements PomFileAppender { // Renamed class

    @Override
    public DependencyType dependencyType() {
        // Using the existing SUN_XML type from the PomFileAppender interface.
        // This type remains relevant as it refers to JDK-internal XML classes.
        return DependencyType.SUN_XML;
    }

    @Override
    public List<String> classesToAppend() {
        // This list is from the original SunXmlFileAppender.
        // These are internal JDK classes for XML processing. Their names and packages
        // are part of the JDK itself and are not affected by Kompile vs. Konduit changes.
        // They are often required for robust XML handling in native images.
        return Arrays.asList(
                "com.sun.org.apache.xerces.internal.impl.dtd.XMLNSDTDValidator",
                "com.sun.org.apache.xerces.internal.impl.XMLEntityManager",
                "com.sun.org.apache.xerces.internal.impl.XMLEntityScanner",
                "com.sun.org.apache.xerces.internal.impl.XMLDocumentFragmentScannerImpl",
                "com.sun.org.apache.xerces.internal.impl.XMLScanner",
                "com.sun.org.apache.xerces.internal.util.FeatureState",
                // "jdk.xml.internal.JdkXmlUtils", // This class might be in a module not accessible by default
                // or its functionality covered by others. Test if needed.
                "com.sun.org.apache.xerces.internal.impl.XMLVersionDetector",
                "com.sun.org.apache.xerces.internal.xni.NamespaceContext",
                "com.sun.org.apache.xerces.internal.impl.XMLNSDocumentScannerImpl",
                "com.sun.xml.internal.stream.util.ThreadLocalBufferAllocator", // Corrected, was com.sun.xml.internal
                "com.sun.org.apache.xerces.internal.util.XMLChar",
                "com.sun.org.apache.xerces.internal.impl.XMLEntityManager$EncodingInfo",
                "com.sun.org.apache.xerces.internal.impl.XMLDTDScannerImpl",
                "com.sun.org.apache.xerces.internal.impl.dtd.XMLDTDProcessor",
                "com.sun.org.apache.xerces.internal.impl.dv.dtd.DTDDVFactoryImpl",
                "com.sun.org.apache.xerces.internal.impl.XMLDocumentScannerImpl",
                "com.sun.org.apache.xerces.internal.impl.dtd.XMLDTDValidator", // Duplicate of the one above, but harmless
                "com.sun.org.apache.xerces.internal.impl.Constants",
                // "jdk.xml.internal.SecuritySupport", // Similar to JdkXmlUtils, check accessibility
                "javax.xml.parsers.FactoryFinder", // Standard JAXP API
                "com.sun.org.apache.xerces.internal.util.XMLSymbols",
                "com.sun.org.apache.xerces.internal.util.PropertyState"
                // "jdk.xml.internal.JdkXmlUtils" // Duplicate
        );
    }

    @Override
    public InitializeType initializeType() {
        // These JDK-internal XML classes are typically safe and often necessary
        // to initialize at build time for GraalVM to correctly see their usage.
        return InitializeType.BUILD_TIME;
    }

    @Override
    public boolean isNative() {
        // These are part of the JDK's Java implementation of XML, not direct JNI.
        return false;
    }
}
