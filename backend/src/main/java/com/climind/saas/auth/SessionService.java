package com.climind.saas.auth;

import com.climind.saas.shared.security.AppPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Alta, cierre e invalidación de sesiones HTTP (persistidas por Spring Session JDBC). */
@Slf4j
@Service
public class SessionService {

    static final String CSRF_COOKIE = "XSRF-TOKEN";

    private final SecurityContextRepository contextRepository;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionService(SecurityContextRepository contextRepository,
                          FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.contextRepository = contextRepository;
        this.sessions = sessions;
    }

    /** Inicia la sesión autenticada. Cambia el id de sesión (protección contra fijación de sesión). */
    public void establish(AppPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, authorities(principal));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        expireCsrfCookie(request, response);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, auth);
        expireCsrfCookie(request, response);
    }

    /** Cierra todas las sesiones del usuario (cambio de contraseña, suspensión). */
    public int invalidateAll(UUID userId) {
        var found = sessions.findByPrincipalName(userId.toString());
        found.keySet().forEach(sessions::deleteById);
        if (!found.isEmpty()) {
            log.info("Sesiones invalidadas para el usuario {}: {}", userId, found.size());
        }
        return found.size();
    }

    static List<GrantedAuthority> authorities(AppPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (principal.tenantRole() != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + principal.tenantRole()));
        }
        if (principal.isSuperAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + AppPrincipal.ROLE_SUPER_ADMIN));
        }
        return authorities;
    }

    /**
     * Rota el token CSRF al cambiar el estado de autenticación: el frontend pide uno nuevo
     * ({@code GET /api/v1/auth/csrf}) antes de la siguiente operación.
     */
    private static void expireCsrfCookie(HttpServletRequest request, HttpServletResponse response) {
        Cookie cookie = new Cookie(CSRF_COOKIE, "");
        cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
        cookie.setMaxAge(0);
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);
    }
}
