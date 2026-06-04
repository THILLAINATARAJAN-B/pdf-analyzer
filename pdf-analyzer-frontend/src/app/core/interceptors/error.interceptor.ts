import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (!req.url.includes('/health')) {
        console.error(`[HTTP ${error.status}] ${req.url}`);
      }
      return throwError(() => error);
    })
  );
};