import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AnalysisResult } from '../../core/models/analysis.model';

@Component({
  selector: 'app-result-card',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './result-card.component.html',
  styleUrl: './result-card.component.scss'
})
export class ResultCardComponent {
  @Input({ required: true }) result!: AnalysisResult;
}