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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { Subscription } from 'rxjs';
import { environment } from '../environments/environment';
import { ConfigService } from './services/config.service';

// Define a type for the possible tab values
export type ActiveTabType = 'unifiedChat' | 'search' | 'mcp' | 'orchestrator' | 'modelDebug' | 'batchConfig' | 'subprocessConfig';

@Component({
  standalone: false,
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, OnDestroy {
  title = environment.appTitle; // Default from environment, will be updated from backend
  activeTab: ActiveTabType = 'unifiedChat'; // Use unified chat by default
  private configSubscription?: Subscription;

  constructor(private configService: ConfigService) {}

  ngOnInit(): void {
    // Subscribe to config updates from the backend
    this.configSubscription = this.configService.config$.subscribe(config => {
      this.title = config.appTitle;
    });
  }

  ngOnDestroy(): void {
    if (this.configSubscription) {
      this.configSubscription.unsubscribe();
    }
  }
}
