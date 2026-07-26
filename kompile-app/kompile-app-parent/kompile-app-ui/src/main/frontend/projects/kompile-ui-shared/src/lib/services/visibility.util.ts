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

import { fromEvent, MonoTypeOperatorFunction, Observable } from 'rxjs';
import { distinctUntilChanged, filter, map, startWith } from 'rxjs/operators';

/**
 * Observable that emits `true` when the page is visible and `false` when hidden.
 * Starts with the current visibility state so subscribers have an immediate value.
 *
 * Available for consumers that need to *react* to a visibility transition (fire a refresh the
 * moment the user comes back, say). Deliberately NOT used by {@link pauseWhenHidden} — see the
 * note there.
 */
export const pageVisible$: Observable<boolean> = fromEvent(document, 'visibilitychange').pipe(
  startWith(null as Event | null),
  map(() => !document.hidden),
  distinctUntilChanged()
);

/**
 * RxJS pipeable operator that drops emissions while the browser tab is hidden, so background tabs
 * issue no polling requests. Emissions resume on the next tick after the tab becomes visible.
 *
 * Usage:
 *   interval(5000).pipe(takeUntil(this.destroy$), pauseWhenHidden()).subscribe(...)
 *
 * This is a `filter` on the source rather than the more obvious
 * `pageVisible$.pipe(switchMap(visible => visible ? source : EMPTY))`, and the difference is load
 * bearing. The switchMap form makes `pageVisible$` the outer observable, which silently discards
 * every terminator upstream of it: with `takeUntil(destroy$)` before this operator, the subscription
 * that `.subscribe()` returns is on `pageVisible$`, and `pageVisible$` never completes. Destroying
 * the component then leaves the `visibilitychange` listener attached, and the next tab switch
 * re-subscribes to the source — resurrecting the poller. It does not even stop on the second pass,
 * because `takeUntil` completes on notifier *next*, never on notifier *complete*, so re-subscribing
 * to an already-fired `destroy$` yields an interval that runs for the life of the page. Four of this
 * operator's call sites place a terminator upstream of it and would leak exactly that way.
 *
 * Keeping the source in the chain costs us the timer teardown that switchMap gave: the interval
 * keeps ticking while hidden instead of being unsubscribed. That is the cheap half — browsers
 * already clamp background timers hard, and the expensive half (the HTTP round trip and the
 * re-render) is skipped either way. An immortal poller per destroyed component is not a trade worth
 * making for it.
 */
export function pauseWhenHidden<T>(): MonoTypeOperatorFunction<T> {
  return (source: Observable<T>) => source.pipe(filter(() => !document.hidden));
}
