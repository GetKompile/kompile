#!/usr/bin/env python3
"""Persona closure over the Angular component graph.

Mirrors the Java-side carve: start from each persona's route-root components and
walk every reference (TS import of a sibling component, template selector usage,
routerLink target) to a fixpoint. A component in >1 closure is shared; a component
in exactly 1 belongs to that app; a component in 0 is dead relative to the routes.
"""
import json
import os
import re
import sys
from collections import defaultdict

ROOT = "/home/agibsonccc/Documents/GitHub/kompile/kompile-app/kompile-app-parent/kompile-app-ui/src/main/frontend/src/app"
COMPONENTS = os.path.join(ROOT, "components")

# class name -> record
by_class = {}
# selector -> class name
by_selector = {}
# file path -> [class names] (for import resolution; a file may declare several)
by_path = defaultdict(list)

sel_re = re.compile(r"selector\s*:\s*['\"]([^'\"]+)['\"]")
standalone_re = re.compile(r"standalone\s*:\s*true")
templateurl_re = re.compile(r"templateUrl\s*:\s*['\"]([^'\"]+)['\"]")
# One @Component decorator plus the class it decorates. Several files declare a page
# component and its dialog side by side, so this must match every occurrence, not the first.
decl_re = re.compile(r"@Component\s*\(\s*\{(.*?)\}\s*\)\s*(?:export\s+)?class\s+(\w+)", re.S)

# Walk all of src/app, not just components/: AppComponent is the shell that owns the tab bar,
# branding and status chrome, and every persona app carves its own shell from it.
for dirpath, _dirnames, filenames in os.walk(ROOT):
    if os.sep + "node_modules" in dirpath:
        continue
    for fn in filenames:
        if not fn.endswith(".ts") or fn.endswith(".spec.ts"):
            continue
        path = os.path.join(dirpath, fn)
        with open(path, encoding="utf-8", errors="replace") as fh:
            src = fh.read()
        if "@Component" not in src:
            continue
        for body, cls in decl_re.findall(src):
            sel = sel_re.search(body)
            rec = {
                "class": cls,
                "selector": sel.group(1) if sel else None,
                "ts": path,
                "dir": os.path.relpath(dirpath, COMPONENTS),
                "standalone": bool(standalone_re.search(body)),
                "src": src,
            }
            tu = templateurl_re.search(body)
            html = None
            if tu:
                cand = os.path.normpath(os.path.join(dirpath, tu.group(1)))
                if os.path.exists(cand):
                    html = cand
            rec["html"] = html
            by_class[cls] = rec
            by_path[os.path.splitext(path)[0]].append(cls)
            if rec["selector"]:
                by_selector[rec["selector"]] = cls

# Build the reference edges.
edges = defaultdict(set)
import_re = re.compile(r"import\s*\{([^}]*)\}\s*from\s*['\"]([^'\"]+)['\"]")

for cls, rec in by_class.items():
    src = rec["src"]
    # TS imports that resolve to another component file
    for names, spec in import_re.findall(src):
        if not spec.startswith("."):
            continue
        target = os.path.normpath(os.path.join(os.path.dirname(rec["ts"]), spec))
        for hit in by_path.get(target, ()):
            if hit != cls:
                edges[cls].add(hit)
    # Template selector usage
    blobs = [src]
    if rec["html"]:
        with open(rec["html"], encoding="utf-8", errors="replace") as fh:
            blobs.append(fh.read())
    blob = "\n".join(blobs)
    for sel, target in by_selector.items():
        if target == cls:
            continue
        if re.search(r"<" + re.escape(sel) + r"[\s/>]", blob):
            edges[cls].add(target)

# AppComponent is a root for every persona: each app carves its own shell from it, so the
# chrome it pulls in (branding, status indicators, setup wizard, ...) is shared by definition.
SHELL = ["AppComponent"]
# GraphsHubComponent is a standalone component that eagerly `imports:` all 29 panels, so routing
# chat/crawl at it would drag the whole maintenance/rules/health/simulator suite into an end-user
# bundle. Those personas get the `explore` section's four panels only — SECTION_TABS.explore in
# graphs-hub.component.ts — which is what "read-only exploration" means in the plan.
GRAPH_EXPLORE = ["GraphVisualizerComponent", "GraphHierarchyComponent",
                 "GraphOverviewComponent", "CommunityPanelComponent"]
PERSONAS = {
    "chat": SHELL + GRAPH_EXPLORE + ["UnifiedChatComponent", "ProjectPageComponent",
                                     "FactSheetPageComponent", "GroundingConsolePanelComponent"],
    "crawl": SHELL + GRAPH_EXPLORE + ["UnifiedCrawlComponent", "FactSheetPageComponent",
                                      "ToolsHubComponent", "NoteSyncManagerComponent",
                                      "ConnectionsManagerComponent", "IndexBrowserComponent"],
    "admin": SHELL + ["DeveloperHubComponent", "KClawHubComponent", "EnforcerHubComponent",
                      "SettingsComponent", "KnowledgeGraphPageComponent",
                      "GraphSimulatorComponent"],
}

closures = {}
for persona, roots in PERSONAS.items():
    missing = [r for r in roots if r not in by_class]
    seen = set()
    stack = [r for r in roots if r in by_class]
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        stack.extend(edges[cur] - seen)
    closures[persona] = seen
    print(f"{persona}: {len(seen)} components reachable from {len(roots)} roots"
          + (f"  MISSING ROOTS: {missing}" if missing else ""))

all_reachable = set().union(*closures.values())
membership = defaultdict(list)
for cls in all_reachable:
    owners = [p for p in PERSONAS if cls in closures[p]]
    membership["+".join(sorted(owners))].append(cls)

print("\n=== membership buckets ===")
for key in sorted(membership, key=lambda k: -len(membership[k])):
    print(f"{key}: {len(membership[key])}")

dead = sorted(set(by_class) - all_reachable)
print(f"\ntotal components: {len(by_class)}   reachable: {len(all_reachable)}   unreachable: {len(dead)}")

out = {
    "closures": {p: sorted(c) for p, c in closures.items()},
    "membership": {k: sorted(v) for k, v in membership.items()},
    "dead": dead,
    "components": {c: {k: v for k, v in r.items() if k != "src"} for c, r in by_class.items()},
}
dest = sys.argv[1] if len(sys.argv) > 1 else "/tmp/ui_closure.json"
with open(dest, "w", encoding="utf-8") as fh:
    json.dump(out, fh, indent=1)
print(f"\nwrote {dest}")
