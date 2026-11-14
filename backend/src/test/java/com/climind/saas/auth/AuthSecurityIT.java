package com.climind.saas.auth;

import com.climind.saas.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Protecciones de autenticación: CSRF, fuerza bruta, enumeración y recuperación de contraseña. */
class AuthSecurityIT extends IntegrationTest {

    @Test
    void operacionesMutantesSinCsrfSonRechazadas() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Ana", "ana@ejemplo.com")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).contains("CSRF_INVALID");

        // Header con un token distinto al de la cookie: también rechazado.
        Browser browser = new Browser();
        browser.csrfToken();
        MvcResult forged = browser.perform(post("/api/v1/auth/register")
                .header("X-XSRF-TOKEN", "token-falso")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson("Ana", "ana@ejemplo.com")));
        assertThat(forged.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
    }

    @Test
    void elLoginRotaElTokenCsrfYCambiaElIdDeSesion() throws Exception {
        Browser browser = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        assertThat(browser.cookie("XSRF-TOKEN")).as("el token previo al login se descarta").isEmpty();
        // Con un token nuevo la sesión sigue operando.
        assertThat(browser.put("/api/v1/business/profile", "{\"businessName\":\"Limpiezas Ana\"}")
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void credencialesInvalidasDevuelvenElMismoMensajeExistaONoElEmail() throws Exception {
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Browser attacker = new Browser();

        MvcResult wrongPassword = attacker.post("/api/v1/auth/login", loginJson("ana@ejemplo.com", "otra-clave-123"));
        MvcResult unknownEmail = attacker.post("/api/v1/auth/login", loginJson("nadie@ejemplo.com", "otra-clave-123"));

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getContentAsString())
                .contains(AuthService.INVALID_CREDENTIALS_MESSAGE);
        assertThat(unknownEmail.getResponse().getContentAsString())
                .contains(AuthService.INVALID_CREDENTIALS_MESSAGE);
    }

    @Test
    void bloqueoProgresivoTrasIntentosFallidos() throws Exception {
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Browser attacker = new Browser();
        for (int i = 0; i < 5; i++) {
            assertThat(attacker.post("/api/v1/auth/login", loginJson("ana@ejemplo.com", "mala-clave-" + i))
                    .getResponse().getStatus()).isEqualTo(401);
        }

        // Bloqueada: ni siquiera la contraseña correcta entra.
        MvcResult locked = attacker.post("/api/v1/auth/login", loginJson("ana@ejemplo.com", PASSWORD));
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getContentAsString()).contains("ACCOUNT_LOCKED");
        assertThat(jdbc.queryForList("select action from audit_logs", String.class)).contains("ACCOUNT_LOCKED");

        // Pasado el bloqueo, la contraseña correcta funciona y reinicia el contador.
        clock.advance(Duration.ofMinutes(16));
        assertThat(attacker.post("/api/v1/auth/login", loginJson("ana@ejemplo.com", PASSWORD))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select failed_login_count from users", Integer.class)).isZero();
    }

    @Test
    void rateLimitPorIpEnEndpointsDeAutenticacion() throws Exception {
        Browser browser = new Browser();
        int lastStatus = 0;
        for (int i = 0; i < 25 && lastStatus != 429; i++) {
            lastStatus = browser.post("/api/v1/auth/forgot-password", "{\"email\":\"x" + i + "@ejemplo.com\"}")
                    .getResponse().getStatus();
        }
        assertThat(lastStatus).isEqualTo(429);
    }

    @Test
    void recuperacionDeContrasenaCierraLasSesionesYElLinkEsDeUnSoloUso() throws Exception {
        Browser session = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Browser anonymous = new Browser();

        // Respuesta idéntica para emails inexistentes.
        assertThat(anonymous.post("/api/v1/auth/forgot-password", "{\"email\":\"nadie@ejemplo.com\"}")
                .getResponse().getStatus()).isEqualTo(202);
        assertThat(emails().lastTo("nadie@ejemplo.com")).isEmpty();

        assertThat(anonymous.post("/api/v1/auth/forgot-password", "{\"email\":\"ana@ejemplo.com\"}")
                .getResponse().getStatus()).isEqualTo(202);
        String token = tokenFromLastEmail("ana@ejemplo.com");

        String newPassword = "Nueva-Clave-Segura-9";
        MvcResult reset = anonymous.post("/api/v1/auth/reset-password", """
                {"token":"%s","password":"%s","passwordConfirmation":"%s"}""".formatted(token, newPassword, newPassword));
        assertThat(reset.getResponse().getStatus()).isEqualTo(200);

        // La sesión abierta antes del cambio quedó invalidada.
        assertThat(session.get("/api/v1/auth/me").getResponse().getStatus()).isEqualTo(401);
        // El link no se puede reutilizar.
        assertThat(anonymous.post("/api/v1/auth/reset-password", """
                {"token":"%s","password":"Otra-Clave-Segura-1","passwordConfirmation":"Otra-Clave-Segura-1"}"""
                .formatted(token)).getResponse().getContentAsString()).contains("LINK_EXPIRED");
        // Vieja contraseña no, nueva sí.
        assertThat(anonymous.post("/api/v1/auth/login", loginJson("ana@ejemplo.com", PASSWORD))
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(anonymous.post("/api/v1/auth/login", loginJson("ana@ejemplo.com", newPassword))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(emails().recent()).anyMatch(m -> m.template().equals("PASSWORD_CHANGED"));
    }

    @Test
    void linkDeRecuperacionVenceEnUnaHora() throws Exception {
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Browser anonymous = new Browser();
        anonymous.post("/api/v1/auth/forgot-password", "{\"email\":\"ana@ejemplo.com\"}");
        String token = tokenFromLastEmail("ana@ejemplo.com");

        clock.advance(Duration.ofMinutes(61));

        MvcResult reset = anonymous.post("/api/v1/auth/reset-password", """
                {"token":"%s","password":"Nueva-Clave-Segura-9","passwordConfirmation":"Nueva-Clave-Segura-9"}"""
                .formatted(token));
        assertThat(reset.getResponse().getStatus()).isEqualTo(400);
        assertThat(reset.getResponse().getContentAsString()).contains("LINK_EXPIRED");
    }

    @Test
    void cuerposDemasiadoGrandesSonRechazados() throws Exception {
        Browser browser = new Browser();
        String huge = "{\"email\":\"" + "a".repeat(70_000) + "@x.com\"}";
        assertThat(browser.post("/api/v1/auth/forgot-password", huge).getResponse().getStatus()).isEqualTo(413);
    }
}
