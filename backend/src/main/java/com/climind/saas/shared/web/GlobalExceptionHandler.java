package com.climind.saas.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Manejo centralizado de errores: respuestas consistentes ({@link ApiError}) y sin filtrar detalles
 * internos (stacktraces, SQL, nombres de clases).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final NotFoundPage notFoundPage;

    public GlobalExceptionHandler(NotFoundPage notFoundPage) {
        this.notFoundPage = notFoundPage;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ApiError(ex.getCode(), ex.getMessage(),
                ex.getFieldErrors(), ex.getIssues(), MDC.get(RequestIdFilter.MDC_KEY)));
    }

    /** Edición concurrente del mismo recurso (bloqueo optimista). */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(Exception ex) {
        return build(HttpStatus.CONFLICT, "STALE_VERSION",
                "Estos datos se modificaron en otra pestaña o dispositivo. Recargá para ver la última versión.",
                List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(),
                        fe.getDefaultMessage() == null ? "Valor inválido." : fe.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Revisá los datos ingresados.", fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Revisá los datos ingresados.", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof PayloadTooLargeException tooLarge) {
                return handleTooLarge(tooLarge);
            }
        }
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "La solicitud no tiene un formato válido.",
                List.of());
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ApiError> handleTooLarge(PayloadTooLargeException ex) {
        return build(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "La solicitud es demasiado grande.",
                List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "El archivo supera el tamaño permitido.",
                List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Método no permitido.", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Formato de contenido no soportado.",
                List.of());
    }

    /** 404: JSON para la API, página HTML para el resto. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                    .body(notFoundPage.html());
        }
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No encontramos lo que buscabas.", List.of());
    }

    /** Carreras de concurrencia contra restricciones únicas (p. ej. dos altas simultáneas). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Violación de integridad: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return build(HttpStatus.CONFLICT, "CONFLICT",
                "No pudimos guardar los cambios porque entran en conflicto con otros datos. Volvé a intentar.",
                List.of());
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiError> handleDenied(Exception ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "No tenés permiso para realizar esta acción.", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Error no controlado [traceId={}]", MDC.get(RequestIdFilter.MDC_KEY), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Ocurrió un error inesperado. Si persiste, contactanos indicando el código de soporte.", List.of());
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                                  List<ApiError.FieldError> fields) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, fields, MDC.get(RequestIdFilter.MDC_KEY)));
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
