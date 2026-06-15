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

import { Component, OnInit, ViewChild } from '@angular/core';
import { MatTabGroup } from '@angular/material/tabs';

@Component({
  standalone: false,
  selector: 'app-unified-data-management',
  templateUrl: './unified-data-management.component.html',
  styleUrls: ['./unified-data-management.component.css']
})
export class UnifiedDataManagementComponent implements OnInit {
  @ViewChild('tabGroup') tabGroup!: MatTabGroup;

  selectedTabIndex = 0;

  constructor() {}

  ngOnInit(): void {
    // Initialize any shared state if needed
  }

  onTabChange(index: number): void {
    this.selectedTabIndex = index;
  }
}
