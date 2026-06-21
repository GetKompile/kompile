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

import { Component, OnInit, OnDestroy, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { ConfigService } from '../../services/config.service';
import { environment } from '../../../environments/environment';

/**
 * Modular, white-labelable application branding shown in the top-left of the
 * RAG console header (and reusable elsewhere, e.g. the footer).
 *
 * <p>Renders a bundled logo alongside the application name. Every value is
 * overridable, in increasing order of precedence:</p>
 * <ol>
 *   <li>Build-time defaults from {@code environment.branding} +
 *       {@code environment.appTitle}.</li>
 *   <li>Runtime values from the backend {@code /api/config} endpoint
 *       (via {@link ConfigService}) — no frontend rebuild required. See
 *       {@code FrontendConfigController}, which sources branding from the
 *       kompile app config ({@code app-index-config.json}).</li>
 *   <li>Per-usage {@code @Input()} overrides (e.g. hide the logo in the footer
 *       with {@code <app-branding [showLogo]="false">}).</li>
 * </ol>
 *
 * <p>The default logo asset is bundled with kompile-app-main at
 * {@code src/assets/branding/kompile-logo.svg}. To white-label, replace that
 * file or point {@code logoUrl} at another asset.</p>
 */
@Component({
  selector: 'app-branding',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './branding.component.html',
  styleUrls: ['./branding.component.css']
})
export class BrandingComponent implements OnInit, OnDestroy {
  /** Override the resolved logo URL for this instance. */
  @Input() logoUrl?: string;
  /** Override the resolved title for this instance. */
  @Input() title?: string;
  /** Override logo visibility for this instance. */
  @Input() showLogo?: boolean;

  /** Effective values, resolved from @Input overrides -> backend config -> env defaults. */
  resolvedTitle: string = environment.appTitle;
  resolvedLogoUrl: string = environment.branding.logoUrl;
  resolvedLogoAlt: string = environment.branding.logoAlt;
  resolvedShowLogo: boolean = environment.branding.showLogo;

  /** Set when the logo asset fails to load, so we gracefully fall back to the name only. */
  logoFailed = false;

  private sub?: Subscription;

  constructor(private configService: ConfigService) {}

  ngOnInit(): void {
    this.sub = this.configService.config$.subscribe(cfg => {
      this.resolvedTitle = this.title ?? cfg.appTitle ?? environment.appTitle;
      this.resolvedLogoUrl = this.logoUrl ?? cfg.logoUrl ?? environment.branding.logoUrl;
      this.resolvedLogoAlt = cfg.logoAlt ?? environment.branding.logoAlt ?? this.resolvedTitle;
      this.resolvedShowLogo = this.showLogo ?? cfg.showLogo ?? environment.branding.showLogo;
    });
  }

  /** Whether to actually render the logo image. */
  get displayLogo(): boolean {
    return this.resolvedShowLogo && !!this.resolvedLogoUrl && !this.logoFailed;
  }

  /** A broken/missing logo asset must not blank out the brand — fall back to text. */
  onLogoError(): void {
    this.logoFailed = true;
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }
}
