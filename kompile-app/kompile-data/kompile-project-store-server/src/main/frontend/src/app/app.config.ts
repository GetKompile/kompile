import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';

/**
 * Standalone application providers. The UI is served from the same origin as the
 * project-store REST API, so the HTTP client uses relative URLs (no base href needed).
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideHttpClient(withFetch()),
    provideAnimations()
  ]
};
