package com.climind.saas.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Seguridad HTTP.
 * <ul>
 *   <li>Sesión de servidor persistida en PostgreSQL (Spring Session JDBC), cookie HttpOnly + SameSite=Lax.</li>
 *   <li>CSRF con token en cookie {@code XSRF-TOKEN} legible por JS y header {@code X-XSRF-TOKEN} (patrón SPA).</li>
 *   <li>Sin CORS: frontend y API comparten origen.</li>
 *   <li>La autorización por recurso y por negocio (tenant) se hace además en los servicios.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    /** CSP estricta: sin scripts ni estilos inline, todo servido desde el mismo origen. */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data: blob:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contextRepository,
                                                   Environment environment) throws Exception {
        boolean devEndpoints = environment.acceptsProfiles(Profiles.of("dev"));
        http
                .securityContext(sc -> sc.securityContextRepository(contextRepository))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf
                        .spa()
                        // Endpoints sin sesión: el cotizador público y los webhooks firmados de proveedores.
                        .ignoringRequestMatchers("/api/v1/public/**", "/api/v1/webhooks/**"))
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(a -> {
                })
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()")))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/v1/auth/me").authenticated()
                            .requestMatchers("/api/v1/auth/**").permitAll()
                            .requestMatchers("/api/v1/public/**", "/api/v1/catalog/**", "/api/v1/webhooks/**")
                            .permitAll()
                            .requestMatchers("/api/v1/admin/**").hasRole(AppPrincipal.ROLE_SUPER_ADMIN)
                            .requestMatchers("/api/v1/business/**", "/api/v1/calculators/**", "/api/v1/templates/**",
                                    "/api/v1/leads/**", "/api/v1/stats/**", "/api/v1/billing/**")
                            .hasRole("OWNER")
                            // Documentación OpenAPI: solo existe si API_DOCS_ENABLED (deshabilitada en prod).
                            .requestMatchers("/api/docs", "/api/docs/**", "/api/swagger-ui/**", "/swagger-ui/**")
                            .permitAll();
                    if (devEndpoints) {
                        auth.requestMatchers("/api/dev/**").permitAll();
                    } else {
                        auth.requestMatchers("/api/dev/**").denyAll();
                    }
                    auth.requestMatchers("/api/**").authenticated()
                            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                            .requestMatchers("/actuator/**").denyAll()
                            // Páginas y recursos estáticos del frontend: públicos (los datos los protege la API).
                            .anyRequest().permitAll();
                })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(JsonSecurityHandlers.unauthorized())
                        .accessDeniedHandler(JsonSecurityHandlers.accessDenied()));
        return http.build();
    }

    /** Evita que Spring Boot cree un usuario en memoria con contraseña generada. */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
