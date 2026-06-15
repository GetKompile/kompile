/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  formatFileSize,
  getRecommendedHeapSize,
  getGpuMemoryTierLabel,
  getGpuMemoryTierClass,
  getVectorPopulationPhaseDisplayName,
  getVectorPopulationPhaseIcon,
  getVectorPopulationStatusColor,
  getSourceViewModeIcon,
  getEventSeverity
} from './api-models';

describe('api-models utility functions', () => {

  // ═══════════════════════ formatFileSize ═══════════════════════

  describe('formatFileSize()', () => {
    it('should return "0 B" for 0', () => {
      expect(formatFileSize(0)).toBe('0 B');
    });

    it('should format bytes < 1024', () => {
      expect(formatFileSize(512)).toBe('512 B');
    });

    it('should format kilobytes', () => {
      expect(formatFileSize(1024)).toBe('1 KB');
    });

    it('should format megabytes', () => {
      expect(formatFileSize(1048576)).toBe('1 MB');
    });

    it('should format gigabytes', () => {
      expect(formatFileSize(1073741824)).toBe('1 GB');
    });

    it('should format terabytes', () => {
      expect(formatFileSize(1099511627776)).toBe('1 TB');
    });

    it('should format fractional sizes', () => {
      expect(formatFileSize(1536)).toBe('1.5 KB');
    });

    it('should handle NaN gracefully', () => {
      // Math.log(NaN) → NaN, Math.floor(NaN) → NaN, sizes[NaN] → undefined
      const result = formatFileSize(NaN);
      expect(result).toContain('NaN');
    });

    it('should handle negative values', () => {
      // Math.log(-1) → NaN → same as NaN case
      const result = formatFileSize(-1);
      expect(result).toContain('NaN');
    });

    it('should handle very large values', () => {
      // 5 TB
      const result = formatFileSize(5497558138880);
      expect(result).toBe('5 TB');
    });

    it('should handle 1 byte', () => {
      expect(formatFileSize(1)).toBe('1 B');
    });
  });

  // ═══════════════════════ getRecommendedHeapSize ═══════════════════════

  describe('getRecommendedHeapSize()', () => {
    it('should return "1g" for 0 MB', () => {
      expect(getRecommendedHeapSize(0)).toBe('1g');
    });

    it('should return "1g" for negative MB', () => {
      expect(getRecommendedHeapSize(-1)).toBe('1g');
    });

    it('should return "1g" for exactly 4096 MB', () => {
      expect(getRecommendedHeapSize(4096)).toBe('1g');
    });

    it('should return "2g" for 4097 MB', () => {
      expect(getRecommendedHeapSize(4097)).toBe('2g');
    });

    it('should return "2g" for exactly 8192 MB', () => {
      expect(getRecommendedHeapSize(8192)).toBe('2g');
    });

    it('should return "4g" for 8193 MB', () => {
      expect(getRecommendedHeapSize(8193)).toBe('4g');
    });

    it('should return "4g" for exactly 16384 MB', () => {
      expect(getRecommendedHeapSize(16384)).toBe('4g');
    });

    it('should return "8g" for 16385 MB', () => {
      expect(getRecommendedHeapSize(16385)).toBe('8g');
    });

    it('should return "8g" for exactly 32768 MB', () => {
      expect(getRecommendedHeapSize(32768)).toBe('8g');
    });

    it('should return "12g" for > 32768 MB', () => {
      expect(getRecommendedHeapSize(32769)).toBe('12g');
    });

    it('should return "12g" for very large value', () => {
      expect(getRecommendedHeapSize(1000000)).toBe('12g');
    });
  });

  // ═══════════════════════ getGpuMemoryTierLabel ═══════════════════════

  describe('getGpuMemoryTierLabel()', () => {
    it('should return "Low VRAM" for 0', () => {
      expect(getGpuMemoryTierLabel(0)).toBe('Low VRAM (< 4GB)');
    });

    it('should return "Low VRAM" for negative', () => {
      expect(getGpuMemoryTierLabel(-100)).toBe('Low VRAM (< 4GB)');
    });

    it('should return "Medium VRAM" at boundary 4096', () => {
      expect(getGpuMemoryTierLabel(4096)).toBe('Medium VRAM (4-8GB)');
    });

    it('should return "High VRAM" at boundary 8192', () => {
      expect(getGpuMemoryTierLabel(8192)).toBe('High VRAM (8-16GB)');
    });

    it('should return "Very High VRAM" at boundary 16384', () => {
      expect(getGpuMemoryTierLabel(16384)).toBe('Very High VRAM (> 16GB)');
    });

    it('should return "Very High VRAM" for very large value', () => {
      expect(getGpuMemoryTierLabel(99999)).toBe('Very High VRAM (> 16GB)');
    });
  });

  describe('getGpuMemoryTierClass()', () => {
    it('should return "vram-low" for 0', () => {
      expect(getGpuMemoryTierClass(0)).toBe('vram-low');
    });

    it('should return "vram-medium" at 4096', () => {
      expect(getGpuMemoryTierClass(4096)).toBe('vram-medium');
    });

    it('should return "vram-high" at 8192', () => {
      expect(getGpuMemoryTierClass(8192)).toBe('vram-high');
    });

    it('should return "vram-very-high" at 16384', () => {
      expect(getGpuMemoryTierClass(16384)).toBe('vram-very-high');
    });
  });

  // ═══════════════════════ getVectorPopulationPhaseDisplayName ═══════════════════════

  describe('getVectorPopulationPhaseDisplayName()', () => {
    it('should return "Loading Documents" for LOADING', () => {
      expect(getVectorPopulationPhaseDisplayName('LOADING')).toBe('Loading Documents');
    });

    it('should return "Generating Embeddings" for EMBEDDING', () => {
      expect(getVectorPopulationPhaseDisplayName('EMBEDDING')).toBe('Generating Embeddings');
    });

    it('should return "Writing to Vector Store" for INDEXING', () => {
      expect(getVectorPopulationPhaseDisplayName('INDEXING')).toBe('Writing to Vector Store');
    });

    it('should return "Completed" for COMPLETED', () => {
      expect(getVectorPopulationPhaseDisplayName('COMPLETED')).toBe('Completed');
    });

    it('should return "Failed" for FAILED', () => {
      expect(getVectorPopulationPhaseDisplayName('FAILED')).toBe('Failed');
    });

    it('should return "Cancelled" for CANCELLED', () => {
      expect(getVectorPopulationPhaseDisplayName('CANCELLED')).toBe('Cancelled');
    });

    it('should return the input string for unknown phase', () => {
      expect(getVectorPopulationPhaseDisplayName('UNKNOWN')).toBe('UNKNOWN');
    });

    it('should return empty string for empty string input', () => {
      expect(getVectorPopulationPhaseDisplayName('')).toBe('');
    });
  });

  // ═══════════════════════ getVectorPopulationPhaseIcon ═══════════════════════

  describe('getVectorPopulationPhaseIcon()', () => {
    it('should return "folder_open" for LOADING', () => {
      expect(getVectorPopulationPhaseIcon('LOADING')).toBe('folder_open');
    });

    it('should return "memory" for EMBEDDING', () => {
      expect(getVectorPopulationPhaseIcon('EMBEDDING')).toBe('memory');
    });

    it('should return "storage" for INDEXING', () => {
      expect(getVectorPopulationPhaseIcon('INDEXING')).toBe('storage');
    });

    it('should return "info" for unknown phase', () => {
      expect(getVectorPopulationPhaseIcon('WHATEVER')).toBe('info');
    });

    it('should return "info" for empty string', () => {
      expect(getVectorPopulationPhaseIcon('')).toBe('info');
    });
  });

  // ═══════════════════════ getVectorPopulationStatusColor ═══════════════════════

  describe('getVectorPopulationStatusColor()', () => {
    it('should return "gray" for PENDING', () => {
      expect(getVectorPopulationStatusColor('PENDING')).toBe('gray');
    });

    it('should return "blue" for IN_PROGRESS', () => {
      expect(getVectorPopulationStatusColor('IN_PROGRESS')).toBe('blue');
    });

    it('should return "green" for COMPLETED', () => {
      expect(getVectorPopulationStatusColor('COMPLETED')).toBe('green');
    });

    it('should return "red" for FAILED', () => {
      expect(getVectorPopulationStatusColor('FAILED')).toBe('red');
    });

    it('should return "orange" for CANCELLED', () => {
      expect(getVectorPopulationStatusColor('CANCELLED')).toBe('orange');
    });

    it('should return "gray" for unknown status', () => {
      expect(getVectorPopulationStatusColor('UNKNOWN')).toBe('gray');
    });

    it('should return "gray" for empty string', () => {
      expect(getVectorPopulationStatusColor('')).toBe('gray');
    });
  });

  // ═══════════════════════ getSourceViewModeIcon ═══════════════════════

  describe('getSourceViewModeIcon()', () => {
    it('should return "description" for TEXT', () => {
      expect(getSourceViewModeIcon('TEXT' as any)).toBe('description');
    });

    it('should return "image" for IMAGE', () => {
      expect(getSourceViewModeIcon('IMAGE' as any)).toBe('image');
    });

    it('should return "picture_as_pdf" for EMBEDDED', () => {
      expect(getSourceViewModeIcon('EMBEDDED' as any)).toBe('picture_as_pdf');
    });

    it('should return "download" for DOWNLOAD_ONLY', () => {
      expect(getSourceViewModeIcon('DOWNLOAD_ONLY' as any)).toBe('download');
    });

    it('should return "insert_drive_file" for unknown mode', () => {
      expect(getSourceViewModeIcon('UNKNOWN' as any)).toBe('insert_drive_file');
    });
  });

  // ═══════════════════════ getEventSeverity ═══════════════════════

  describe('getEventSeverity()', () => {
    it('should return "success" for COMPLETED', () => {
      expect(getEventSeverity('COMPLETED' as any)).toBe('success');
    });

    it('should return "success" for PHASE_COMPLETED', () => {
      expect(getEventSeverity('PHASE_COMPLETED' as any)).toBe('success');
    });

    it('should return "success" for RESTART_SUCCEEDED', () => {
      expect(getEventSeverity('RESTART_SUCCEEDED' as any)).toBe('success');
    });

    it('should return "warning" for WARNING', () => {
      expect(getEventSeverity('WARNING' as any)).toBe('warning');
    });

    it('should return "warning" for RESTART_SCHEDULED', () => {
      expect(getEventSeverity('RESTART_SCHEDULED' as any)).toBe('warning');
    });

    it('should return "warning" for MEMORY_ANALYSIS', () => {
      expect(getEventSeverity('MEMORY_ANALYSIS' as any)).toBe('warning');
    });

    it('should return "error" for ERROR', () => {
      expect(getEventSeverity('ERROR' as any)).toBe('error');
    });

    it('should return "error" for FAILED', () => {
      expect(getEventSeverity('FAILED' as any)).toBe('error');
    });

    it('should return "info" for unknown event type', () => {
      expect(getEventSeverity('UNKNOWN' as any)).toBe('info');
    });
  });
});
