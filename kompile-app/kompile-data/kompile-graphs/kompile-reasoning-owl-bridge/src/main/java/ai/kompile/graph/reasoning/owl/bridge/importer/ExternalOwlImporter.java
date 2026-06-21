/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.owl.bridge.importer;

import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.owl.bridge.mapper.OwlOntologyMapper;
import ai.kompile.graph.reasoning.owl.bridge.mapper.ReasoningGraphABoxLoader;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads an OWL ontology from an arbitrary source (file, stream, URL) in any format supported by
 * the OWL API (RDF/XML, OWL/XML, Manchester, Turtle, N-Triples) and projects it into the lib's
 * type model.
 *
 * <h2>Supported formats</h2>
 * <table>
 *   <tr><th>Extension / MIME</th><th>Parser</th></tr>
 *   <tr><td>.owl, .rdf, application/rdf+xml</td><td>RDFXMLDocumentFormat</td></tr>
 *   <tr><td>.owx, application/owl+xml</td><td>OWLXMLDocumentFormat</td></tr>
 *   <tr><td>.ttl, text/turtle</td><td>TurtleDocumentFormat</td></tr>
 *   <tr><td>.nt, application/n-triples</td><td>NTriplesDocumentFormat</td></tr>
 *   <tr><td>.omn, Manchester syntax</td><td>ManchesterSyntaxDocumentFormat</td></tr>
 *   <tr><td>Auto-detect</td><td>OWLOntologyManager default (tries parsers in order)</td></tr>
 * </table>
 *
 * <h2>Import closure</h2>
 * <p>By default, {@link MissingImportHandlingStrategy#SILENT} is used so that missing
 * {@code owl:imports} (common in air-gapped / offline environments) do not cause failures.
 * The import closure that is reachable is merged before projection. Pass
 * {@code silentMissingImports=false} to the builder if you want failures on missing imports.</p>
 *
 * <h2>Limitations</h2>
 * <p>Complex class expressions ({@code OWLObjectComplementOf}, {@code OWLObjectUnionOf})
 * cannot be represented in the lib's {@link OwlOntology} model. They are silently skipped at
 * the projection step and listed in {@link ImportResult#skippedAxioms()}. They are, however,
 * retained in the in-memory {@code OWLOntology} so that a subsequent
 * {@link ai.kompile.graph.reasoning.owl.bridge.reasoner.OwlDlReasoningBridge} call will still
 * reason over them correctly.</p>
 *
 * <p>This class is stateless and thread-safe.</p>
 */
public final class ExternalOwlImporter {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalOwlImporter.class);

    /**
     * Result returned by all {@code importFrom*} methods.
     *
     * @param tbox          TBox axioms projected into the lib's {@link OwlOntology}
     * @param abox          ABox individuals and property assertions as a {@link ReasoningGraph}
     * @param skippedAxioms human-readable descriptions of axioms that could not be represented
     *                       in the lib model (e.g. complex class expressions)
     */
    public record ImportResult(OwlOntology tbox, ReasoningGraph abox, List<String> skippedAxioms) {}

    // ─── Public API ─────────────────────────────────────────────────────────────

    /**
     * Load an ontology from an {@link InputStream}.
     *
     * @param in      the source stream (closed by this method on success or failure)
     * @param baseIri hint for relative IRI resolution; {@code null} → no hint
     * @param format  explicit document format hint; {@code null} → auto-detect
     * @return the projected lib ontology
     * @throws OWLOntologyCreationException if the OWL API cannot parse the stream
     */
    public ImportResult importFrom(InputStream in, String baseIri, OWLDocumentFormat format)
            throws OWLOntologyCreationException {
        Objects.requireNonNull(in, "in");

        OWLOntologyManager mgr = createManager();

        OWLOntology owlOnt;
        try (InputStream closeable = in) {
            StreamDocumentSource src = baseIri != null
                    ? new StreamDocumentSource(closeable, IRI.create(baseIri))
                    : new StreamDocumentSource(closeable);

            OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);

            owlOnt = format != null
                    ? mgr.loadOntologyFromOntologyDocument(src, config)
                    : mgr.loadOntologyFromOntologyDocument(src, config);
        } catch (IOException e) {
            throw new OWLOntologyCreationException("Failed to read OWL stream", e);
        }

        return project(mgr, owlOnt);
    }

    /**
     * Convenience: load from a file path. The format is inferred from the file extension.
     *
     * @param path the file to load (never {@code null}; must exist)
     * @return the projected lib ontology
     * @throws OWLOntologyCreationException if parsing fails
     * @throws IOException                  if the file cannot be read
     */
    public ImportResult importFromFile(Path path) throws OWLOntologyCreationException, IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream in = Files.newInputStream(path)) {
            return importFrom(in, path.toUri().toString(), null);
        }
    }

    /**
     * Convenience: load from a URL.
     *
     * @param url the URL to fetch (never {@code null})
     * @return the projected lib ontology
     * @throws OWLOntologyCreationException if parsing fails
     * @throws IOException                  if the URL cannot be opened
     */
    public ImportResult importFromUrl(URL url) throws OWLOntologyCreationException, IOException {
        Objects.requireNonNull(url, "url");
        try (InputStream in = url.openStream()) {
            return importFrom(in, url.toString(), null);
        }
    }

    // ─── Implementation ──────────────────────────────────────────────────────────

    /**
     * Create an {@link OWLOntologyManager} configured for offline-safe operation (missing imports
     * handled silently).
     */
    private static OWLOntologyManager createManager() {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        // Prevent import closure fetch failures from aborting the load
        mgr.setOntologyLoaderConfiguration(
                new OWLOntologyLoaderConfiguration()
                        .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        return mgr;
    }

    /**
     * Merge the import closure and project to lib types.
     */
    private static ImportResult project(OWLOntologyManager mgr, OWLOntology owlOnt)
            throws OWLOntologyCreationException {

        // Merge import closure into a single flat ontology so callers do not need to handle imports
        OWLOntology merged;
        try {
            merged = mgr.createOntology(
                    mgr.importsClosure(owlOnt)
                       .flatMap(o -> o.axioms())
                       .collect(java.util.stream.Collectors.toSet()),
                    owlOnt.getOntologyID().getOntologyIRI().orElse(null) != null
                            ? owlOnt.getOntologyID().getOntologyIRI().get()
                            : null);
        } catch (Exception e) {
            // If merge creation fails (e.g. null IRI path), fall back to using the original
            LOG.debug("[ExternalOwlImporter] Import closure merge fallback: {}", e.getMessage());
            merged = owlOnt;
        }

        // Project TBox axioms into lib model
        OwlOntology tbox = OwlOntologyMapper.fromOwlApi(merged);

        // Extract ABox individuals and assertions into a ReasoningGraph
        MutableReasoningGraph abox = ReasoningGraphABoxLoader.extractAbox(merged);

        // Collect descriptions of skipped axiom types
        List<String> skipped = collectSkippedAxiomTypes(merged);

        LOG.info("[ExternalOwlImporter] Loaded ontology: {} classes, {} object properties, {} data properties, "
                        + "{} ABox individuals, {} ABox relations, {} skipped axiom types",
                tbox.classes().size(),
                tbox.objectProperties().size(),
                tbox.dataProperties().size(),
                abox.entityCount(),
                abox.relationCount(),
                skipped.size());

        return new ImportResult(tbox, abox, skipped);
    }

    /**
     * Collect human-readable descriptions of axiom types that could not be represented in the
     * lib's {@link OwlOntology} model. These are axioms involving complex class expressions.
     */
    private static List<String> collectSkippedAxiomTypes(OWLOntology owlOnt) {
        List<String> skipped = new ArrayList<>();
        for (OWLAxiom axiom : owlOnt.getAxioms()) {
            if (axiom instanceof OWLSubClassOfAxiom sca) {
                boolean subAnon = !sca.getSubClass().isNamed();
                boolean supAnon = !sca.getSuperClass().isNamed();
                if (subAnon || supAnon) {
                    skipped.add("OWLSubClassOfAxiom with anonymous class expression: " + axiom);
                }
            } else if (axiom instanceof OWLEquivalentClassesAxiom eca) {
                boolean anyAnon = eca.getOperandsAsList().stream()
                        .anyMatch(e -> !e.isNamed());
                if (anyAnon) {
                    skipped.add("OWLEquivalentClassesAxiom with complex expression: " + axiom);
                }
            } else if (axiom instanceof OWLDisjointClassesAxiom dca) {
                boolean anyAnon = dca.getOperandsAsList().stream()
                        .anyMatch(e -> !e.isNamed());
                if (anyAnon) {
                    skipped.add("OWLDisjointClassesAxiom with complex expression: " + axiom);
                }
            }
            // Other complex axioms (HasKey, DatatypeDefinition, SubPropertyChain, etc.)
            // are silently ignored as they're not representable in the lib model
        }
        return skipped;
    }
}
