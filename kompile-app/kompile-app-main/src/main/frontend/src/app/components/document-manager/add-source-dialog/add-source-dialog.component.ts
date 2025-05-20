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
import { Subscription, merge, startWith } from 'rxjs'; // Import merge and startWith
import { map } from 'rxjs/operators'; // Import map

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

interface AddSourceFormModel {
  sourceType: FormControl<'file' | 'url'>;
  urlInput: FormControl<string | null>;
  fileNameInput: FormControl<string | null>;
  loaderSelect: FormControl<string | null>;
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
  isSubmitButtonDisabled: boolean = true; // Property to control button state

  private subscriptions: Subscription = new Subscription(); // Use a single Subscription to manage all

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
      loaderSelect: new FormControl('')
    });
  }

  ngOnInit(): void {
    this.updateValidatorsBasedOnSourceType(); // Initial setup

    const sourceTypeControl = this.addSourceForm.controls.sourceType;
    const urlInputControl = this.addSourceForm.controls.urlInput;

    // Subscribe to sourceType changes to update validators
    this.subscriptions.add(
      sourceTypeControl.valueChanges.subscribe(() => {
        this.updateValidatorsBasedOnSourceType();
        // No need to explicitly call updateSubmitButtonState here if form statusChanges handles it
      })
    );

    // Subscribe to form status changes or relevant control value changes to update button state
    // We also need to consider `this.selectedFile` for the 'file' type.
    // A combined observable might be cleaner or update in relevant methods.
    this.subscriptions.add(
      // Listen to changes that affect validity
      merge(
        this.addSourceForm.statusChanges, // Covers all form validity changes
        sourceTypeControl.valueChanges // React immediately if sourceType changes logic
      ).pipe(startWith(null)) // Emit initially to set the button state
        .subscribe(() => {
          this.updateSubmitButtonState();
        })
    );
    this.updateSubmitButtonState(); // Initial check
  }

  private updateValidatorsBasedOnSourceType(): void {
    const sourceType = this.addSourceForm.controls.sourceType.value;
    const urlControl = this.addSourceForm.controls.urlInput;

    if (sourceType !== 'file') {
      this.selectedFile = null; // Reset file if not 'file' type
      this.fileErrorMessage = null;
      const fileInput = document.getElementById('dialogInternalFileInput') as HTMLInputElement;
      if (fileInput) fileInput.value = '';
    } else {
      // When switching to file, make sure URL is not required.
      // If URL was previously invalid due to being required, this makes it valid again (if pattern matches empty string)
    }


    if (sourceType === 'file') {
      urlControl.clearValidators();
      urlControl.setValidators([Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)]);
      // urlControl.setValue(''); // Optionally reset, or preserve user input
    } else { // 'url'
      urlControl.setValidators([
        Validators.required,
        Validators.pattern(/^(https?|ftp):\/\/[^\s/$.?#].[^\s]*$/i)
      ]);
    }
    urlControl.updateValueAndValidity({ emitEvent: false }); // Avoid re-triggering valueChanges if possible
    // though statusChanges will still fire.
    this.updateSubmitButtonState(); // Update button state after validator changes
    this.cdr.markForCheck(); // Essential for OnPush
  }

  private updateSubmitButtonState(): void {
    const sourceType = this.addSourceForm.controls.sourceType.value;
    let isValid = false;
    if (sourceType === 'file') {
      isValid = !!this.selectedFile; // Valid if a file is selected
      if (isValid) {
        this.fileErrorMessage = null;
      } else {
        // Don't set error message here directly as it's for display on submit attempt or blur
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
        // updateValidators and updateSubmitButtonState will be called via subscription
      } else {
        // If already 'file' type, just update button state
        this.updateSubmitButtonState();
      }
    } else {
      this.selectedFile = null;
      this.updateSubmitButtonState();
    }
    // this.cdr.markForCheck(); // updateSubmitButtonState calls it
  }

  onCancelDialog(): void {
    if (!this.isSubmitting) {
      this.dialogRef.close();
    }
  }

  // This method is no longer directly bound in the template for [disabled]
  // It's used internally by updateSubmitButtonState and by onSubmitDialog
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
      // Ensure all controls are marked as touched for URL validation messages
      this.addSourceForm.controls.urlInput.markAsTouched();
      return this.addSourceForm.controls.urlInput.valid;
    }
    return false;
  }

  onSubmitDialog(): void {
    // Mark all as touched to show any pending validation errors
    Object.values(this.addSourceForm.controls).forEach(control => {
      control.markAsTouched();
    });

    // Explicitly update the error message for file type before checking validity
    if (this.addSourceForm.controls.sourceType.value === 'file') {
      if (!this.selectedFile) {
        this.fileErrorMessage = "A file must be selected to upload.";
      } else {
        this.fileErrorMessage = null;
      }
    }
    this.updateSubmitButtonState(); // Re-evaluate button state and trigger CD
    this.cdr.markForCheck(); // Ensure UI is up-to-date with error messages

    if (!this.checkFormValidityForAction()) {
      return; // Stop if not valid
    }

    this.isSubmitting = true;
    this.updateSubmitButtonState(); // Disable button during submission

    const formValues = this.addSourceForm.getRawValue();
    const result: AddSourceDialogResult = {
      selectedLoader: formValues.loaderSelect || undefined
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
