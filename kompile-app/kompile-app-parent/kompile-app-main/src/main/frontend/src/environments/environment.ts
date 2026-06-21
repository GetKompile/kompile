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
  appTitle: 'Kompile RAG Console', // Configurable application title

  // ───────────────────────────────────────────────────────────────────────────
  // White-label branding (top-left header logo + name).
  //
  // These are BUILD-TIME defaults. They can also be overridden at RUNTIME —
  // without rebuilding the frontend — via the backend `/api/config` endpoint
  // (see FrontendConfigController), which sources branding from the kompile app
  // config (app-index-config.json).
  //
  // To white-label, set logoUrl / logoAlt / showLogo / faviconUrl in the kompile
  // app config (app-index-config.json) via either:
  //   • the CLI:  kompile init-project --logoUrl … --faviconUrl … [--no-showLogo]
  //   • the app-builder UI (App Title + branding fields), or
  //   • replace the bundled asset at src/assets/branding/kompile-logo.svg.
  // ───────────────────────────────────────────────────────────────────────────
  branding: {
    logoUrl: 'assets/branding/kompile-logo.svg', // bundled with kompile-app-main
    logoAlt: 'Kompile',
    showLogo: true,
    faviconUrl: 'assets/branding/kompile-logo.svg' // browser tab icon (same brand mark)
  }
};
