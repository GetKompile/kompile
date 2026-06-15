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

package ai.kompile.core.graphrag.reporting;

import ai.kompile.core.graphrag.model.Graph;
import java.io.OutputStream;

/**
 * A service for generating various types of reports from a knowledge graph.
 */
public interface Reporter {

    /**
     * Generates a summary report of the entire graph and writes it to the given output stream.
     *
     * @param graph The graph to report on.
     * @param output The output stream to write the report to.
     */
    void generateGraphSummaryReport(Graph graph, OutputStream output);

    /**
     * Generates a detailed report for each community in the graph and writes it to the given output stream.
     *
     * @param graph The graph to report on.
     * @param output The output stream to write the report to.
     */
    void generateCommunityReports(Graph graph, OutputStream output);

    /**
     * Generates a report of all entities and their relationships and writes it to the given output stream.
     *
     * @param graph The graph to report on.
     * @param output The output stream to write the report to.
     */
    void generateEntityRelationshipReport(Graph graph, OutputStream output);
}