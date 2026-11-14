package com.climind.saas.tenants;

import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 6 del MVP: un negocio no puede acceder a datos de otro, aunque manipule las solicitudes.
 * (Se amplía en cada fase con los recursos nuevos: cotizadores, consultas, etc.)
 */
class TenantIsolationIT extends IntegrationTest {

    @Test
    void cadaNegocioVeYEditaSoloSuPerfil() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Browser bruno = registerVerifiedAndLogin("Bruno", "bruno@ejemplo.com");
        UUID anaTenant = tenantOf("ana@ejemplo.com");
        UUID brunoTenant = tenantOf("bruno@ejemplo.com");
        assertThat(anaTenant).isNotEqualTo(brunoTenant);

        assertThat(ana.put("/api/v1/business/profile", """
                {"businessName":"Limpiezas Ana","whatsapp":"+54 9 11 2345-6789","industry":"LIMPIEZA"}""")
                .getResponse().getStatus()).isEqualTo(200);

        // Bruno intenta escribir en el negocio de Ana inyectando identificadores en el body.
        MvcResult tampered = bruno.put("/api/v1/business/profile", """
                {"businessName":"Hackeado","tenantId":"%s","tenant_id":"%s","id":"%s"}"""
                .formatted(anaTenant, anaTenant, anaTenant));
        assertThat(tampered.getResponse().getStatus()).isEqualTo(200);

        // Se modificó SOLO el negocio de Bruno.
        assertThat(jdbc.queryForObject("select business_name from business_profiles where tenant_id = ?",
                String.class, anaTenant)).isEqualTo("Limpiezas Ana");
        assertThat(jdbc.queryForObject("select business_name from business_profiles where tenant_id = ?",
                String.class, brunoTenant)).isEqualTo("Hackeado");

        // Cada uno lee su propio perfil (también con parámetros manipulados).
        String anaProfile = ana.get("/api/v1/business/profile?tenantId=" + brunoTenant)
                .getResponse().getContentAsString();
        assertThat((String) JsonPath.read(anaProfile, "$.businessName")).isEqualTo("Limpiezas Ana");
        assertThat((String) JsonPath.read(anaProfile, "$.whatsapp")).isEqualTo("5491123456789");
        String brunoProfile = bruno.get("/api/v1/business/profile").getResponse().getContentAsString();
        assertThat(brunoProfile).doesNotContain("Limpiezas Ana", "5491123456789");
    }

    @Test
    void elTenantDeLaSesionNoSePuedeFalsificarConCookiesNiHeaders() throws Exception {
        Browser bruno = registerVerifiedAndLogin("Bruno", "bruno@ejemplo.com");
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        UUID anaTenant = tenantOf("ana@ejemplo.com");

        MvcResult result = bruno.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/business/profile")
                .header("X-Tenant-Id", anaTenant.toString())
                .cookie(new jakarta.servlet.http.Cookie("tenantId", anaTenant.toString())));

        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.businessName"))
                .isEqualTo("Negocio de Bruno");
    }

    @Test
    void negocioSuspendidoNoPuedeOperar() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        jdbc.update("update tenants set status = 'SUSPENDED' where id = ?", tenantOf("ana@ejemplo.com"));

        MvcResult result = ana.get("/api/v1/business/profile");
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).contains("TENANT_SUSPENDED");
    }

    @Test
    void sinSesionLosEndpointsPrivadosDevuelven401() throws Exception {
        assertThat(mockMvc.perform(anonymousGet("/api/v1/business/profile")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(anonymousGet("/api/v1/auth/me")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(anonymousGet("/api/v1/admin/summary")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void unOwnerNoAccedeAlPanelDeAdministracion() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        assertThat(ana.get("/api/v1/admin/summary").getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void validacionYNormalizacionDelPerfil() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");

        MvcResult invalid = ana.put("/api/v1/business/profile", """
                {"businessName":"A","industry":"INVENTADO","whatsapp":"123"}""");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);

        MvcResult ok = ana.put("/api/v1/business/profile", """
                {"businessName":"  Limpiezas Ana  ","industry":"LIMPIEZA","website":"limpiezasana.com.ar",
                 "contactEmail":"Contacto@LimpiezasAna.com","description":"Hogares y oficinas"}""");
        String body = ok.getResponse().getContentAsString();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body, "$.businessName")).isEqualTo("Limpiezas Ana");
        assertThat((String) JsonPath.read(body, "$.industryLabel")).isEqualTo("Limpieza");
        assertThat((String) JsonPath.read(body, "$.website")).isEqualTo("https://limpiezasana.com.ar");
        assertThat((String) JsonPath.read(body, "$.contactEmail")).isEqualTo("contacto@limpiezasana.com");
        // El nombre del negocio se refleja en /me y, antes de terminar el onboarding, también en el slug.
        assertThat(ana.get("/api/v1/auth/me").getResponse().getContentAsString()).contains("Limpiezas Ana");
        assertThat((String) JsonPath.read(body, "$.publicSlug")).matches("limpiezas-ana-[a-z2-9]{5}");
    }

    @Test
    void terminarElOnboardingFijaLaDireccionYEsIdempotente() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        ana.put("/api/v1/business/profile", "{\"businessName\":\"Limpiezas Ana\"}");

        String first = ana.post("/api/v1/business/onboarding/complete", null).getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(first, "$.onboardingCompleted")).isTrue();
        String slug = JsonPath.read(first, "$.publicSlug");
        assertThat(ana.post("/api/v1/business/onboarding/complete", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ONBOARDING_COMPLETED'",
                Long.class)).isEqualTo(1);

        ana.put("/api/v1/business/profile", "{\"businessName\":\"Otro nombre\"}");
        assertThat(ana.get("/api/v1/auth/me").getResponse().getContentAsString())
                .contains(slug, "\"onboardingCompleted\":true");
    }

    @Test
    void elSlugQuedaFijoDespuesDelOnboarding() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        ana.put("/api/v1/business/profile", "{\"businessName\":\"Limpiezas Ana\"}");
        String slug = jdbc.queryForObject("select slug from tenants", String.class);
        jdbc.update("update tenants set onboarding_completed_at = now()");

        ana.put("/api/v1/business/profile", "{\"businessName\":\"Limpiezas Ana y Asociados\"}");

        assertThat(jdbc.queryForObject("select slug from tenants", String.class)).isEqualTo(slug);
        assertThat(jdbc.queryForObject("select name from tenants", String.class)).isEqualTo("Limpiezas Ana y Asociados");
    }

    @Test
    void lasConsultasYEstadisticasDeUnNegocioNoSonVisiblesParaOtro() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Browser bruno = registerVerifiedAndLogin("Bruno", "bruno@ejemplo.com");
        grantPlan("bruno@ejemplo.com", "PRO"); // para que Bruno pueda exportar
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Browser visitor = new Browser();
        String quote = visitor.post(calc.api() + "/quotes", """
                {"answers":{"tipo":"basica","m2":50,"ambientes":1,"frecuencia":"unica"}}""").getResponse().getContentAsString();
        visitor.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/public/quotes/" + JsonPath.read(quote, "$.code") + "/request")
                .header("X-Quote-Token", (String) JsonPath.read(quote, "$.token"))
                .contentType("application/json")
                .content("{\"name\":\"Carla Gómez\",\"whatsapp\":\"11 2345-6789\",\"consent\":true}"));
        String leadId = jdbc.queryForObject("select id::text from quote_requests", String.class);

        // Bruno no puede leer, modificar, exportar ni descargar la consulta de Ana: 404 (no revela que existe).
        assertThat(bruno.get("/api/v1/leads/" + leadId).getResponse().getStatus()).isEqualTo(404);
        assertThat(bruno.put("/api/v1/leads/" + leadId + "/status", "{\"status\":\"PERDIDA\"}")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(bruno.post("/api/v1/leads/" + leadId + "/notes", "{\"note\":\"x\"}").getResponse().getStatus())
                .isEqualTo(404);
        assertThat(bruno.post("/api/v1/leads/" + leadId + "/anonymize", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(bruno.get("/api/v1/leads/" + leadId + "/pdf").getResponse().getStatus()).isEqualTo(404);
        assertThat(bruno.get("/api/v1/calculators/" + calc.id() + "/qr.svg").getResponse().getStatus()).isEqualTo(404);
        assertThat(bruno.get("/api/v1/leads?q=Carla").getResponse().getContentAsString()).contains("\"totalItems\":0");
        MvcResult csv = bruno.get("/api/v1/leads/export.csv");
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
        assertThat(csv.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("Carla");
        assertThat((Integer) JsonPath.read(bruno.get("/api/v1/stats/summary").getResponse().getContentAsString(),
                "$.totals.simulations")).isZero();
        assertThat(jdbc.queryForObject("select status from quote_requests", String.class)).isEqualTo("NUEVA");

        // Ana sí.
        assertThat(ana.get("/api/v1/leads/" + leadId).getResponse().getStatus()).isEqualTo(200);
        assertThat(ana.get("/api/v1/calculators/" + calc.id() + "/qr.svg").getResponse().getContentAsString())
                .startsWith("<svg");
        // Sin sesión, nada.
        assertThat(new Browser().get("/api/v1/leads").getResponse().getStatus()).isEqualTo(401);
    }

    private UUID tenantOf(String email) {
        return jdbc.queryForObject("""
                select m.tenant_id from tenant_memberships m join users u on u.id = m.user_id where u.email = ?
                """, UUID.class, email);
    }
}
