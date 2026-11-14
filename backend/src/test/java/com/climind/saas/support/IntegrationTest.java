package com.climind.saas.support;

import com.climind.saas.notifications.EmailMessage;
import com.climind.saas.notifications.EmailSender;
import com.climind.saas.notifications.LoggingEmailSender;
import com.climind.saas.shared.ratelimit.RateLimiter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base de tests de integración: contexto completo de Spring, PostgreSQL real embebido (con Flyway y
 * validación de esquema de Hibernate), MockMvc con la cadena de filtros real (sesión JDBC, CSRF, rate limit)
 * y reloj controlable. Los emails quedan capturados en {@link LoggingEmailSender}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {

    /** Miércoles 7/10/2026 12:00 en Buenos Aires (15:00 UTC). */
    public static final Instant DEFAULT_NOW = Instant.parse("2026-10-07T15:00:00Z");
    public static final String PASSWORD = "Cotizar-Facil-2026";
    public static final String SESSION_COOKIE = "CLIMIND_SESSION";

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]{43})");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @TestConfiguration
    public static class TestBeans {
        @Bean
        @Primary
        public MutableClock testClock() {
            return new MutableClock(DEFAULT_NOW);
        }
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    private EmailSender emailSender;
    @Autowired
    private RateLimiter rateLimiter;
    @Autowired
    protected com.climind.saas.subscriptions.payments.FakePaymentProvider payments;
    @Autowired
    private com.climind.saas.auth.UserRepository users;
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetState() {
        jdbc.execute("""
                TRUNCATE product_email_sends, usage_counters, payment_provider_events, payment_transactions, tenant_subscriptions,
                         quote_status_history, quote_requests, quote_simulations, calculators, calculator_versions,
                         spring_session, spring_session_attributes, audit_logs, email_delivery_logs,
                         email_verification_tokens, password_reset_tokens, business_profiles, business_settings,
                         business_assets, tenant_memberships, tenants, users CASCADE
                """);
        // Los planes vienen de la migración: se restauran los valores iniciales por si un test los editó.
        jdbc.update("DELETE FROM subscription_plans WHERE code NOT IN ('GRATIS', 'PRO', 'NEGOCIO')");
        jdbc.update("""
                UPDATE subscription_plans SET
                  price = CASE code WHEN 'GRATIS' THEN 0 WHEN 'PRO' THEN 9900 ELSE 19900 END,
                  max_published_calculators = CASE code WHEN 'GRATIS' THEN 1 WHEN 'PRO' THEN 3 ELSE 10 END,
                  max_monthly_quotes = CASE code WHEN 'GRATIS' THEN 30 WHEN 'PRO' THEN 300 ELSE 1500 END,
                  csv_export = code <> 'GRATIS', custom_branding = code <> 'GRATIS',
                  lead_email_notifications = true, active_for_new = true
                """);
        clock.set(DEFAULT_NOW);
        emails().clear();
        rateLimiter.reset();
        payments.reset();
    }

    protected LoggingEmailSender emails() {
        return (LoggingEmailSender) emailSender;
    }

    // ------------------------------------------------------------------ CSRF y sesión reales

    /** Cliente HTTP mínimo con "cookie jar": sesión + token CSRF, como un navegador. */
    public final class Browser {
        private final List<Cookie> cookies = new ArrayList<>();

        public Browser() {
            // cookie jar vacío: visitante nuevo
        }

        public MvcResult perform(AbstractMockHttpServletRequestBuilder<?> builder) throws Exception {
            AbstractMockHttpServletRequestBuilder<?> withCookies = cookies.isEmpty() ? builder
                    : builder.cookie(cookies.toArray(Cookie[]::new));
            MvcResult result = mockMvc.perform(withCookies).andReturn();
            for (Cookie c : result.getResponse().getCookies()) {
                cookies.removeIf(existing -> existing.getName().equals(c.getName()));
                if (c.getMaxAge() != 0 && !c.getValue().isEmpty()) {
                    cookies.add(c);
                }
            }
            return result;
        }

        /** GET. */
        public MvcResult get(String url) throws Exception {
            return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url));
        }

        /** Operación mutante con el token CSRF vigente (lo pide si no existe, como hace api.js). */
        public MvcResult send(MockHttpServletRequestBuilder builder, String json) throws Exception {
            String token = csrfToken();
            if (json != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(json);
            }
            return perform(builder.header("X-XSRF-TOKEN", token));
        }

        public MvcResult post(String url, String json) throws Exception {
            return send(MockMvcRequestBuildersHolder.post(url), json);
        }

        public MvcResult put(String url, String json) throws Exception {
            return send(MockMvcRequestBuildersHolder.put(url), json);
        }

        public String csrfToken() throws Exception {
            return cookie("XSRF-TOKEN").map(Cookie::getValue).orElseGet(() -> {
                try {
                    perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/csrf"));
                    return cookie("XSRF-TOKEN").map(Cookie::getValue)
                            .orElseThrow(() -> new AssertionError("No se recibió la cookie XSRF-TOKEN"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        public java.util.Optional<Cookie> cookie(String name) {
            return cookies.stream().filter(c -> c.getName().equals(name)).findFirst();
        }

        public void dropCookie(String name) {
            cookies.removeIf(c -> c.getName().equals(name));
        }
    }

    /** Evita el choque de nombres con los métodos de {@link Browser}. */
    private static final class MockMvcRequestBuildersHolder {
        static MockHttpServletRequestBuilder post(String url) {
            return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
        }

        static MockHttpServletRequestBuilder put(String url) {
            return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url);
        }
    }

    // ------------------------------------------------------------------ flujos de alto nivel

    protected static String registerJson(String firstName, String email) {
        return """
                {"firstName":"%s","lastName":"Prueba","email":"%s","password":"%s",
                 "passwordConfirmation":"%s","acceptTerms":true}
                """.formatted(firstName, email, PASSWORD, PASSWORD);
    }

    protected static String loginJson(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    /** Token del último link recibido por email en esa casilla. */
    protected String tokenFromLastEmail(String email) {
        EmailMessage message = emails().lastTo(email)
                .orElseThrow(() -> new AssertionError("No llegó ningún email a " + email));
        Matcher m = TOKEN_IN_LINK.matcher(message.html());
        assertThat(m.find()).as("el email debe contener un link con token").isTrue();
        return m.group(1);
    }

    /** Registro + verificación + login. Devuelve el navegador con la sesión iniciada. */
    protected Browser registerVerifiedAndLogin(String firstName, String email) throws Exception {
        Browser browser = new Browser();
        assertThat(browser.post("/api/v1/auth/register", registerJson(firstName, email))
                .getResponse().getStatus()).isEqualTo(202);
        String token = tokenFromLastEmail(email);
        assertThat(browser.post("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}")
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult login = browser.post("/api/v1/auth/login", loginJson(email, PASSWORD));
        assertThat(login.getResponse().getStatus()).as(login.getResponse().getContentAsString()).isEqualTo(200);
        return browser;
    }

    /** Cotizador publicado: id privado y dirección pública. */
    public record Published(String id, String tenantSlug, String slug) {
        public String api() {
            return "/api/v1/public/c/" + tenantSlug + "/" + slug;
        }

        public String page() {
            return "/c/" + tenantSlug + "/" + slug;
        }
    }

    /** Completa el perfil (con WhatsApp), crea un cotizador desde una plantilla y lo publica. */
    protected Published publishTemplate(Browser owner, String businessName, String calculatorName, String template)
            throws Exception {
        MvcResult profile = owner.put("/api/v1/business/profile", """
                {"businessName":"%s","whatsapp":"+54 9 11 5555-0000","industry":"LIMPIEZA"}""".formatted(businessName));
        assertThat(profile.getResponse().getStatus()).as(profile.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult created = owner.post("/api/v1/calculators",
                "{\"name\":\"%s\",\"templateCode\":\"%s\"}".formatted(calculatorName, template));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        MvcResult published = owner.post("/api/v1/calculators/" + id + "/publish", null);
        assertThat(published.getResponse().getStatus())
                .as(published.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200);
        String url = com.jayway.jsonpath.JsonPath.read(
                published.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.calculator.publicUrl");
        String[] parts = url.substring(url.indexOf("/c/") + 3).split("/");
        return new Published(id, parts[0], parts[1]);
    }

    /** Da al negocio de ese dueño un plan pago vigente (como si ya se hubiera cobrado el primer mes). */
    protected void grantPlan(String ownerEmail, String planCode) {
        java.sql.Timestamp now = java.sql.Timestamp.from(clock.instant());
        java.sql.Timestamp end = java.sql.Timestamp.from(clock.instant().plus(java.time.Duration.ofDays(30)));
        int rows = jdbc.update("""
                INSERT INTO tenant_subscriptions (id, tenant_id, plan_id, status, provider, external_id, plan_code, plan_name,
                    price, currency, billing_period, max_published_calculators, max_monthly_quotes, csv_export,
                    custom_branding, lead_email_notifications, current_period_start, current_period_end, last_payment_at,
                    created_at, updated_at)
                SELECT gen_random_uuid(), m.tenant_id, p.id, 'ACTIVE', 'FAKE', 'test-' || gen_random_uuid(), p.code, p.name,
                    p.price, p.currency, p.billing_period, p.max_published_calculators, p.max_monthly_quotes, p.csv_export,
                    p.custom_branding, p.lead_email_notifications, ?, ?, ?, ?, ?
                FROM tenant_memberships m JOIN users u ON u.id = m.user_id, subscription_plans p
                WHERE u.email = ? AND p.code = ?
                """, now, end, now, now, now, ownerEmail, planCode);
        assertThat(rows).as("plan otorgado").isEqualTo(1);
    }

    /** Crea un SUPER_ADMIN y devuelve su navegador con la sesión iniciada. */
    protected Browser loginAsSuperAdmin() throws Exception {
        com.climind.saas.auth.User admin = new com.climind.saas.auth.User("admin@climind.test",
                passwordEncoder.encode(PASSWORD), "Climind", "Admin", clock.instant());
        admin.promoteToSuperAdmin();
        admin.markEmailVerified(clock.instant());
        users.save(admin);
        Browser browser = new Browser();
        assertThat(browser.post("/api/v1/auth/login", loginJson("admin@climind.test", PASSWORD))
                .getResponse().getStatus()).isEqualTo(200);
        return browser;
    }

    protected static MockHttpServletRequestBuilder anonymousGet(String url) {
        return get(url);
    }

    protected static MockHttpServletRequestBuilder anonymousPost(String url) {
        return post(url);
    }
}
