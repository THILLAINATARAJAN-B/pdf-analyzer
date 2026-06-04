import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map, timeout } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { AnalyzeRequest, AnalysisResult, ApiResponse } from '../models/analysis.model';

@Injectable({ providedIn: 'root' })
export class PdfAnalyzerService {

  private readonly apiUrl = `${environment.apiBaseUrl}/analyze`;
  private readonly timeoutMs = 120_000;

  constructor(private http: HttpClient) {}

  analyze(pdfUrl: string): Observable<AnalysisResult> {
    const body: AnalyzeRequest = { pdfUrl: pdfUrl.trim() };

    return this.http.post<ApiResponse<AnalysisResult>>(this.apiUrl, body).pipe(
      timeout(this.timeoutMs),
      map(res => {
        if (!res.success || !res.data) {
          throw new Error(res.message || 'Analysis failed. Please try again.');
        }
        return res.data;
      }),
      catchError(this.handleError)
    );
  }

  private handleError(err: HttpErrorResponse | Error): Observable<never> {
    if (err instanceof Error && err.name === 'TimeoutError') {
      return throwError(() => new Error('Request timed out. The PDF may be large or the AI service is slow — please retry.'));
    }
    if (err instanceof HttpErrorResponse) {
      const msg = (err.error as ApiResponse<unknown>)?.message || PdfAnalyzerService.statusMessage(err.status);
      return throwError(() => new Error(msg));
    }
    return throwError(() => err);
  }

  private static statusMessage(status: number): string {
    const map: Record<number, string> = {
      400: 'Invalid request. Please check the URL.',
      422: 'Unable to process the PDF. It may be encrypted, inaccessible, or not a valid PDF.',
      429: 'Too many requests. Please wait a moment and retry.',
      503: 'AI service is temporarily unavailable. Please try again shortly.'
    };
    return map[status] ?? 'An unexpected error occurred. Please try again.';
  }
}