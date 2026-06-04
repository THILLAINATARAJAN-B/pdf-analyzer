import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { Subject, takeUntil } from 'rxjs';

import { PdfAnalyzerService } from '../../core/services/pdf-analyzer.service';
import { AnalysisResult, AnalysisState } from '../../core/models/analysis.model';
import { ResultCardComponent } from '../result-card/result-card.component';
import { ErrorMessageComponent } from '../error-message/error-message.component';
import { LoadingSpinnerComponent } from '../loading-spinner/loading-spinner.component';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    ResultCardComponent,
    ErrorMessageComponent,
    LoadingSpinnerComponent
  ],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss'
})
export class HomeComponent implements OnInit, OnDestroy {

  form!: FormGroup;
  state: AnalysisState = { loading: false, result: null, error: null };

  private destroy$ = new Subject<void>();

  constructor(
    private fb: FormBuilder,
    private analyzerSvc: PdfAnalyzerService
  ) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      pdfUrl: ['', [
        Validators.required,
        Validators.maxLength(2048),
        Validators.pattern(/^https?:\/\/.+/)
      ]]
    });
  }

  get urlCtrl() { return this.form.get('pdfUrl')!; }

  get urlError(): string {
    const c = this.urlCtrl;
    if (c.hasError('required')) return 'PDF URL is required.';
    if (c.hasError('maxlength')) return 'URL must not exceed 2048 characters.';
    if (c.hasError('pattern')) return 'Please enter a valid URL starting with https://';
    return '';
  }

  submit(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    this.state = { loading: true, result: null, error: null };

    this.analyzerSvc.analyze(this.urlCtrl.value)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result: AnalysisResult) => {
          this.state = { loading: false, result, error: null };
        },
        error: (err: Error) => {
          this.state = { loading: false, result: null, error: err.message };
        }
      });
  }

  clear(): void {
    this.form.reset();
    this.state = { loading: false, result: null, error: null };
  }

  useExample(): void {
    this.urlCtrl.setValue('https://arxiv.org/pdf/1706.03762');
    this.urlCtrl.markAsTouched();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }
}