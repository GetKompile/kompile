/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
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
import { MatCheckboxModule } from '@angular/material/checkbox'; // Import MatCheckboxModule
import { Subscription, merge, startWith } from 'rxjs';
import { map } from 'rxjs/operators';

import { LoaderInfo, AddSourceDialogResult } from '../../../models/api-models'; // Ensure AddSourceDialogResult is imported or defined correctly

export interface AddSourceDialogData {
  availableLoaders: LoaderInfo[];
}

// AddSourceDialogResult is now imported from api-models.ts

interface AddSourceFormModel {
  sourceType: FormControl<'file' | 'url'>;
  urlInput: FormControl<string | null>;
  fileNameInput: FormControl<string | null>;
  loaderSelect: FormControl<string | null>;
  rebuildIndex: FormControl<boolean>; // Added for the checkbox
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
    MatProgressBarModule,
    MatCheckboxModule // Add MatCheckboxModule here
  ]
})
export class AddSourceDialogComponent implements OnInit, OnDestroy {
  addSourceForm: FormGroup<AddSourceFormModel>;
  selectedFile: File | null = null;
  fileErrorMessage: string | null = null;
  availableLoaders: LoaderInfo[] = [];
  isSubmitting: boolean = false;
  isSubmitButtonDisabled: boolean = true;

  private subscriptions: Subscription = new Subscription();

  constructor(
    public dialogRef: MatDialogRef<AddSourceDialogComponent, AddSourceDialogResult>,
    @Inject(MAT_DIALOG_DATA) public data: AddSourceDialogData,
    private fb: FormBuilder,
    private cdr: ChangeDetectorRef
  ) {
    this.availableLoaders = this.data.availableLoaders;
    this.addSourceForm = this.fb.group<AddSourceFormModel>({
      sourceType: new FormControl<'file' | 'url'>('file', { nonNullable: true, validators: Validators.required }),
      urlInput: new FormControl('', { validators: [Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)] }),
      fileNameInput: new FormControl(''),
      loaderSelect: new FormControl(''),
      rebuildIndex: new FormControl(false, { nonNullable: true }) // Initialize checkbox form control
    });
  }

  ngOnInit(): void {
    this.updateValidatorsBasedOnSourceType();

    const sourceTypeControl = this.addSourceForm.controls.sourceType;

    this.subscriptions.add(
      sourceTypeControl.valueChanges.subscribe(() => {
        this.updateValidatorsBasedOnSourceType();
      })
    );

    this.subscriptions.add(
      merge(
        this.addSourceForm.statusChanges,
        sourceTypeControl.valueChanges
      ).pipe(startWith(null))
        .subscribe(() => {
          this.updateSubmitButtonState();
        })
    );
    this.updateSubmitButtonState();
  }

  private updateValidatorsBasedOnSourceType(): void {
    const sourceType = this.addSourceForm.controls.sourceType.value;
    const urlControl = this.addSourceForm.controls.urlInput;

    if (sourceType !== 'file') {
      this.selectedFile = null;
      this.fileErrorMessage = null;
      const fileInput = document.getElementById('dialogInternalFileInput') as HTMLInputElement;
      if (fileInput) fileInput.value = '';
    }

    if (sourceType === 'file') {
      urlControl.clearValidators();
      urlControl.setValidators([Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)]);
    } else { // 'url'
      urlControl.setValidators([
        Validators.required,
        Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)
      ]);
    }
    urlControl.updateValueAndValidity({ emitEvent: false });
    this.updateSubmitButtonState();
    this.cdr.markForCheck();
  }

  private updateSubmitButtonState(): void {
    const sourceType = this.addSourceForm.controls.sourceType.value;
    let isValid = false;
    if (sourceType === 'file') {
      isValid = !!this.selectedFile;
      if (isValid) {
        this.fileErrorMessage = null;
      }
    } else if (sourceType === 'url') {
      isValid = this.addSourceForm.controls.urlInput.valid;
    }
    this.isSubmitButtonDisabled = !isValid || this.isSubmitting;
    this.cdr.markForCheck();
  }

  onFileSelectedChange(event: Event): void {
    const element = event.target as HTMLInputElement;
    this.fileErrorMessage = null;
    if (element.files && element.files.length > 0) {
      this.selectedFile = element.files!![0];
      if (this.addSourceForm.controls.sourceType.value !== 'file') {
        this.addSourceForm.controls.sourceType.setValue('file');
      } else {
        this.updateSubmitButtonState();
      }
    } else {
      this.selectedFile = null;
      this.updateSubmitButtonState();
    }
  }

  onCancelDialog(): void {
    if (!this.isSubmitting) {
      this.dialogRef.close();
    }
  }

  private checkFormValidityForAction(): boolean {
    const sourceType = this.addSourceForm.controls.sourceType.value;
    if (sourceType === 'file') {
      if (!this.selectedFile) {
        this.fileErrorMessage = "A file must be selected to upload.";
        return false;
      }
      this.fileErrorMessage = null;
      return true;
    } else if (sourceType === 'url') {
      this.addSourceForm.controls.urlInput.markAsTouched();
      return this.addSourceForm.controls.urlInput.valid;
    }
    return false;
  }

  onSubmitDialog(): void {
    Object.values(this.addSourceForm.controls).forEach(control => {
      control.markAsTouched();
    });

    if (this.addSourceForm.controls.sourceType.value === 'file') {
      if (!this.selectedFile) {
        this.fileErrorMessage = "A file must be selected to upload.";
      } else {
        this.fileErrorMessage = null;
      }
    }
    this.updateSubmitButtonState();
    this.cdr.markForCheck();

    if (!this.checkFormValidityForAction()) {
      return;
    }

    this.isSubmitting = true;
    this.updateSubmitButtonState();

    const formValues = this.addSourceForm.getRawValue();
    const result: AddSourceDialogResult = {
      selectedLoader: formValues.loaderSelect || undefined,
      rebuildIndex: formValues.rebuildIndex // Include the checkbox value
    };

    if (formValues.sourceType === 'file' && this.selectedFile) {
      result.file = this.selectedFile;
    } else if (formValues.sourceType === 'url') {
      result.url = formValues.urlInput ?? undefined;
      result.fileName = formValues.fileNameInput || undefined;
    }
    this.dialogRef.close(result);
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }
}
