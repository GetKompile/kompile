/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

import { Component, Inject, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatRadioModule } from '@angular/material/radio';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar'; // For loading state in dialog

import { LoaderInfo } from '../../../models/api-models';

export interface AddSourceDialogData {
  availableLoaders: LoaderInfo[];
}

export interface AddSourceDialogResult {
  file?: File;
  url?: string;
  fileName?: string;
  selectedLoader?: string;
}

@Component({
  selector: 'app-add-source-dialog',
  templateUrl: './add-source-dialog.component.html',
  styleUrls: ['./add-source-dialog.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatRadioModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule
  ]
})
export class AddSourceDialogComponent implements OnInit {
  addSourceForm: FormGroup;
  selectedFile: File | null = null;
  fileErrorMessage: string | null = null;
  availableLoaders: LoaderInfo[] = [];
  isSubmitting: boolean = false; // For dialog's own loading state

  constructor(
    public dialogRef: MatDialogRef<AddSourceDialogComponent, AddSourceDialogResult>,
    @Inject(MAT_DIALOG_DATA) public data: AddSourceDialogData,
    private fb: FormBuilder
  ) {
    this.availableLoaders = this.data.availableLoaders;
    this.addSourceForm = this.fb.group({
      sourceType: ['file', Validators.required],
      urlInput: ['', [Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)]],
      fileNameInput: [''],
      loaderSelect: ['']
    });
  }

  ngOnInit(): void {
    this.updateValidatorsBasedOnSourceType();
    this.addSourceForm.get('sourceType')?.valueChanges.subscribe(() => {
      this.updateValidatorsBasedOnSourceType();
    });
  }

  private updateValidatorsBasedOnSourceType(): void {
    const sourceType = this.addSourceForm.get('sourceType')?.value;
    const urlControl = this.addSourceForm.get('urlInput');

    if (sourceType === 'file') {
      urlControl?.clearValidators();
      urlControl?.setValue('');
      urlControl?.updateValueAndValidity();
    } else { // url
      urlControl?.setValidators([Validators.required, Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)]);
      urlControl?.updateValueAndValidity();
      this.selectedFile = null;
      this.fileErrorMessage = null;
      const fileInput = document.getElementById('dialogInternalFileInput') as HTMLInputElement;
      if (fileInput) fileInput.value = '';
    }
  }

  onFileSelectedChange(event: Event): void {
    const element = event.target as HTMLInputElement;
    this.fileErrorMessage = null;
    if (element.files && element.files.length > 0) {
      this.selectedFile = element.files[0];
      if (this.addSourceForm.get('sourceType')?.value !== 'file') {
        this.addSourceForm.get('sourceType')?.setValue('file');
      }
    } else {
      this.selectedFile = null;
    }
  }

  onCancelDialog(): void {
    if (!this.isSubmitting) {
      this.dialogRef.close();
    }
  }

  isCurrentFormValid(): boolean {
    const sourceType = this.addSourceForm.get('sourceType')?.value;
    if (sourceType === 'file') {
      if (!this.selectedFile) {
        this.fileErrorMessage = "A file must be selected to upload.";
        return false;
      }
      this.fileErrorMessage = null;
      return true;
    } else if (sourceType === 'url') {
      return this.addSourceForm.get('urlInput')?.valid ?? false;
    }
    return false;
  }

  onSubmitDialog(): void {
    if (!this.isCurrentFormValid()) {
      if (this.addSourceForm.get('sourceType')?.value === 'url') {
        this.addSourceForm.get('urlInput')?.markAsTouched(); // Show mat-error
      }
      // For file type, fileErrorMessage is handled by isCurrentFormValid()
      return;
    }

    this.isSubmitting = true; // Indicate submission is in progress

    const result: AddSourceDialogResult = {
      selectedLoader: this.addSourceForm.value.loaderSelect || undefined
    };

    if (this.addSourceForm.value.sourceType === 'file' && this.selectedFile) {
      result.file = this.selectedFile;
    } else if (this.addSourceForm.value.sourceType === 'url') {
      result.url = this.addSourceForm.value.urlInput;
      result.fileName = this.addSourceForm.value.fileNameInput || undefined;
    }
    // Simulate a short delay if needed for UX, or let the caller handle loading state
    // setTimeout(() => { // Example, remove if service call is quick
    this.dialogRef.close(result);
    //      this.isSubmitting = false;
    // }, 500);
    // Note: The actual DocumentManagerComponent will handle the service call and its loading state.
    // The dialog's isSubmitting is primarily for disabling its own buttons during its brief lifetime.
  }
}
