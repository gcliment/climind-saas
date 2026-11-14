package com.climind.saas.shared.security;

import com.climind.saas.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Acceso al usuario autenticado del request actual. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<AppPrincipal> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AppPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public static AppPrincipal require() {
        return find().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Tu sesión expiró. Ingresá nuevamente."));
    }
}
