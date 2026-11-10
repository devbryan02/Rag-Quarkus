import { inject } from "@angular/core";
import { HttpInterceptorFn, HttpErrorResponse } from "@angular/common/http";
import { AuthStore, ownApi } from "../auth/auth";
export const apiKeyInterceptor: HttpInterceptorFn = (req, next) => {
  const key = inject(AuthStore).key();
  return next(
    key && ownApi(req.url, location.origin)
      ? req.clone({ setHeaders: { "X-API-Key": key } })
      : req,
  );
};
export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 401 || error.status === 403)
      return "La clave no es válida o no tiene acceso. Revísala en Ajustes.";
    if (error.status === 409)
      return "Hay otra operación en curso o el archivo ya existe. Inténtalo de nuevo.";
    if (error.status === 429)
      return "Demasiadas solicitudes. Espera un momento antes de reintentar.";
    if (error.status === 0)
      return "No se pudo conectar con el servidor. Comprueba la conexión.";
    return typeof error.error?.message === "string"
      ? error.error.message
      : `El servidor devolvió un error (${error.status}).`;
  }
  return error instanceof Error
    ? error.message
    : "Ocurrió un error inesperado.";
}
