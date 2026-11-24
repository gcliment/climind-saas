package com.climind.saas.shared.security;

import com.climind.saas.shared.config.AppProperties;
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

    /** Dominios que necesita el píxel de Meta (script, y la imagen o pedido con el que envía cada evento). */
    private static final String META_SCRIPT = "https://connect.facebook.net";
    private static final String META_EVENTS = "https://www.facebook.com";

    /**
     * CSP estricta: sin scripts ni estilos inline, todo servido desde el mismo origen. Con el píxel de Meta
     * configurado se agregan solo sus dominios.
     */
    static String contentSecurityPolicy(boolean metaPixel) {
        return String.join("; ",
                "default-src 'self'",
                metaPixel ? "script-src 'self' " + META_SCRIPT : "script-src 'self'",
                "style-src 'self'",
                metaPixel ? "img-src 'self' data: blob: " + META_EVENTS : "img-src 'self' data: blob:",
                "font-src 'self'",
                metaPixel ? "connect-src 'self' " + META_EVENTS + " " + META_SCRIPT : "connect-src 'self'",
                "object-src 'none'",
                "base-uri 'self'",
                "form-action 'self'",
                "frame-ancestors 'none'");
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contextRepository,
                                                   Environment environment, AppProperties properties) throws Exception {
        boolean devEndpoints = environment.acceptsProfiles(Profiles.of("dev"));
        String policy = contentSecurityPolicy(!properties.metaPixelIdOrEmpty().isEmpty());
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
                        .contentSecurityPolicy(csp -> csp.policyDirectives(policy))
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
                            // Exportar todos los datos y dar de baja la cuenta: solo el dueño del negocio.
                            .requestMatchers("/api/v1/account/export", "/api/v1/account/deletion")
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
