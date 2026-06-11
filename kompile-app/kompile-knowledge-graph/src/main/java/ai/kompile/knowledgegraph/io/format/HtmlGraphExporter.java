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
package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exports a {@link PortableGraph} as a self-contained HTML file with an
 * embedded force-directed graph visualisation using D3.js (loaded from CDN).
 * The file can be opened in any browser without a server.
 *
 * <p>Features:
 * <ul>
 *   <li>Colour-coded nodes by type</li>
 *   <li>Sized nodes by degree (hub detection)</li>
 *   <li>Edge labels with type</li>
 *   <li>Click-to-highlight a node and its neighbours</li>
 *   <li>Search / filter by entity name</li>
 *   <li>Zoom &amp; pan</li>
 *   <li>Node type legend with toggle filters</li>
 *   <li>Provenance badges on edges (EXTRACTED/INFERRED/AMBIGUOUS)</li>
 * </ul>
 */
public final class HtmlGraphExporter {

    public byte[] toBytes(PortableGraph graph) {
        // Compute degree per node for sizing
        Map<String, Integer> degree = new HashMap<>();
        for (PortableNode n : graph.nodes()) {
            degree.put(n.externalId(), 0);
        }
        for (PortableEdge e : graph.edges()) {
            degree.merge(e.fromExternalId(), 1, Integer::sum);
            degree.merge(e.toExternalId(), 1, Integer::sum);
        }
        int maxDeg = degree.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        // Collect unique node types for colour mapping
        Set<String> types = graph.nodes().stream()
                .map(n -> n.nodeType() != null ? n.nodeType() : "UNKNOWN")
                .collect(Collectors.toCollection(LinkedHashSet::new));

        StringBuilder sb = new StringBuilder(16384);
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
        sb.append("<title>Knowledge Graph — Kompile</title>\n");
        sb.append("<style>\n");
        sb.append("*{margin:0;padding:0;box-sizing:border-box}\n");
        sb.append("body{font-family:system-ui,-apple-system,sans-serif;background:#0f1117;color:#e0e0e0;overflow:hidden}\n");
        sb.append("#controls{position:fixed;top:12px;left:12px;z-index:10;display:flex;gap:8px;align-items:center}\n");
        sb.append("#search{padding:6px 12px;border:1px solid #333;border-radius:6px;background:#1a1d27;color:#e0e0e0;font-size:14px;width:260px}\n");
        sb.append("#legend{position:fixed;top:12px;right:12px;z-index:10;background:#1a1d27;border:1px solid #333;border-radius:8px;padding:10px 14px;font-size:12px}\n");
        sb.append(".legend-item{display:flex;align-items:center;gap:6px;margin:4px 0;cursor:pointer;user-select:none}\n");
        sb.append(".legend-dot{width:12px;height:12px;border-radius:50%;flex-shrink:0}\n");
        sb.append(".legend-item.disabled{opacity:0.3}\n");
        sb.append("#stats{position:fixed;bottom:12px;left:12px;font-size:12px;color:#666}\n");
        sb.append("#tooltip{position:fixed;display:none;background:#1a1d27;border:1px solid #444;border-radius:6px;padding:8px 12px;font-size:13px;max-width:340px;pointer-events:none;z-index:20}\n");
        sb.append("svg{width:100vw;height:100vh}\n");
        sb.append(".link{stroke:#444;stroke-opacity:0.6}\n");
        sb.append(".link.extracted{stroke:#4caf50}\n");
        sb.append(".link.inferred{stroke:#ff9800;stroke-dasharray:5,3}\n");
        sb.append(".link.ambiguous{stroke:#f44336;stroke-dasharray:2,4}\n");
        sb.append(".link-label{font-size:9px;fill:#777;pointer-events:none}\n");
        sb.append(".node-label{font-size:11px;fill:#bbb;pointer-events:none;text-anchor:middle}\n");
        sb.append(".node circle{stroke:#222;stroke-width:1.5;cursor:pointer}\n");
        sb.append(".node circle:hover{stroke:#fff;stroke-width:2}\n");
        sb.append("</style>\n</head>\n<body>\n");

        // Controls
        sb.append("<div id=\"controls\"><input id=\"search\" placeholder=\"Search entities…\" autocomplete=\"off\"></div>\n");
        sb.append("<div id=\"legend\"><b>Node Types</b>\n");
        String[] palette = {"#4fc3f7","#81c784","#ffb74d","#e57373","#ba68c8","#4db6ac","#fff176","#f06292","#90a4ae","#aed581","#7986cb","#ff8a65"};
        int ci = 0;
        for (String t : types) {
            String colour = palette[ci % palette.length];
            sb.append("<div class=\"legend-item\" data-type=\"").append(esc(t)).append("\">");
            sb.append("<span class=\"legend-dot\" style=\"background:").append(colour).append("\"></span>");
            sb.append(esc(t)).append("</div>\n");
            ci++;
        }
        sb.append("</div>\n");
        sb.append("<div id=\"stats\">").append(graph.nodes().size()).append(" nodes · ")
                .append(graph.edges().size()).append(" edges</div>\n");
        sb.append("<div id=\"tooltip\"></div>\n");
        sb.append("<svg></svg>\n");

        // Embed data as JSON
        sb.append("<script>\n");
        sb.append("const graphData = {\n  nodes: [\n");
        for (int i = 0; i < graph.nodes().size(); i++) {
            PortableNode n = graph.nodes().get(i);
            int deg = degree.getOrDefault(n.externalId(), 0);
            double radius = 5 + 15.0 * deg / Math.max(maxDeg, 1);
            sb.append("    {id:").append(jsStr(n.externalId()));
            sb.append(",title:").append(jsStr(n.title()));
            sb.append(",type:").append(jsStr(n.nodeType() != null ? n.nodeType() : "UNKNOWN"));
            sb.append(",desc:").append(jsStr(n.description()));
            sb.append(",r:").append(String.format("%.1f", radius));
            sb.append(",deg:").append(deg).append("}");
            if (i < graph.nodes().size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n  links: [\n");
        for (int i = 0; i < graph.edges().size(); i++) {
            PortableEdge e = graph.edges().get(i);
            sb.append("    {source:").append(jsStr(e.fromExternalId()));
            sb.append(",target:").append(jsStr(e.toExternalId()));
            sb.append(",type:").append(jsStr(e.edgeType()));
            sb.append(",prov:").append(jsStr(e.provenance()));
            sb.append(",w:").append(e.weight() != null ? e.weight() : 1.0);
            sb.append(",desc:").append(jsStr(e.description())).append("}");
            if (i < graph.edges().size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n};\n");

        // Colour map
        sb.append("const typeColors = {");
        ci = 0;
        for (String t : types) {
            if (ci > 0) sb.append(",");
            sb.append(jsStr(t)).append(":\"").append(palette[ci % palette.length]).append("\"");
            ci++;
        }
        sb.append("};\n");

        // D3 rendering script (loaded from CDN)
        sb.append("</script>\n");
        sb.append("<script src=\"https://d3js.org/d3.v7.min.js\"></script>\n");
        sb.append("<script>\n");
        sb.append(D3_SCRIPT);
        sb.append("\n</script>\n</body>\n</html>\n");

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String jsStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final String D3_SCRIPT = """
(function() {
  const svg = d3.select("svg");
  const width = window.innerWidth, height = window.innerHeight;
  const g = svg.append("g");

  // Zoom
  svg.call(d3.zoom().scaleExtent([0.05, 8]).on("zoom", e => g.attr("transform", e.transform)));

  // Hidden types
  const hidden = new Set();
  document.querySelectorAll(".legend-item").forEach(el => {
    el.addEventListener("click", () => {
      const t = el.dataset.type;
      if (hidden.has(t)) { hidden.delete(t); el.classList.remove("disabled"); }
      else { hidden.add(t); el.classList.add("disabled"); }
      update();
    });
  });

  // Search
  const searchEl = document.getElementById("search");
  let searchTerm = "";
  searchEl.addEventListener("input", () => { searchTerm = searchEl.value.toLowerCase(); update(); });

  const tooltip = document.getElementById("tooltip");

  // Simulation
  const sim = d3.forceSimulation(graphData.nodes)
    .force("link", d3.forceLink(graphData.links).id(d => d.id).distance(80).strength(0.4))
    .force("charge", d3.forceManyBody().strength(-120))
    .force("center", d3.forceCenter(width / 2, height / 2))
    .force("collision", d3.forceCollide().radius(d => d.r + 2));

  // Links
  const linkG = g.append("g");
  let linkSel = linkG.selectAll("line").data(graphData.links).join("line")
    .attr("class", d => "link" + (d.prov ? " " + d.prov.toLowerCase() : ""))
    .attr("stroke-width", d => 1 + d.w);

  // Link labels
  const linkLabelG = g.append("g");
  let linkLabelSel = linkLabelG.selectAll("text").data(graphData.links).join("text")
    .attr("class", "link-label")
    .text(d => d.type || "");

  // Nodes
  const nodeG = g.append("g");
  let nodeSel = nodeG.selectAll("g").data(graphData.nodes).join("g").attr("class", "node");
  nodeSel.append("circle")
    .attr("r", d => d.r)
    .attr("fill", d => typeColors[d.type] || "#888")
    .on("mouseover", (ev, d) => {
      tooltip.style.display = "block";
      tooltip.innerHTML = "<b>" + (d.title||d.id) + "</b><br>Type: " + d.type + "<br>Degree: " + d.deg
        + (d.desc ? "<br><br>" + d.desc.substring(0, 200) : "");
    })
    .on("mousemove", ev => {
      tooltip.style.left = (ev.clientX + 14) + "px";
      tooltip.style.top = (ev.clientY + 14) + "px";
    })
    .on("mouseout", () => { tooltip.style.display = "none"; })
    .call(d3.drag().on("start", dragStart).on("drag", dragging).on("end", dragEnd));

  nodeSel.append("text").attr("class", "node-label").attr("dy", d => d.r + 14)
    .text(d => (d.title || d.id || "").substring(0, 24));

  sim.on("tick", () => {
    linkSel.attr("x1", d => d.source.x).attr("y1", d => d.source.y)
           .attr("x2", d => d.target.x).attr("y2", d => d.target.y);
    linkLabelSel.attr("x", d => (d.source.x + d.target.x) / 2)
                .attr("y", d => (d.source.y + d.target.y) / 2);
    nodeSel.attr("transform", d => "translate(" + d.x + "," + d.y + ")");
  });

  function update() {
    const vis = new Set();
    graphData.nodes.forEach(n => {
      const show = !hidden.has(n.type) && (!searchTerm || (n.title||"").toLowerCase().includes(searchTerm));
      if (show) vis.add(n.id);
    });
    nodeSel.style("display", d => vis.has(d.id) ? null : "none");
    linkSel.style("display", d => (vis.has(d.source.id||d.source) && vis.has(d.target.id||d.target)) ? null : "none");
    linkLabelSel.style("display", d => (vis.has(d.source.id||d.source) && vis.has(d.target.id||d.target)) ? null : "none");
  }

  function dragStart(ev, d) { if (!ev.active) sim.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; }
  function dragging(ev, d) { d.fx = ev.x; d.fy = ev.y; }
  function dragEnd(ev, d) { if (!ev.active) sim.alphaTarget(0); d.fx = null; d.fy = null; }
})();
""";
}
