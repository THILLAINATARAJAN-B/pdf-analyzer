export interface AnalyzeRequest {
  pdfUrl: string;
}

export interface AnalysisResult {
  documentType: string;
  title: string;
  authors: string;
  summary: string;
  keyTakeaway: string;
}

export interface ApiResponse<T> {
  success: boolean;
  timestamp: string;
  data?: T;
  message?: string;
  fieldErrors?: Record<string, string>;
}

export interface AnalysisState {
  loading: boolean;
  result: AnalysisResult | null;
  error: string | null;
}