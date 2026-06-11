import {
  parse
} from "./chunk-M4GQJCDG.js";
import "./chunk-DMZNK4DN.js";
import {
  selectSvgElement
} from "./chunk-UEFMNANI.js";
import "./chunk-LUAGKGGW.js";
import "./chunk-RNISIYWT.js";
import "./chunk-PMFBBG35.js";
import "./chunk-MU2UTNAC.js";
import "./chunk-PPW3EK5B.js";
import "./chunk-H5U3ZZDR.js";
import "./chunk-ZWPCNIZI.js";
import "./chunk-MRWG37VW.js";
import {
  configureSvgSize
} from "./chunk-CSAGDLWM.js";
import {
  __name,
  log
} from "./chunk-G4VPOIJH.js";
import "./chunk-27GZJSOC.js";
import "./chunk-ZRLGKL2V.js";
import "./chunk-W6HWZJ4B.js";
import {
  __async
} from "./chunk-TXDUYLVM.js";

// node_modules/mermaid/dist/chunks/mermaid.core/infoDiagram-5YYISTIA.mjs
var parser = {
  parse: /* @__PURE__ */ __name((input) => __async(null, null, function* () {
    const ast = yield parse("info", input);
    log.debug(ast);
  }), "parse")
};
var DEFAULT_INFO_DB = {
  version: "11.15.0" + (true ? "" : "-tiny")
};
var getVersion = /* @__PURE__ */ __name(() => DEFAULT_INFO_DB.version, "getVersion");
var db = {
  getVersion
};
var draw = /* @__PURE__ */ __name((text, id, version) => {
  log.debug("rendering info diagram\n" + text);
  const svg = selectSvgElement(id);
  configureSvgSize(svg, 100, 400, true);
  const group = svg.append("g");
  group.append("text").attr("x", 100).attr("y", 40).attr("class", "version").attr("font-size", 32).style("text-anchor", "middle").text(`v${version}`);
}, "draw");
var renderer = {
  draw
};
var diagram = {
  parser,
  db,
  renderer
};
export {
  diagram
};
//# sourceMappingURL=chunk-PCGGFUJ3.js.map
