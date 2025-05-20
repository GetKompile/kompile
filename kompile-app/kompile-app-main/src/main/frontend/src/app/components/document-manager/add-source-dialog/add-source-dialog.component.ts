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

import { Component, Inject, OnInit, ChangeDetectionStrategy, ChangeDetectorRef, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators, FormControl } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatRadioModule } from '@angular/material/radio';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Subscription } from 'rxjs';

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

// Interface to define the structure and types of the form controls
interface AddSourceFormModel {
  sourceType: FormControl<'file' | 'url'>; // Non-nullable, as it always has a value
  urlInput: FormControl<string | null>; // Can be null or empty string
  fileNameInput: FormControl<string | null>; // Can be null or empty string
  loaderSelect: FormControl<string | null>; // Can be null or empty string (for default)
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
export class AddSourceDialogComponent implements OnInit, OnDestroy {
  addSourceForm: FormGroup<AddSourceFormModel>;
  selectedFile: File | null = null;
  fileErrorMessage: string | null = null;
  availableLoaders: LoaderInfo[] = [];
  isSubmitting: boolean = false;

  private sourceTypeSubscription: Subscription | undefined;

  constructor(
    public dialogRef: MatDialogRef<AddSourceDialogComponent, AddSourceDialogResult>,
    @Inject(MAT_DIALOG_DATA) public data: AddSourceDialogData,
    private fb: FormBuilder,
    private cdr: ChangeDetectorRef
  ) {
    this.availableLoaders = this.data.availableLoaders;

    // Initialize the form with strong types using FormControl instances
    this.addSourceForm = this.fb.group<AddSourceFormModel>({
      sourceType: new FormControl<'file' | 'url'>('file', { nonNullable: true, validators: Validators.required }),
      urlInput: new FormControl('', { validators: [Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)] }),
      fileNameInput: new FormControl(''),
      loaderSelect: new FormControl('')
    });
  }

  ngOnInit(): void {
    this.updateValidatorsBasedOnSourceType(); // Initial call to set validators based on default sourceType
    const sourceTypeControl = this.addSourceForm.controls.sourceType; // Access via controls for typed control
    if (sourceTypeControl) {
      this.sourceTypeSubscription = sourceTypeControl.valueChanges.subscribe(() => {
        this.updateValidatorsBasedOnSourceType();
      });
    }
  }

  private updateValidatorsBasedOnSourceType(): void {
    const sourceType = this.addSourceForm.controls.sourceType.value;
    const urlControl = this.addSourceForm.controls.urlInput;

    // Clear previous file selection and error if not 'file' type
    if (sourceType !== 'file') {
      this.selectedFile = null;
      this.fileErrorMessage = null;
      const fileInput = document.getElementById('dialogInternalFileInput') as HTMLInputElement;
      if (fileInput) fileInput.value = '';
    }


    if (sourceType === 'file') {
      urlControl.clearValidators();
      // Keep pattern validator if it should always apply, or clear it too.
      // For now, assuming pattern is only for URL, so it's added back for 'url' type.
      urlControl.setValidators([Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)]);
      urlControl.setValue(''); // Reset URL input when switching to file
    } else { // 'url'
      urlControl.setValidators([
        Validators.required,
        Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)
      ]);
    }
    urlControl.updateValueAndValidity();
    this.cdr.detectChanges();
  }

  onFileSelectedChange(event: Event): void {
    const element = event.target as HTMLInputElement;
    this.fileErrorMessage = null; // Reset error message
    if (element.files && element.files.length > 0) {
      this.selectedFile = element.files!![0];
      // Automatically switch radio button to 'file' if not already selected
      if (this.addSourceForm.controls.sourceType.value !== 'file') {
        this.addSourceForm.controls.sourceType.setValue('file');
        // updateValidatorsBasedOnSourceType will be called by the valueChanges subscription
      }
    } else {
      this.selectedFile = null;
    }
    this.cdr.detectChanges(); // Update view with file info or error
  }

  onCancelDialog(): void {
    if (!this.isSubmitting) {
      this.dialogRef.close();
    }
  }

  isDialogFormValid(): boolean {
    const sourceType = this.addSourceForm.controls.sourceType.value;
    if (sourceType === 'file') {
      if (!this.selectedFile) {
        this.fileErrorMessage = "A file must be selected to upload.";
        this.cdr.detectChanges();
        return false;
      }
      this.fileErrorMessage = null; // Clear error if file is selected
      this.cdr.detectChanges();
      return true;
    } else if (sourceType === 'url') {
      // Check validity of the urlInput control itself
      return this.addSourceForm.controls.urlInput.valid;
    }
    // Should not be reached if sourceType is required and has a valid value
    return false;
  }

  onSubmitDialog(): void {
    // Mark all controls as touched to trigger validation messages
    Object.values(this.addSourceForm.controls).forEach(control => {
      control.markAsTouched();
    });

    if (!this.isDialogFormValid()) {
      this.cdr.detectChanges(); // Ensure UI updates with validation messages
      return;
    }

    this.isSubmitting = true;
    this.cdr.detectChanges();

    const formValues = this.addSourceForm.getRawValue(); // Use getRawValue to get values even from disabled controls if any

    const result: AddSourceDialogResult = {
      selectedLoader: formValues.loaderSelect || undefined
    };

    if (formValues.sourceType === 'file' && this.selectedFile) {
      result.file = this.selectedFile;
    } else if (formValues.sourceType === 'url') {
      result.url = formValues.urlInput ?? undefined; // Ensure undefined if null/empty
      result.fileName = formValues.fileNameInput || undefined;
    }
    this.dialogRef.close(result);
  }

  ngOnDestroy(): void {
    if (this.sourceTypeSubscription) {
      this.sourceTypeSubscription.unsubscribe();
    }
  }
}
