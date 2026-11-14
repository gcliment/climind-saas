package com.climind.saas.shared.web;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Error de negocio con código estable y mensaje para el usuario. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiError.FieldError> fieldErrors;
    private final transient List<?> issues;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<ApiError.FieldError> fieldErrors) {
        this(status, code, message, fieldErrors, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<ApiError.FieldError> fieldErrors,
                        List<?> issues) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.issues = List.copyOf(issues);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public List<?> getIssues() {
        return issues;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException field(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Revisá los datos ingresados.",
                List.of(new ApiError.FieldError(field, message)));
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException tooManyRequests() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Hiciste demasiados intentos. Esperá unos minutos y volvé a probar.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ApiError.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
