import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ProjectBrowserComponent } from './components/project-browser/project-browser.component';
import { ProjectDetailComponent } from './components/project-detail/project-detail.component';
import { ProjectSummary } from './services/project-store.service';

/** Selected project coordinates, or null while showing the browser. */
interface Selection {
  namespace: string;
  slug: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, ProjectBrowserComponent, ProjectDetailComponent],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent {
  selected: Selection | null = null;

  open(project: ProjectSummary): void {
    this.selected = { namespace: project.namespace, slug: project.slug };
    window.scrollTo({ top: 0 });
  }

  back(): void {
    this.selected = null;
  }
}
