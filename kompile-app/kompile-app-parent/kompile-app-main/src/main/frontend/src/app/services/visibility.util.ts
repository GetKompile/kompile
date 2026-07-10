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

import { fromEvent, Observable, of } from 'rxjs';
import { distinctUntilChanged, map, startWith } from 'rxjs/operators';
import { MonoTypeOperatorFunction } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { EMPTY } from 'rxjs';

/**
 * Observable that emits `true` when the page is visible and `false` when hidden.
 * Starts with the current visibility state so subscribers have an immediate value.
 */
export const pageVisible$: Observable<boolean> = fromEvent(document, 'visibilitychange').pipe(
  startWith(null as Event | null),
  map(() => !document.hidden),
  distinctUntilChanged()
);

/**
 * RxJS pipeable operator that pauses the source observable while the browser tab is hidden.
 * When the tab becomes visible again the source resumes from the next emission.
 *
 * Usage:
 *   interval(5000).pipe(pauseWhenHidden()).subscribe(...)
 */
export function pauseWhenHidden<T>(): MonoTypeOperatorFunction<T> {
  return (source: Observable<T>) =>
    pageVisible$.pipe(
      switchMap(visible => (visible ? source : EMPTY))
    );
}
