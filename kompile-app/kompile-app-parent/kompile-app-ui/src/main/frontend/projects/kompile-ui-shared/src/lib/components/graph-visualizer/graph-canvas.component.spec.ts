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

import { ElementRef, SimpleChange, SimpleChanges } from '@angular/core';
import { fakeAsync, flushMicrotasks } from '@angular/core/testing';
import Graph from 'graphology';
import Sigma from 'sigma';
import { of } from 'rxjs';

import { GraphCanvasComponent } from './graph-canvas.component';
import { D3Link, D3Node, D3VisualizationData } from '../../models/graph-models';

// How the canvas redraws as its data changes, and how it centres a requested node. Sigma draws
// with WebGL, so these run over the canvas's real graphology graph with a renderer that records.

/** Stands in for Sigma: it records refreshes and camera moves over the graph the canvas built. */
class RecordingRenderer {
  readonly camera = {
    ratio: 1,
    animate: jasmine.createSpy('animate'),
    enable: () => undefined,
    disable: () => undefined,
    animatedReset: () => undefined,
  };

  constructor(readonly graph: Graph) {}

  on(): void {}
  getMouseCaptor() { return { on: () => undefined }; }
  getCamera() { return this.camera; }
  refresh(): void {}
  kill(): void {}

  /** Sigma reports positions in its own normalized frame; this one halves the graph coordinates. */
  getNodeDisplayData(key: string): { x: number; y: number } | undefined {
    if (!this.graph.hasNode(key)) return undefined;
    return { x: this.graph.getNodeAttribute(key, 'x') / 2, y: this.graph.getNodeAttribute(key, 'y') / 2 };
  }
}

class TestCanvas extends GraphCanvasComponent {
  recorder!: RecordingRenderer;

  constructor() {
    super(
      { markForCheck: () => undefined } as any,
      { run: (fn: () => unknown) => fn() } as any,
      { theme$: of(null) } as any
    );
    this.sigmaContainerRef = new ElementRef(document.createElement('div'));
  }

  protected override createRenderer(graph: Graph): Sigma {
    this.recorder = new RecordingRenderer(graph);
    return this.recorder as unknown as Sigma;
  }
}

function node(id: string): D3Node {
  return { id, type: 'ENTITY', label: id };
}

function link(source: string, target: string): D3Link {
  return { id: `${source}::${target}::SHARED_ENTITY`, source, target, type: 'SHARED_ENTITY', weight: 1 };
}

function data(ids: string[], links: D3Link[] = []): D3VisualizationData {
  return { nodes: ids.map(node), links };
}

function at(graph: Graph, id: string): { x: number; y: number } {
  return { x: graph.getNodeAttribute(id, 'x'), y: graph.getNodeAttribute(id, 'y') };
}

/** Sets inputs the way a template binding does: the fields first, then one ngOnChanges. */
function bind(
  canvas: GraphCanvasComponent,
  inputs: { data?: D3VisualizationData; focusNodeId?: string | null },
  firstChange = false
): void {
  const changes: SimpleChanges = {};
  if (inputs.data !== undefined) {
    changes['data'] = new SimpleChange(canvas.data, inputs.data, firstChange);
    canvas.data = inputs.data;
  }
  if (inputs.focusNodeId !== undefined) {
    changes['focusNodeId'] = new SimpleChange(canvas.focusNodeId, inputs.focusNodeId, firstChange);
    canvas.focusNodeId = inputs.focusNodeId;
  }
  canvas.ngOnChanges(changes);
}

describe('GraphCanvasComponent', () => {
  let focused: jasmine.Spy;
  let selected: jasmine.Spy;

  /** Creates the canvas as Angular does: the first bindings, then init. */
  function start(inputs: { data: D3VisualizationData; focusNodeId?: string | null }): TestCanvas {
    const canvas = new TestCanvas();
    focused = jasmine.createSpy('focused');
    selected = jasmine.createSpy('nodeSelected');
    canvas.focused.subscribe(focused);
    canvas.nodeSelected.subscribe(selected);
    bind(canvas, inputs, true);
    canvas.ngOnInit();
    return canvas;
  }

  describe('a focus request', () => {
    it('selects and centres a node drawn at start-up, then reports it', fakeAsync(() => {
      const canvas = start({ data: data(['a', 'b']), focusNodeId: 'b' });
      const position = canvas.recorder.getNodeDisplayData('b')!;

      expect(canvas.recorder.camera.animate)
        .toHaveBeenCalledOnceWith({ x: position.x, y: position.y, ratio: 0.5 }, { duration: 400 });
      expect(canvas.recorder.graph.getNodeAttribute('b', 'highlighted')).toBeTrue();
      expect(canvas.recorder.graph.getNodeAttribute('a', 'highlighted')).toBeFalse();
      // Reported after the change detection pass that applied it, not inside it.
      expect(focused).not.toHaveBeenCalled();

      flushMicrotasks();
      expect(focused).toHaveBeenCalledOnceWith(node('b'));
      expect(selected).not.toHaveBeenCalled();
    }));

    it('waits for a data change that draws the node', fakeAsync(() => {
      const canvas = start({ data: data(['a']), focusNodeId: 'b' });
      flushMicrotasks();
      expect(canvas.recorder.camera.animate).not.toHaveBeenCalled();
      expect(focused).not.toHaveBeenCalled();

      bind(canvas, { data: data(['a', 'b'], [link('a', 'b')]) });
      flushMicrotasks();

      const position = canvas.recorder.getNodeDisplayData('b')!;
      expect(canvas.recorder.camera.animate)
        .toHaveBeenCalledOnceWith({ x: position.x, y: position.y, ratio: 0.5 }, { duration: 400 });
      expect(focused).toHaveBeenCalledOnceWith(node('b'));
    }));

    it('waits for an expansion that draws the node', fakeAsync(() => {
      const canvas = start({ data: data(['a']), focusNodeId: 'b' });

      canvas.addNodesToGraph(data(['a', 'b'], [link('a', 'b')]));
      flushMicrotasks();

      const position = canvas.recorder.getNodeDisplayData('b')!;
      expect(canvas.recorder.camera.animate)
        .toHaveBeenCalledOnceWith({ x: position.x, y: position.y, ratio: 0.5 }, { duration: 400 });
      expect(canvas.recorder.graph.getNodeAttribute('b', 'highlighted')).toBeTrue();
      expect(focused).toHaveBeenCalledOnceWith(node('b'));
    }));

    it('is dropped when cleared before its node is drawn', fakeAsync(() => {
      const canvas = start({ data: data(['a']), focusNodeId: 'b' });

      bind(canvas, { focusNodeId: null });
      bind(canvas, { data: data(['a', 'b']) });
      flushMicrotasks();

      expect(canvas.recorder.camera.animate).not.toHaveBeenCalled();
      expect(canvas.recorder.graph.getNodeAttribute('b', 'highlighted')).not.toBeTrue();
      expect(focused).not.toHaveBeenCalled();
    }));

    it('gives way to a newer request', fakeAsync(() => {
      const canvas = start({ data: data(['a', 'c']), focusNodeId: 'b' });

      bind(canvas, { focusNodeId: 'c' });
      flushMicrotasks();
      expect(focused).toHaveBeenCalledOnceWith(node('c'));

      bind(canvas, { data: data(['a', 'b', 'c']) });
      flushMicrotasks();
      expect(canvas.recorder.camera.animate).toHaveBeenCalledTimes(1);
      expect(focused).toHaveBeenCalledTimes(1);
    }));

    it('is applied once', fakeAsync(() => {
      const canvas = start({ data: data(['a', 'b']), focusNodeId: 'b' });

      bind(canvas, { data: data(['a', 'b']) });
      canvas.addNodesToGraph(data(['b', 'c'], [link('b', 'c')]));
      flushMicrotasks();

      expect(canvas.recorder.camera.animate).toHaveBeenCalledTimes(1);
      expect(focused).toHaveBeenCalledTimes(1);
    }));

    it('keeps a closer zoom', fakeAsync(() => {
      const canvas = start({ data: data(['a']), focusNodeId: 'b' });
      canvas.recorder.camera.ratio = 0.2;

      bind(canvas, { data: data(['a', 'b']) });

      const position = canvas.recorder.getNodeDisplayData('b')!;
      expect(canvas.recorder.camera.animate)
        .toHaveBeenCalledOnceWith({ x: position.x, y: position.y, ratio: 0.2 }, { duration: 400 });
    }));
  });

  describe('a redraw', () => {
    it('leaves the graph alone when the same data arrives again', () => {
      const canvas = start({ data: data(['a', 'b'], [link('a', 'b')]) });
      const graph = canvas.recorder.graph;
      graph.setNodeAttribute('a', 'x', 999);  // where a drag left it
      graph.setNodeAttribute('a', 'y', -999);
      const clear = spyOn(graph, 'clear').and.callThrough();
      const addNode = spyOn(graph, 'addNode').and.callThrough();

      bind(canvas, { data: data(['a', 'b'], [link('a', 'b')]) });

      expect(clear).not.toHaveBeenCalled();
      expect(addNode).not.toHaveBeenCalled();
      expect(at(graph, 'a')).toEqual({ x: 999, y: -999 });
      expect(graph.edges()).toEqual(['a::b::SHARED_ENTITY']);
    });

    it('adds a new node without moving the others', () => {
      const canvas = start({ data: data(['a', 'b'], [link('a', 'b')]) });
      const graph = canvas.recorder.graph;
      const a = at(graph, 'a');
      const b = at(graph, 'b');
      const addNode = spyOn(graph, 'addNode').and.callThrough();

      bind(canvas, { data: data(['a', 'b', 'c'], [link('a', 'b'), link('b', 'c')]) });

      expect(addNode).toHaveBeenCalledOnceWith('c', jasmine.anything());
      expect(at(graph, 'a')).toEqual(a);
      expect(at(graph, 'b')).toEqual(b);
      expect(graph.edges().sort()).toEqual(['a::b::SHARED_ENTITY', 'b::c::SHARED_ENTITY']);
    });

    it('drops a node the data no longer has, with its edges', () => {
      const canvas = start({ data: data(['a', 'b', 'c'], [link('a', 'b'), link('b', 'c')]) });
      const graph = canvas.recorder.graph;

      bind(canvas, { data: data(['a', 'b'], [link('a', 'b')]) });

      expect(graph.nodes().sort()).toEqual(['a', 'b']);
      expect(graph.edges()).toEqual(['a::b::SHARED_ENTITY']);
    });

    it('drops an edge the data no longer has', () => {
      const canvas = start({ data: data(['a', 'b', 'c'], [link('a', 'b'), link('b', 'c')]) });
      const graph = canvas.recorder.graph;

      bind(canvas, { data: data(['a', 'b', 'c'], [link('a', 'b')]) });

      expect(graph.nodes().sort()).toEqual(['a', 'b', 'c']);
      expect(graph.edges()).toEqual(['a::b::SHARED_ENTITY']);
    });
  });
});
