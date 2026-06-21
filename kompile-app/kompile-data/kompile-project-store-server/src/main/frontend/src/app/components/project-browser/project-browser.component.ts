import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ProjectStoreService, ProjectSummary } from '../../services/project-store.service';

/**
 * Lists the hosted projects as a searchable grid of cards. Selecting a card opens the
 * project detail / download view via the {@link open} output.
 */
@Component({
  selector: 'app-project-browser',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatTooltipModule
  ],
  templateUrl: './project-browser.component.html',
  styleUrls: ['./project-browser.component.css']
})
export class ProjectBrowserComponent implements OnInit {
  @Output() open = new EventEmitter<ProjectSummary>();

  projects: ProjectSummary[] = [];
  loading = false;
  error: string | null = null;
  search = '';

  // Origin-derived URLs/commands for the "no projects yet" git instructions.
  readonly gitBase = `${window.location.origin}/git`;
  readonly createExample =
    `curl -X POST ${window.location.origin}/api/projects ` +
    `-H "Content-Type: application/json" ` +
    `-d '{"namespace":"me","slug":"my-project"}'`;
  readonly pushExample =
    `git remote add kompile ${window.location.origin}/git/me/my-project.git\n` +
    `git push kompile main`;

  constructor(private store: ProjectStoreService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.error = null;
    this.store.listProjects().subscribe({
      next: (projects) => {
        this.projects = projects ?? [];
        this.loading = false;
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to load projects.';
        this.loading = false;
      }
    });
  }

  get filtered(): ProjectSummary[] {
    const q = this.search.trim().toLowerCase();
    if (!q) {
      return this.projects;
    }
    return this.projects.filter(p =>
      p.fullName.toLowerCase().includes(q) ||
      (p.description ?? '').toLowerCase().includes(q) ||
      (p.repoType ?? '').toLowerCase().includes(q) ||
      p.namespace.toLowerCase().includes(q));
  }

  trackByFullName(_: number, p: ProjectSummary): string {
    return p.fullName;
  }

  iconFor(repoType: string): string {
    switch ((repoType || '').toLowerCase()) {
      case 'model': return 'smart_toy';
      case 'dataset': return 'dataset';
      case 'space': return 'rocket_launch';
      default: return 'folder_special';
    }
  }
}
