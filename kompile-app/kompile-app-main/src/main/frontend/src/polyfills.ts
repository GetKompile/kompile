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

/**
 * Polyfills for Node.js globals required by sockjs-client
 *
 * The sockjs-client library is designed for both Node.js and browser environments.
 * In browser environments, it expects certain Node.js globals to be available.
 * This file must be loaded before any other application code.
 */

// Polyfill for Node.js 'global' object
(window as any).global = window;

// Polyfill for Node.js 'process' object
(window as any).process = (window as any).process || {
  env: {},
  nextTick: (fn: Function, ...args: any[]) => setTimeout(() => fn(...args), 0),
  version: '',
  versions: {}
};

// Polyfill for Node.js 'Buffer' object
(window as any).Buffer = (window as any).Buffer || {
  isBuffer: () => false,
  from: () => new Uint8Array(),
  alloc: () => new Uint8Array()
};
