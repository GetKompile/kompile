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

import { describeRouteTable } from '@shared/testing/route-table-harness';
import { routes } from './app-routing.module';

describeRouteTable('crawl-app', routes, {
  declared: ['/crawl', '/fact-sheets', '/data', '/graph', '/settings'],
  redirects: {
    '/knowledge': '/fact-sheets',
    '/tools': '/data',
    '/code-projects': '/data'
  },
  fallback: '/crawl',
  // Chat and admin surfaces. The crawl manager is an end-user app: none of these may resolve here.
  foreign: ['/chat', '/project', '/developer', '/agents', '/enforcer',
            '/knowledge-graph', '/grounding', '/graph-simulator']
});
