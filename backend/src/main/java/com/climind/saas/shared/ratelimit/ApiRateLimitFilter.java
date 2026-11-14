package com.climind.saas.shared.ratelimit;

import com.climind.saas.shared.web.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Límite por IP para toda la API y uno más estricto para los endpoints de autenticación. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public ApiRateLimitFilter(RateLimiter rateLimiter, ClientIpResolver clientIpResolver) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIpResolver.resolve(request);
        boolean isAuthMutation = request.getRequestURI().startsWith("/api/v1/auth/")
                && !"GET".equalsIgnoreCase(request.getMethod());
        boolean allowed = rateLimiter.tryConsume(RateLimiter.Policy.API_IP, ip)
                && (!isAuthMutation || rateLimiter.tryConsume(RateLimiter.Policy.AUTH_IP, ip));
        if (!allowed) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":"
                    + "\"Hiciste demasiadas solicitudes. Esperá un minuto y volvé a probar.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
