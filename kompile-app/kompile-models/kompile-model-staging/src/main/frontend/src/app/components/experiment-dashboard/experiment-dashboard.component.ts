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

import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ExperimentService } from '../../services/experiment.service';
import { Experiment, getExperimentStatusColor, getExperimentStatusIcon } from '../../models/api-models';

@Component({
  selector: 'app-experiment-dashboard',
  standalone: false,
  templateUrl: './experiment-dashboard.component.html',
  styleUrls: ['./experiment-dashboard.component.css']
})
export class ExperimentDashboardComponent implements OnInit {

  experiments: Experiment[] = [];
  loading = false;

  // Create form
  showCreateForm = false;
  newName = '';
  newDescription = '';
  newSuiteId = '';

  getStatusColor = getExperimentStatusColor;
  getStatusIcon = getExperimentStatusIcon;

  constructor(
    private experimentService: ExperimentService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadExperiments();
  }

  loadExperiments(): void {
    this.loading = true;
    this.experimentService.listExperiments().subscribe({
      next: (experiments) => {
        this.experiments = experiments;
        this.loading = false;
      },
      error: (err) => {
        this.snackBar.open('Failed to load experiments: ' + err.message, 'Close', { duration: 5000 });
        this.loading = false;
      }
    });
  }

  createExperiment(): void {
    if (!this.newName || !this.newSuiteId) {
      this.snackBar.open('Name and Suite ID are required', 'Close', { duration: 3000 });
      return;
    }

    this.experimentService.createExperiment({
      name: this.newName,
      description: this.newDescription,
      suiteId: this.newSuiteId
    }).subscribe({
      next: (experiment) => {
        this.snackBar.open('Experiment created', 'Close', { duration: 3000 });
        this.showCreateForm = false;
        this.newName = '';
        this.newDescription = '';
        this.newSuiteId = '';
        this.loadExperiments();
      },
      error: (err) => {
        this.snackBar.open('Failed to create experiment: ' + err.message, 'Close', { duration: 5000 });
      }
    });
  }

  deleteExperiment(id: string, event: Event): void {
    event.stopPropagation();
    if (confirm('Delete this experiment and all its runs?')) {
      this.experimentService.deleteExperiment(id).subscribe({
        next: () => {
          this.snackBar.open('Experiment deleted', 'Close', { duration: 3000 });
          this.loadExperiments();
        },
        error: (err) => {
          this.snackBar.open('Failed to delete: ' + err.message, 'Close', { duration: 5000 });
        }
      });
    }
  }

  openExperiment(id: string): void {
    this.router.navigate(['/experiments', id]);
  }
}
