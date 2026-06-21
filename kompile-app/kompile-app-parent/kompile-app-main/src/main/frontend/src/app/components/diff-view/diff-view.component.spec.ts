/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { DiffViewComponent } from './diff-view.component';

/**
 * Pure logic tests for the shared diff renderer. The component has no injected
 * dependencies, so it is exercised directly with `new` + `ngOnChanges()` (no
 * TestBed / DOM needed) — these assertions cover the unified-diff parser and the
 * side-by-side pairing that the git, agent and compare views all rely on.
 */
describe('DiffViewComponent', () => {
  let component: DiffViewComponent;

  beforeEach(() => {
    component = new DiffViewComponent();
  });

  /** Apply the current @Input values, mimicking Angular's change detection. */
  function render(): void {
    component.ngOnChanges();
  }

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('parses a unified diff into typed, line-numbered rows', () => {
    component.unifiedDiff = [
      '--- a/foo.txt',
      '+++ b/foo.txt',
      '@@ -1,2 +1,2 @@',
      ' context',
      '-old line',
      '+new line'
    ].join('\n');
    render();

    const types = component.parsedDiff.map(l => l.type);
    expect(types).toEqual(['file', 'file', 'header', 'context', 'remove', 'add']);

    const remove = component.parsedDiff.find(l => l.type === 'remove')!;
    const add = component.parsedDiff.find(l => l.type === 'add')!;
    expect(remove.oldLineNo).toBe(2);
    expect(remove.newLineNo).toBeNull();
    expect(add.newLineNo).toBe(2);
    expect(add.oldLineNo).toBeNull();
    expect(component.isEmpty).toBeFalse();
  });

  it('strips the +/- prefix from changed line text', () => {
    component.unifiedDiff = '@@ -1 +1 @@\n-foo\n+bar';
    render();
    expect(component.parsedDiff.find(l => l.type === 'remove')!.text).toBe('foo');
    expect(component.parsedDiff.find(l => l.type === 'add')!.text).toBe('bar');
  });

  it('builds a synthetic diff from oldString/newString when no unified diff is given', () => {
    component.unifiedDiff = null;
    component.oldString = 'a\nb';
    component.newString = 'a\nc';
    component.filePath = 'x.txt';
    render();

    const removes = component.parsedDiff.filter(l => l.type === 'remove').map(l => l.text);
    const adds = component.parsedDiff.filter(l => l.type === 'add').map(l => l.text);
    expect(removes).toEqual(['a', 'b']);
    expect(adds).toEqual(['a', 'c']);
  });

  it('pairs consecutive removes and adds side by side for the split view', () => {
    component.unifiedDiff = '@@ -1,2 +1,2 @@\n-old1\n-old2\n+new1\n+new2';
    render();

    const changed = component.splitPairs.filter(p => p.left?.type === 'remove' || p.right?.type === 'add');
    expect(changed.length).toBe(2);
    expect(changed[0].left?.text).toBe('old1');
    expect(changed[0].right?.text).toBe('new1');
    expect(changed[1].left?.text).toBe('old2');
    expect(changed[1].right?.text).toBe('new2');
  });

  it('represents an addition-only hunk as right-only pairs', () => {
    component.unifiedDiff = '@@ -0,0 +1,2 @@\n+l1\n+l2';
    render();

    const adds = component.splitPairs.filter(p => p.right?.type === 'add');
    expect(adds.length).toBe(2);
    expect(adds.every(p => p.left === null)).toBeTrue();
  });

  it('keeps a context line on both sides of a split (same row reference)', () => {
    component.unifiedDiff = '@@ -1 +1 @@\n ctx';
    render();

    const ctx = component.splitPairs.find(p => p.left?.type === 'context')!;
    expect(ctx.left).toBe(ctx.right!);
    expect(ctx.left!.text).toBe('ctx');
  });

  it('reports empty when there is nothing but file/hunk headers', () => {
    component.unifiedDiff = '';
    component.oldString = null;
    component.newString = null;
    render();
    expect(component.isEmpty).toBeTrue();
  });
});
