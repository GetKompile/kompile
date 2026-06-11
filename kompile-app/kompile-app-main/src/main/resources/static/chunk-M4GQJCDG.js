import {
  __name
} from "./chunk-W6HWZJ4B.js";
import {
  __async
} from "./chunk-TXDUYLVM.js";

// node_modules/@mermaid-js/parser/dist/mermaid-parser.core.mjs
var parsers = {};
var initializers = {
  info: /* @__PURE__ */ __name(() => __async(null, null, function* () {
    const {
      createInfoServices: createInfoServices2
    } = yield import("./chunk-NTPKP2MV.js");
    const parser = createInfoServices2().Info.parser.LangiumParser;
    parsers.info = parser;
  }), "info"),
  packet: /* @__PURE__ */ __name(() => __async(null, null, function* () {
    const {
      createPacketServices: createPacketServices2
    } = yield import("./chunk-NUJJBSZI.js");
    const parser = createPacketServices2().Packet.parser.LangiumParser;
    parsers.packet = parser;
  }), "packet"),
  pie: /* @__PURE__ */ __name(() => __async(null, null, function* () {
    const {
      createPieServices: createPieServices2
    } = yield import("./chunk-VHL2NWYW.js");
    const parser = createPieServices2().Pie.parser.LangiumParser;
    parsers.pie = parser;
  }), "pie"),
  treeView: /* @__PURE__ */ __name(() => __async(null, null, function* () {
    const {
      createTreeViewServices: createTreeViewServices2
    } = yield import("./chunk-YONSGYNM.js");
    const parser = createTreeViewServices2().TreeView.parser.LangiumParser;
    parsers.treeView = parser;
  }), "treeView"),
  architecture: /* @__PURE__ */ __name(() => __async(null, null, function* () {
    const {
      createArchitectureServices: createArchitectureServices2
    } = yield import("./chunk-E5XR5ZDN.js");
    const parser = createArchitectureServices2().Architecture.parser.LangiumParser;
    parsers.architecture = parser;
  }), "architecture"),
  gitGraph: /* @__PURE__ */ __name(() => __async(null, null, function* () {
    const {
      createGitGraphServices: createGitGraphServices2
    } = yield import("./chunk-X5OREHJ4.js");
    const parser = createGitGraphServices2().GitGraph.parser.LangiumParser;
    parsers.gitGraph = parser;
  }), "gitGraph"),
  eventmodeling: /* @__PURE__ */ __name(() => __async(null, null, function* () {
    const {
      createEventModelingServices: createEventModelingServices2
    } = yield import("./chunk-5YBX3QOV.js");
    const parser = createEventModelingServices2().EventModel.parser.LangiumParser;
    parsers.eventmodeling = parser;
  }), "eventmodeling"),
  radar: /* @__PURE__ */ __name(() => __async(null, null, function* () {
    const {
      createRadarServices: createRadarServices2
    } = yield import("./chunk-WHLS3HL5.js");
    const parser = createRadarServices2().Radar.parser.LangiumParser;
    parsers.radar = parser;
  }), "radar"),
  treemap: /* @__PURE__ */ __name(() => __async(null, null, function* () {
    const {
      createTreemapServices: createTreemapServices2
    } = yield import("./chunk-PVKEIAYX.js");
    const parser = createTreemapServices2().Treemap.parser.LangiumParser;
    parsers.treemap = parser;
  }), "treemap"),
  wardley: /* @__PURE__ */ __name(() => __async(null, null, function* () {
    const {
      createWardleyServices: createWardleyServices2
    } = yield import("./chunk-PAXKPX5I.js");
    const parser = createWardleyServices2().Wardley.parser.LangiumParser;
    parsers.wardley = parser;
  }), "wardley")
};
function parse(diagramType, text) {
  return __async(this, null, function* () {
    const initializer = initializers[diagramType];
    if (!initializer) {
      throw new Error(`Unknown diagram type: ${diagramType}`);
    }
    if (!parsers[diagramType]) {
      yield initializer();
    }
    const parser = parsers[diagramType];
    const result = parser.parse(text);
    if (result.lexerErrors.length > 0 || result.parserErrors.length > 0) {
      throw new MermaidParseError(result);
    }
    return result.value;
  });
}
__name(parse, "parse");
var MermaidParseError = class extends Error {
  constructor(result) {
    const lexerErrors = result.lexerErrors.map((err) => {
      const line = err.line !== void 0 && !isNaN(err.line) ? err.line : "?";
      const column = err.column !== void 0 && !isNaN(err.column) ? err.column : "?";
      return `Lexer error on line ${line}, column ${column}: ${err.message}`;
    }).join("\n");
    const parserErrors = result.parserErrors.map((err) => {
      const line = err.token.startLine !== void 0 && !isNaN(err.token.startLine) ? err.token.startLine : "?";
      const column = err.token.startColumn !== void 0 && !isNaN(err.token.startColumn) ? err.token.startColumn : "?";
      return `Parse error on line ${line}, column ${column}: ${err.message}`;
    }).join("\n");
    super(`Parsing failed: ${lexerErrors} ${parserErrors}`);
    this.result = result;
  }
  static {
    __name(this, "MermaidParseError");
  }
};

export {
  parse
};
//# sourceMappingURL=chunk-M4GQJCDG.js.map
