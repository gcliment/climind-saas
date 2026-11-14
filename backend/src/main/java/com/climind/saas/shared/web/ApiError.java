package com.climind.saas.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Formato único de error de la API.
 *
 * @param code        código estable para el frontend (p. ej. {@code VALIDATION_ERROR})
 * @param message     mensaje en español, apto para mostrar al usuario
 * @param fieldErrors errores por campo (solo en validaciones)
 * @param issues      problemas estructurados adicionales (p. ej. de la configuración de un cotizador)
 * @param traceId     identificador del request para soporte (también en el header X-Request-Id)
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, List<FieldError> fieldErrors, List<?> issues, String traceId) {

    public ApiError(String code, String message, List<FieldError> fieldErrors, String traceId) {
        this(code, message, fieldErrors, List.of(), traceId);
    }

    public record FieldError(String field, String message) {
    }
}
