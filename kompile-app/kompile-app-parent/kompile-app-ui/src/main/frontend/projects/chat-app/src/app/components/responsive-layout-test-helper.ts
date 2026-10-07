/** Render the actual component/styles in a viewport where media queries really apply. */
export function responsiveLayout(host: HTMLElement, width: number, height = 640): {
  root: HTMLElement; view: Window; dispose: () => void;
} {
  const frame = document.createElement('iframe');
  frame.style.cssText = `position:fixed;left:-10000px;top:0;width:${width}px;height:${height}px;border:0;`;
  document.body.appendChild(frame);
  const doc = frame.contentDocument!;
  const style = doc.createElement('style');
  style.textContent = Array.from(document.styleSheets).map(sheet => {
    try { return Array.from(sheet.cssRules).map(rule => rule.cssText).join('\n'); }
    catch { return ''; }
  }).join('\n') + '\nhtml,body{margin:0;height:100%;overflow:hidden;}';
  doc.head.appendChild(style);
  doc.body.className = document.body.className;
  const root = doc.importNode(host, true) as HTMLElement;
  root.style.cssText += ';display:block;width:100%;height:100%;min-height:0;';
  doc.body.appendChild(root);
  return { root, view: frame.contentWindow!, dispose: () => frame.remove() };
}
