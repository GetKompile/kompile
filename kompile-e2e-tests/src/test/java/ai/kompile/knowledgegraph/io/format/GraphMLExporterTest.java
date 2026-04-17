/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMLExporterTest {

    @Test
    void emitsValidXmlWithNodesAndEdges() throws Exception {
        PortableGraph graph = new PortableGraph(
                List.of(
                        new PortableNode("n1", "Alpha", "first node", "PERSON", null),
                        new PortableNode("n2", "Beta", null, "PERSON", null)
                ),
                List.of(new PortableEdge("n1", "n2", "KNOWS", 1.5, null)));
        byte[] bytes = new GraphMLExporter().toBytes(graph);

        Document doc = parse(bytes);
        Element root = doc.getDocumentElement();
        assertEquals("graphml", root.getTagName());

        NodeList graphElems = root.getElementsByTagName("graph");
        assertEquals(1, graphElems.getLength());
        assertEquals("directed", ((Element) graphElems.item(0)).getAttribute("edgedefault"));

        NodeList nodes = root.getElementsByTagName("node");
        assertEquals(2, nodes.getLength());
        assertEquals("n1", ((Element) nodes.item(0)).getAttribute("id"));

        NodeList edges = root.getElementsByTagName("edge");
        assertEquals(1, edges.getLength());
        Element edge = (Element) edges.item(0);
        assertEquals("n1", edge.getAttribute("source"));
        assertEquals("n2", edge.getAttribute("target"));
    }

    @Test
    void declaresExpectedKeys() throws Exception {
        byte[] bytes = new GraphMLExporter().toBytes(PortableGraph.empty());
        Document doc = parse(bytes);
        NodeList keys = doc.getDocumentElement().getElementsByTagName("key");
        assertTrue(keys.getLength() >= 5, "Expected at least 5 key declarations, got " + keys.getLength());
    }

    @Test
    void escapesSpecialCharactersInAttributes() throws Exception {
        PortableGraph graph = new PortableGraph(
                List.of(new PortableNode("n<1>", "Title & Co.", "<dangerous>", "ENTITY", null)),
                List.of());
        byte[] bytes = new GraphMLExporter().toBytes(graph);
        Document doc = parse(bytes);
        Element node = (Element) doc.getDocumentElement().getElementsByTagName("node").item(0);
        // External id is escaped at serialization but read back through DOM as the raw value.
        assertEquals("n<1>", node.getAttribute("id"));
        NodeList dataElems = node.getElementsByTagName("data");
        assertNotNull(dataElems.item(0));
        // Title appears in the data element and round-trips through DOM unescape.
        boolean foundTitle = false;
        for (int i = 0; i < dataElems.getLength(); i++) {
            Element data = (Element) dataElems.item(i);
            if ("title".equals(data.getAttribute("key"))) {
                assertEquals("Title & Co.", data.getTextContent());
                foundTitle = true;
            }
        }
        assertTrue(foundTitle, "Expected a title data element");
    }

    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(bytes));
    }
}
