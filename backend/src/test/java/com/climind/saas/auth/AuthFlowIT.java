package com.climind.saas.auth;

import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Escenario 1 del MVP: registro (entra directo) → verificación → ingreso → espacio de trabajo. */
class AuthFlowIT extends IntegrationTest {

    @Test
    void registroVerificacionLoginMeYLogout() throws Exception {
        Browser browser = new Browser();

        MvcResult register = browser.post("/api/v1/auth/register", registerJson("Ana", "Ana@Ejemplo.COM"));
        assertThat(register.getResponse().getStatus()).isEqualTo(201);
        // La cuenta nueva entra directo: sesión iniciada sin esperar la confirmación del email.
        String registerBody = register.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(registerBody, "$.user.email")).isEqualTo("ana@ejemplo.com");
        assertThat((Boolean) JsonPath.read(registerBody, "$.user.emailVerified")).isFalse();
        assertThat((String) JsonPath.read(registerBody, "$.tenant.role")).isEqualTo("OWNER");
        assertThat(registerBody).doesNotContain("password");
        assertThat(browser.get("/api/v1/auth/me").getResponse().getStatus()).isEqualTo(200);
        assertThat(browser.get("/api/v1/business/profile").getResponse().getStatus()).isEqualTo(200);

        // Se creó usuario (email normalizado), negocio, membresía OWNER, perfil y configuración.
        assertThat(jdbc.queryForObject("select email from users", String.class)).isEqualTo("ana@ejemplo.com");
        assertThat(jdbc.queryForObject("select role from tenant_memberships", String.class)).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("select business_name from business_profiles", String.class))
                .isEqualTo("Negocio de Ana");
        assertThat(jdbc.queryForObject("select count(*) from business_settings", Long.class)).isEqualTo(1);
        // La contraseña se guarda con BCrypt y el token solo como hash.
        assertThat(jdbc.queryForObject("select password_hash from users", String.class)).startsWith("$2a$12$");
        String token = tokenFromLastEmail("ana@ejemplo.com");
        assertThat(jdbc.queryForObject("select count(*) from email_verification_tokens where token_hash = ?",
                Long.class, token)).isZero();

        // Sin verificar también puede volver a ingresar (el panel le recuerda confirmar el email).
        Browser otherDevice = new Browser();
        MvcResult early = otherDevice.post("/api/v1/auth/login", loginJson("ana@ejemplo.com", PASSWORD));
        assertThat(early.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(early.getResponse().getContentAsString(), "$.user.emailVerified")).isFalse();

        // Verificación (idempotente si se repite el mismo link).
        assertThat(browser.post("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(browser.post("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(emails().lastTo("ana@ejemplo.com").orElseThrow().template()).isEqualTo("WELCOME");

        // Login: cookie de sesión HttpOnly + datos del negocio.
        MvcResult login = browser.post("/api/v1/auth/login", loginJson("ANA@ejemplo.com", PASSWORD));
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        String loginBody = login.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(loginBody, "$.user.firstName")).isEqualTo("Ana");
        assertThat((String) JsonPath.read(loginBody, "$.tenant.role")).isEqualTo("OWNER");
        assertThat((String) JsonPath.read(loginBody, "$.tenant.name")).isEqualTo("Negocio de Ana");
        assertThat((Boolean) JsonPath.read(loginBody, "$.user.emailVerified")).isTrue();
        assertThat(loginBody).doesNotContain("password");
        var sessionCookie = browser.cookie(SESSION_COOKIE).orElseThrow();
        assertThat(sessionCookie.isHttpOnly()).isTrue();
        assertThat(login.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(h -> assertThat(h).startsWith(SESSION_COOKIE + "=").contains("HttpOnly", "SameSite=Lax"));

        // Las sesiones quedan persistidas en PostgreSQL e indexadas por usuario (la del registro, que se
        // renovó al ingresar, y la del otro dispositivo).
        assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name is not null",
                Long.class)).isEqualTo(2);

        MvcResult me = browser.get("/api/v1/auth/me");
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(me.getResponse().getContentAsString(), "$.user.email"))
                .isEqualTo("ana@ejemplo.com");

        // Logout: la sesión deja de valer.
        assertThat(browser.post("/api/v1/auth/logout", null).getResponse().getStatus()).isEqualTo(204);
        assertThat(browser.get("/api/v1/auth/me").getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from spring_session", Long.class)).isEqualTo(1);

        // Auditoría de los eventos principales.
        assertThat(jdbc.queryForList("select action from audit_logs order by id", String.class))
                .contains("USER_REGISTERED", "EMAIL_VERIFIED", "LOGIN_SUCCEEDED", "LOGOUT");
    }

    @Test
    void registroSoloConNombreEmailYContrasena() throws Exception {
        Browser browser = new Browser();
        MvcResult result = browser.post("/api/v1/auth/register", """
                {"firstName":"Ana","email":"ana@ejemplo.com","password":"Mate-2026","acceptTerms":true}""");

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select last_name from users", String.class)).isEmpty();
        assertThat(emails().lastTo("ana@ejemplo.com").orElseThrow().template()).isEqualTo("VERIFY_EMAIL");
    }

    @Test
    void registroConEmailExistenteNoCreaOtraCuentaNiAbreSesion() throws Exception {
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        emails().clear();

        Browser other = new Browser();
        MvcResult again = other.post("/api/v1/auth/register", registerJson("Otra", "ana@ejemplo.com"));

        assertThat(again.getResponse().getStatus()).isEqualTo(202);
        assertThat(again.getResponse().getContentAsString()).doesNotContain("\"user\"");
        assertThat(other.get("/api/v1/auth/me").getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenants", Long.class)).isEqualTo(1);
        assertThat(emails().lastTo("ana@ejemplo.com").orElseThrow().template()).isEqualTo("ACCOUNT_EXISTS");
    }

    @Test
    void validacionesDeRegistroConMensajesPorCampo() throws Exception {
        Browser browser = new Browser();
        MvcResult result = browser.post("/api/v1/auth/register", """
                {"firstName":"","lastName":"X","email":"no-es-email","password":"corta",
                 "passwordConfirmation":"corta","acceptTerms":false}""");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("VALIDATION_ERROR");
        assertThat(body).contains("firstName", "email", "acceptTerms");

        MvcResult weak = browser.post("/api/v1/auth/register", """
                {"firstName":"Ana","email":"ana@ejemplo.com","password":"12345678","acceptTerms":true}""");
        assertThat(weak.getResponse().getStatus()).isEqualTo(400);
        assertThat(weak.getResponse().getContentAsString()).contains("\"field\":\"password\"");

        MvcResult mismatch = browser.post("/api/v1/auth/register", """
                {"firstName":"Ana","lastName":"X","email":"ana@ejemplo.com","password":"Cotizar-Facil-2026",
                 "passwordConfirmation":"Cotizar-Facil-2027","acceptTerms":true}""");
        assertThat(mismatch.getResponse().getContentAsString()).contains("passwordConfirmation");
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
    }

    @Test
    void reenvioDeVerificacionInvalidaElLinkAnterior() throws Exception {
        Browser browser = new Browser();
        browser.post("/api/v1/auth/register", registerJson("Ana", "ana@ejemplo.com"));
        String first = tokenFromLastEmail("ana@ejemplo.com");

        assertThat(browser.post("/api/v1/auth/resend-verification", "{\"email\":\"ana@ejemplo.com\"}")
                .getResponse().getStatus()).isEqualTo(202);
        String second = tokenFromLastEmail("ana@ejemplo.com");
        assertThat(second).isNotEqualTo(first);

        MvcResult old = browser.post("/api/v1/auth/verify-email", "{\"token\":\"" + first + "\"}");
        assertThat(old.getResponse().getStatus()).isEqualTo(400);
        assertThat(old.getResponse().getContentAsString()).contains("LINK_EXPIRED");
        assertThat(browser.post("/api/v1/auth/verify-email", "{\"token\":\"" + second + "\"}")
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void linkDeVerificacionVencido() throws Exception {
        Browser browser = new Browser();
        browser.post("/api/v1/auth/register", registerJson("Ana", "ana@ejemplo.com"));
        String token = tokenFromLastEmail("ana@ejemplo.com");

        clock.advance(java.time.Duration.ofHours(25));

        MvcResult result = browser.post("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("LINK_EXPIRED");
        assertThat(browser.post("/api/v1/auth/verify-email", "{\"token\":\"basura\"}")
                .getResponse().getContentAsString()).contains("INVALID_LINK");
    }
}
