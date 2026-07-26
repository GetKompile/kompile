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

import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { GraphIoService, ImportResult } from '@shared/services/graph-io.service';

interface FormatOption { value: string; label: string; }

/**
 * Export/import a fact sheet's graph in interop formats (graph-as-asset Phase 1/9). Export covers
 * JSON, JSON-LD, CSV, GraphML, Cypher, and the real-RDF N-Triples/Turtle; import covers the
 * round-trippable subset (JSON, JSON-LD, CSV, Cypher).
 */
@Component({
  selector: 'app-graph-io-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatFormFieldModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatSnackBarModule
  ],
  templateUrl: './graph-io-panel.component.html',
  styleUrls: ['./graph-io-panel.component.css']
})
export class GraphIoPanelComponent {
  @Input() factSheetId: number | null = null;

  readonly exportFormats: FormatOption[] = [
    { value: 'json', label: 'JSON' },
    { value: 'jsonld', label: 'JSON-LD' },
    { value: 'csv', label: 'CSV (zip)' },
    { value: 'graphml', label: 'GraphML' },
    { value: 'cypher', label: 'Cypher' },
    { value: 'ntriples', label: 'RDF — N-Triples' },
    { value: 'turtle', label: 'RDF — Turtle' }
  ];
  readonly importFormats: FormatOption[] = [
    { value: 'json', label: 'JSON' },
    { value: 'jsonld', label: 'JSON-LD' },
    { value: 'csv', label: 'CSV' },
    { value: 'cypher', label: 'Cypher' }
  ];

  exportFormat = 'json';
  importFormat = 'json';
  file: File | null = null;
  edgesFile: File | null = null;

  exporting = false;
  importing = false;
  lastImport: ImportResult | null = null;

  constructor(private io: GraphIoService, private snackBar: MatSnackBar) {}

  doExport(): void {
    this.exporting = true;
    this.io.export(this.exportFormat, this.factSheetId).subscribe({
      next: (resp) => { this.exporting = false; this.download(resp, `graph.${this.exportFormat}`); },
      error: (e) => { this.exporting = false; this.error('Export failed', e); }
    });
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file = input.files && input.files.length ? input.files[0] : null;
  }

  onEdgesFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.edgesFile = input.files && input.files.length ? input.files[0] : null;
  }

  doImport(): void {
    if (!this.file) {
      return;
    }
    this.importing = true;
    this.lastImport = null;
    this.io.importGraph(this.importFormat, this.file, this.factSheetId, this.edgesFile).subscribe({
      next: (r) => { this.importing = false; this.lastImport = r; this.ok('Import complete'); },
      error: (e) => { this.importing = false; this.error('Import failed', e); }
    });
  }

  private download(resp: HttpResponse<Blob>, fallbackName: string): void {
    const blob = resp.body;
    if (!blob) {
      return;
    }
    const cd = resp.headers.get('Content-Disposition') || '';
    const match = /filename="?([^";]+)"?/.exec(cd);
    const filename = match ? match[1] : fallbackName;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  private ok(msg: string): void {
    this.snackBar.open(msg, 'OK', { duration: 2500 });
  }

  private error(prefix: string, e: any): void {
    this.snackBar.open(`${prefix}: ${e?.error?.message || e?.message || 'error'}`, 'Dismiss', { duration: 5000 });
  }
}
