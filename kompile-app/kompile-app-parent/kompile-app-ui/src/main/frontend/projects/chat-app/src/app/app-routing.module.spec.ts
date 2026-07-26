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

describeRouteTable('chat-app', routes, {
  declared: ['/chat', '/project', '/fact-sheets', '/graph'],
  redirects: { '/knowledge': '/fact-sheets' },
  fallback: '/chat',
  // Crawl-manager and admin surfaces. Chat is an end-user app: none of these may resolve here.
  foreign: ['/crawl', '/data', '/developer', '/agents', '/enforcer', '/settings',
            '/knowledge-graph', '/grounding', '/graph-simulator']
});
