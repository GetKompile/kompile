import { Component, OnInit } from '@angular/core';
import { PipelineScheduleService, ScheduleInfo } from '../../../services/pipeline-schedule.service';

@Component({
  standalone: false,
  selector: 'app-pipeline-schedules',
  templateUrl: './pipeline-schedules.component.html',
  styleUrls: ['./pipeline-schedules.component.css']
})
export class PipelineSchedulesComponent implements OnInit {
  schedules: ScheduleInfo[] = [];
  loading = true;
  error: string | null = null;
  message: string | null = null;

  // Create form
  showCreateForm = false;
  newScheduleType: 'staleness-check' | 're-ingestion' | 'eval-suite' = 'staleness-check';
  newCron = '0 0 * * *';
  newFactSheetId: number = 1;
  newSuiteId = '';
  creating = false;

  constructor(private scheduleService: PipelineScheduleService) {}

  ngOnInit(): void {
    this.loadSchedules();
  }

  loadSchedules(): void {
    this.loading = true;
    this.error = null;
    this.scheduleService.listSchedules().subscribe({
      next: list => {
        this.schedules = list;
        this.loading = false;
      },
      error: () => {
        this.error = 'Pipeline scheduling service not available';
        this.loading = false;
      }
    });
  }

  createSchedule(): void {
    this.creating = true;
    let obs;
    switch (this.newScheduleType) {
      case 'staleness-check':
        obs = this.scheduleService.createStalenessCheck(this.newCron, this.newFactSheetId);
        break;
      case 're-ingestion':
        obs = this.scheduleService.createReIngestion(this.newCron, this.newFactSheetId);
        break;
      case 'eval-suite':
        obs = this.scheduleService.createEvalSuite(this.newCron, this.newSuiteId);
        break;
    }
    obs.subscribe({
      next: info => {
        this.schedules.push(info);
        this.creating = false;
        this.showCreateForm = false;
        this.showMessage(`Schedule created: ${info.scheduleId}`);
      },
      error: e => {
        this.creating = false;
        this.error = e.error?.error || 'Failed to create schedule';
      }
    });
  }

  deleteSchedule(id: string): void {
    this.scheduleService.deleteSchedule(id).subscribe({
      next: () => {
        this.schedules = this.schedules.filter(s => s.scheduleId !== id);
        this.showMessage(`Deleted: ${id}`);
      },
      error: () => this.error = 'Failed to delete schedule'
    });
  }

  getTypeIcon(type: string): string {
    switch (type) {
      case 'staleness-check': return 'update';
      case 're-ingestion': return 'replay';
      case 'eval-suite': return 'assessment';
      default: return 'schedule';
    }
  }

  private showMessage(msg: string): void {
    this.message = msg;
    setTimeout(() => this.message = null, 4000);
  }
}
