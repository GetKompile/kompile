import {
  getConfig2
} from "./chunk-CSAGDLWM.js";
import {
  __name
} from "./chunk-G4VPOIJH.js";
import {
  select_default
} from "./chunk-27GZJSOC.js";

// node_modules/mermaid/dist/chunks/mermaid.core/chunk-WU5MYG2G.mjs
var selectSvgElement = /* @__PURE__ */ __name((id) => {
  const {
    securityLevel
  } = getConfig2();
  let root = select_default("body");
  if (securityLevel === "sandbox") {
    const sandboxElement = select_default(`#i${id}`);
    const doc = sandboxElement.node()?.contentDocument ?? document;
    root = select_default(doc.body);
  }
  const svg = root.select(`#${id}`);
  return svg;
}, "selectSvgElement");

export {
  selectSvgElement
};
//# sourceMappingURL=chunk-UEFMNANI.js.map
