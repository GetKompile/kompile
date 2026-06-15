import { Component, OnInit } from '@angular/core';
import {
  ExperimentService, ExperimentDto, ExperimentWithRunsDto, ExperimentRunDto,
  EvalDatasetDto, CreateExperimentRequest, AddRunRequest
} from '../../../services/experiment.service';

@Component({
  selector: 'app-experiments',
  standalone: false,
  templateUrl: './experiments.component.html',
  styleUrls: ['./experiments.component.css']
})
export class ExperimentsComponent implements OnInit {
  experiments: ExperimentDto[] = [];
  selectedExperiment: ExperimentWithRunsDto | null = null;
  comparisonData: Record<string, any> | null = null;
  datasets: EvalDatasetDto[] = [];
  loading = false;
  error: string | null = null;
  message: string | null = null;

  // Create form
  showCreateForm = false;
  newName = '';
  newDescription = '';
  newSuiteId = '';
  newDatasetId = '';

  // Add run form
  showAddRun = false;
  runModelId = '';
  runModelVariant = '';
  runModelType = '';

  // Dataset upload
  showDatasetUpload = false;
  datasetName = '';
  datasetDescription = '';
  datasetFile: File | null = null;

  activeTab: 'experiments' | 'datasets' = 'experiments';

  constructor(private svc: ExperimentService) {}

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.loading = true;
    this.error = null;
    Promise.all([
      this.svc.list().toPromise(),
      this.svc.listEvalDatasets().toPromise()
    ]).then(([exps, ds]) => {
      this.experiments = exps || [];
      this.datasets = ds || [];
      this.loading = false;
    }).catch(err => {
      this.error = err.error?.message || err.message || 'Failed to load';
      this.loading = false;
    });
  }

  selectExperiment(exp: ExperimentDto): void {
    this.loading = true;
    this.comparisonData = null;
    this.svc.get(exp.id).subscribe({
      next: data => { this.selectedExperiment = data; this.loading = false; },
      error: err => { this.error = err.message; this.loading = false; }
    });
  }

  createExperiment(): void {
    const req: CreateExperimentRequest = {
      name: this.newName,
      description: this.newDescription || undefined,
      suiteId: this.newSuiteId || undefined,
      datasetId: this.newDatasetId || undefined
    };
    this.svc.create(req).subscribe({
      next: () => { this.message = 'Experiment created'; this.showCreateForm = false; this.newName = ''; this.loadAll(); },
      error: err => this.error = err.error?.message || err.message
    });
  }

  deleteExperiment(id: string): void {
    this.svc.delete(id).subscribe({
      next: () => {
        this.message = 'Experiment deleted';
        if (this.selectedExperiment?.id === id) this.selectedExperiment = null;
        this.loadAll();
      },
      error: err => this.error = err.message
    });
  }

  compare(): void {
    if (!this.selectedExperiment) return;
    this.svc.compare(this.selectedExperiment.id).subscribe({
      next: data => this.comparisonData = data,
      error: err => this.error = err.message
    });
  }

  addRun(): void {
    if (!this.selectedExperiment) return;
    const req: AddRunRequest = {
      modelId: this.runModelId,
      modelVariant: this.runModelVariant || undefined,
      modelType: this.runModelType || undefined
    };
    this.svc.addRun(this.selectedExperiment.id, req).subscribe({
      next: () => {
        this.message = 'Run added';
        this.showAddRun = false;
        this.runModelId = '';
        this.selectExperiment(this.selectedExperiment!);
      },
      error: err => this.error = err.message
    });
  }

  executeRun(run: ExperimentRunDto): void {
    if (!this.selectedExperiment) return;
    this.svc.executeRun(this.selectedExperiment.id, run.id).subscribe({
      next: updated => {
        this.message = `Run ${run.id} executing`;
        this.selectExperiment(this.selectedExperiment!);
      },
      error: err => this.error = err.message
    });
  }

  uploadDataset(): void {
    if (!this.datasetFile || !this.datasetName) return;
    this.svc.createEvalDataset(this.datasetFile, this.datasetName, this.datasetDescription).subscribe({
      next: () => {
        this.message = 'Dataset uploaded';
        this.showDatasetUpload = false;
        this.datasetName = '';
        this.datasetFile = null;
        this.loadAll();
      },
      error: err => this.error = err.message
    });
  }

  onFileSelect(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files?.length) this.datasetFile = input.files[0];
  }

  deleteDataset(id: string): void {
    this.svc.deleteEvalDataset(id).subscribe({
      next: () => { this.message = 'Dataset deleted'; this.loadAll(); },
      error: err => this.error = err.message
    });
  }

  getRunStatusClass(status: string): string {
    switch (status) {
      case 'COMPLETED': return 'run-completed';
      case 'RUNNING': return 'run-running';
      case 'FAILED': return 'run-failed';
      case 'PENDING': return 'run-pending';
      default: return '';
    }
  }
}
