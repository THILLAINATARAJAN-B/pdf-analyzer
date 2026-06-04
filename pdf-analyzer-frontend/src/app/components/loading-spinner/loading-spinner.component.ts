import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-loading-spinner',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './loading-spinner.component.html',
  styleUrl: './loading-spinner.component.scss'
})
export class LoadingSpinnerComponent {
  steps = [
    { label: 'Downloading PDF from URL…' },
    { label: 'Extracting text content…' },
    { label: 'Sending to Gemini AI…' },
    { label: 'Structuring insights…' }
  ];
}