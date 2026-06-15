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

// Helper function to determine API URL based on current location
function getApiUrl(): string {
  if (typeof window !== 'undefined' && window.location) {
    const protocol = window.location.protocol;
    const hostname = window.location.hostname;
    const port = window.location.port;

    // Use the same host and port as the frontend
    return `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
  }
  // Fallback for SSR or when window is not available
  return '/api';
}

export const environment = {
  production: false,
  apiUrl: getApiUrl(), // Your backend API base URL (dynamically determined)
  appTitle: 'Kompile RAG Console' // Configurable application title
};
