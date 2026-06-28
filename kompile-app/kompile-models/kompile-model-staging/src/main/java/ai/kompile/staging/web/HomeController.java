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

package ai.kompile.staging.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Redirects the root URL to the Angular dashboard.
 * The Angular SPA is the authoritative UI for this service; the dashboard
 * is declared as the default route in app.module.ts:
 *   { path: '', redirectTo: '/dashboard', pathMatch: 'full' }
 * Adding an explicit server-side redirect prevents the 404 that occurs when
 * Spring's static-resource handler cannot resolve "/" to a concrete file.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String root() {
        return "redirect:/dashboard";
    }
}
