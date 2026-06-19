import {
  parse
} from "./chunk-OTXDPSZQ.js";
import "./chunk-4AIFIRRT.js";
import {
  selectSvgElement
} from "./chunk-NHZ3FHRI.js";
import "./chunk-CAIWQ5CI.js";
import "./chunk-XMVZBG5S.js";
import "./chunk-4IWRCKUU.js";
import "./chunk-2AC4VC4H.js";
import "./chunk-RM4DLSIN.js";
import "./chunk-VGDPVBQK.js";
import "./chunk-LUBR2J3Z.js";
import "./chunk-RR2C3LK6.js";
import {
  configureSvgSize
} from "./chunk-JTZXFLHX.js";
import {
  __name,
  log
} from "./chunk-XGJ5IW5S.js";
import "./chunk-24WPMPII.js";
import "./chunk-FUVZVFMV.js";
import "./chunk-OXT6BPTJ.js";
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
//# sourceMappingURL=chunk-YHZLFPYO.js.map
