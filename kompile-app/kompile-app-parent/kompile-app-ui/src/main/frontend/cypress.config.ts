import { defineConfig } from 'cypress';
import { allPersonaApiUrls, personaBaseUrl, targetPersona } from './e2e-endpoints';

/*
 * Cypress drives one SPA per run, so the persona picks both the baseUrl and which spec folders
 * are in scope. `cypress/e2e/shared/` runs under every persona — those specs only touch the
 * surface all three apps mount (projects, fact sheets, documents, navigation chrome).
 *
 *   npm run e2e:cy            # admin console on :8080
 *   npm run e2e:cy:chat       # chat app on :8081
 *   npm run e2e:cy:crawl      # crawl manager on :8082
 */
const persona = targetPersona();

export default defineConfig({
  e2e: {
    baseUrl: personaBaseUrl(persona),
    specPattern: [`cypress/e2e/${persona}/**/*.cy.ts`, 'cypress/e2e/shared/**/*.cy.ts'],
    supportFile: 'cypress/support/e2e.ts',
    fixturesFolder: 'cypress/fixtures',
    viewportWidth: 1440,
    viewportHeight: 900,
    defaultCommandTimeout: 10000,
    requestTimeout: 15000,
    responseTimeout: 15000,
    video: false,
    screenshotOnRunFailure: true,
    retries: {
      runMode: 1,
      openMode: 0
    },
    env: {
      // Relative, so apiGet/apiPost resolve against the persona under test. A spec that needs
      // another app's API uses the absolute adminApi/chatApi/crawlApi values instead.
      apiUrl: '/api',
      persona,
      ...allPersonaApiUrls()
    }
  }
});
