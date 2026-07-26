/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.app.config.AppIndexConfig;
import ai.kompile.app.services.AppIndexConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight controller at {@code /api/config} serving the application identity
 * fields that the Angular frontend's {@code ConfigService} fetches on startup.
 *
 * <p>The canonical source for {@code appTitle} is the persisted
 * {@code app-index-config.json} (via {@link AppIndexConfigService}). If no
 * title has been persisted, falls back to the Spring property
 * {@code kompile.app.title} (set by {@code RagPomGenerator} during
 * {@code init-project}).</p>
 *
 * <p>Also serves white-label branding fields ({@code logoUrl}, {@code logoAlt},
 * {@code showLogo}, {@code faviconUrl}) from the same persisted
 * {@code app-index-config.json} (via {@link AppIndexConfigService}). Operators
 * rebrand the console's logo, name, and favicon through the kompile app config
 * (CLI {@code init-project} / app UI) — not Spring properties — with no frontend
 * rebuild. The default logo is bundled with kompile-app-main at
 * {@code src/assets/branding/kompile-logo.svg}.</p>
 */
@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "*")
public class FrontendConfigController {

    private final AppIndexConfigService configService;

    private String fallbackAppTitle = "Kompile RAG Console";

    @Value("${spring.application.name:kompile-rag-app}")
    private String applicationName;

    // Fallback branding defaults — used only when app-index-config.json has no
    // branding set. The canonical source is the kompile app JSON config
    // (AppIndexConfig), seeded by the CLI and editable via the app UI.
    private static final String DEFAULT_LOGO_URL = "assets/branding/kompile-logo.svg";
    private static final String DEFAULT_LOGO_ALT = "Kompile";
    private static final String DEFAULT_FAVICON_URL = "assets/branding/kompile-logo.svg";

    @Autowired
    public FrontendConfigController(
            @Autowired(required = false) AppIndexConfigService configService) {
        this.configService = configService;
    }

    /**
     * Returns the minimal config the frontend needs on startup:
     * {@code appTitle} and {@code applicationName}.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        String appTitle = fallbackAppTitle;
        String logoUrl = DEFAULT_LOGO_URL;
        String logoAlt = DEFAULT_LOGO_ALT;
        boolean showLogo = true;
        String faviconUrl = DEFAULT_FAVICON_URL;

        if (configService != null) {
            AppIndexConfig config = configService.getActualConfiguration();
            if (config != null) {
                if (config.getAppTitle() != null && !config.getAppTitle().isBlank()) {
                    appTitle = config.getAppTitle();
                }
                if (config.getLogoUrl() != null && !config.getLogoUrl().isBlank()) {
                    logoUrl = config.getLogoUrl();
                }
                if (config.getLogoAlt() != null && !config.getLogoAlt().isBlank()) {
                    logoAlt = config.getLogoAlt();
                }
                if (config.getShowLogo() != null) {
                    showLogo = config.getShowLogo();
                }
                if (config.getFaviconUrl() != null && !config.getFaviconUrl().isBlank()) {
                    faviconUrl = config.getFaviconUrl();
                }
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("appTitle", appTitle);
        response.put("applicationName", applicationName);
        response.put("logoUrl", logoUrl);
        response.put("logoAlt", logoAlt);
        response.put("showLogo", showLogo);
        response.put("faviconUrl", faviconUrl);
        return ResponseEntity.ok(response);
    }
}
