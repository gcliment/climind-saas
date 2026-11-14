package com.climind.saas.shared.security;

import com.climind.saas.shared.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

/** Respuestas 401/403 de Spring Security en el mismo formato que {@code ApiError}. */
final class JsonSecurityHandlers {

    private JsonSecurityHandlers() {
    }

    static AuthenticationEntryPoint unauthorized() {
        return (request, response, ex) -> write(response, 401, "UNAUTHORIZED",
                "Tu sesión expiró. Ingresá nuevamente.");
    }

    static AccessDeniedHandler accessDenied() {
        return (request, response, ex) -> {
            if (ex instanceof CsrfException) {
                write(response, 403, "CSRF_INVALID",
                        "La página quedó desactualizada. Recargala y volvé a intentar.");
            } else {
                write(response, 403, "FORBIDDEN", "No tenés permiso para realizar esta acción.");
            }
        };
    }

    private static void write(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String traceId = MDC.get(RequestIdFilter.MDC_KEY);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\""
                + (traceId != null ? ",\"traceId\":\"" + traceId + "\"" : "") + "}");
    }
}
