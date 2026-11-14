package com.climind.saas.shared.web;

import com.climind.saas.shared.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * El portal Climind SaaS y la app Climind Cotiza comparten contenedor. Cuando el request llega por el
 * host del portal (p. ej. saas.climind-ia.com), "/" muestra el portal en lugar de la landing de Cotiza.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class PortalHostFilter extends OncePerRequestFilter {

    private final String portalHost;

    public PortalHostFilter(AppProperties properties) {
        String host = properties.getPortalHost();
        this.portalHost = host == null || host.isBlank() ? null : host.trim().toLowerCase();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return portalHost == null || !"/".equals(request.getRequestURI())
                || !portalHost.equalsIgnoreCase(request.getServerName());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.getRequestDispatcher("/saas/index.html").forward(request, response);
    }
}
