import { Component, OnInit } from '@angular/core';
import { AutoConfigureService, AutoConfigureResult } from '../../../services/auto-configure.service';

@Component({
  selector: 'app-auto-configure',
  standalone: false,
  templateUrl: './auto-configure.component.html',
  styleUrls: ['./auto-configure.component.css']
})
export class AutoConfigureComponent implements OnInit {
  detectResult: AutoConfigureResult | null = null;
  applyResult: AutoConfigureResult | null = null;
  loading = false;
  applying = false;
  error: string | null = null;
  message: string | null = null;
  hasLocalEmbedding = true;

  constructor(private svc: AutoConfigureService) {}

  ngOnInit(): void { this.detect(); }

  detect(): void {
    this.loading = true;
    this.error = null;
    this.applyResult = null;
    this.svc.detect(this.hasLocalEmbedding).subscribe({
      next: result => { this.detectResult = result; this.loading = false; },
      error: err => { this.error = err.error?.message || err.message; this.loading = false; }
    });
  }

  apply(): void {
    this.applying = true;
    this.error = null;
    this.svc.apply(this.hasLocalEmbedding).subscribe({
      next: result => {
        this.applyResult = result;
        this.message = 'Configuration applied successfully';
        this.applying = false;
      },
      error: err => { this.error = err.error?.message || err.message; this.applying = false; }
    });
  }

  getHardwareKeys(): string[] {
    return this.detectResult?.hardware ? Object.keys(this.detectResult.hardware) : [];
  }

  getRecommendedKeys(): string[] {
    const r = this.detectResult?.recommended || this.applyResult?.applied;
    return r ? Object.keys(r) : [];
  }

  formatValue(v: any): string {
    if (v == null) return '-';
    if (typeof v === 'object') return JSON.stringify(v);
    return String(v);
  }
}
