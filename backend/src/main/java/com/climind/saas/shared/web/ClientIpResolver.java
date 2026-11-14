package com.climind.saas.shared.web;

import com.climind.saas.shared.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * IP del cliente para rate limiting y auditoría.
 * <p>
 * Por defecto usa {@code request.getRemoteAddr()} (que ya refleja X-Forwarded-For cuando
 * {@code server.forward-headers-strategy=native} y el proxy es de confianza). Si se configura
 * {@code app.security.client-ip-header} (p. ej. {@code CF-Connecting-IP} detrás de Cloudflare), se usa
 * ese header. Solo debe configurarse si TODO el tráfico pasa por ese proxy; de lo contrario sería falsificable.
 */
@Component
public class ClientIpResolver {

    private final String header;

    public ClientIpResolver(AppProperties properties) {
        String configured = properties.getSecurity().getClientIpHeader();
        this.header = configured == null || configured.isBlank() ? null : configured.trim();
    }

    public String resolve(HttpServletRequest request) {
        if (header != null) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                String first = value.split(",")[0].trim();
                if (first.length() <= 45) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
