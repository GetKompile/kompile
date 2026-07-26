/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';
import { StrengthBand, OpinionDto } from '../../services/kb-grounding.service';

const DOTS: Record<StrengthBand, number> = {
  ESTABLISHED: 5,
  HIGH: 4,
  PROBABLE: 3,
  SPECULATIVE: 2,
  SUPPRESSED: 1
};

const COLORS: Record<StrengthBand, string> = {
  ESTABLISHED: '#4CAF50',
  HIGH: '#8BC34A',
  PROBABLE: '#FFC107',
  SPECULATIVE: '#FF9800',
  SUPPRESSED: '#F44336'
};

@Component({
  selector: 'app-strength-badge',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  template: `
    <span class="strength-badge" [class]="'band-' + band.toLowerCase()"
          [matTooltip]="tooltip">
      <span *ngFor="let dot of dotArray; let i = index"
            class="dot"
            [class.filled]="i < filledCount"
            [style.color]="i < filledCount ? color : '#ccc'">&#9679;</span>
      <span class="band-label">{{ bandLabel }}</span>
    </span>
  `,
  styleUrls: ['./strength-badge.component.css']
})
export class StrengthBadgeComponent {
  @Input() band: StrengthBand = 'PROBABLE';
  @Input() opinion?: OpinionDto;

  readonly dotArray = [0, 1, 2, 3, 4];

  get filledCount(): number {
    return DOTS[this.band] ?? 3;
  }

  get color(): string {
    return COLORS[this.band] ?? '#f39c12';
  }

  get bandLabel(): string {
    return this.band.replace('_', ' ');
  }

  get tooltip(): string {
    if (this.opinion) {
      return `belief ${this.opinion.belief.toFixed(2)} · disbelief ${this.opinion.disbelief.toFixed(2)}`
        + ` · uncertainty ${this.opinion.uncertainty.toFixed(2)} · expected value ${this.opinion.expectation.toFixed(2)}`;
    }
    return this.bandLabel;
  }
}
