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

package ai.kompile.app.web.controllers; // New package

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import jakarta.annotation.PostConstruct;

@Controller
public class SpaForwardController {

    private static final Logger logger = LoggerFactory.getLogger(SpaForwardController.class);

    public SpaForwardController() {
        // Spring will log "SpaForwardController : Mapped HTTP GET /" if TRACE is on for mvc.method.annotation.RequestMappingHandlerMapping
    }

    @PostConstruct
    public void init() {
        logger.info("SpaForwardController initialized and active for forwarding '/' to index.html");
    }

    @GetMapping("/")
    public String forwardRootToIndexHtml() {
        logger.trace("Forwarding root path '/' to internal /index.html resource");
        return "forward:/index.html";
    }
}