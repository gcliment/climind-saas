package com.climind.saas.shared.web;

import com.climind.saas.auth.User;
import com.climind.saas.auth.UserRepository;
import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Frontend estático, cabeceras de seguridad, errores y acceso de administración. */
class WebLayerIT extends IntegrationTest {

    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** GET siguiendo el forward interno (MockMvc no ejecuta forwards; el servidor real sí). */
    private MvcResult page(String path) throws Exception {
        MvcResult result = mockMvc.perform(anonymousGet(path)).andReturn();
        String forward = result.getResponse().getForwardedUrl();
        if (forward == null) {
            return result;
        }
        String absolute = forward.startsWith("/") ? forward : path.substring(0, path.lastIndexOf('/') + 1) + forward;
        return mockMvc.perform(anonymousGet(absolute)).andReturn();
    }

    @Test
    void paginasPublicasSeSirvenConRutasLimpias() throws Exception {
        for (String path : new String[]{"/", "/ingresar", "/registro", "/verificar", "/recuperar", "/restablecer",
                "/app/", "/app/negocio", "/app/admin", "/app/cotizadores", "/app/editor", "/app/onboarding",
                "/app/consultas", "/app/estadisticas", "/app/plan", "/app/plan/simulador", "/app/cuenta", "/cotizacion/COT-ABC234", "/legal/terminos", "/legal/privacidad", "/saas/",
                "/precios", "/ayuda", "/ayuda/primeros-pasos", "/ayuda/crear-cotizador", "/ayuda/precios-y-reglas",
                "/ayuda/publicar-y-compartir", "/ayuda/consultas", "/ayuda/planes-y-pagos", "/ayuda/cuenta-y-datos",
                "/ayuda/preguntas-frecuentes", "/offline.html", "/emails/baja"}) {
            MvcResult result = page(path);
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).as(path).contains("<!doctype html>");
        }
        assertThat(page("/").getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("Dejá de contestar", "cotizador a medida");
        assertThat(page("/saas/").getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("Climind SaaS");
    }

    @Test
    void cabecerasDeSeguridad() throws Exception {
        MvcResult result = mockMvc.perform(anonymousGet("/")).andReturn();
        var response = result.getResponse();
        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'", "script-src 'self'", "frame-ancestors 'none'")
                .doesNotContain("unsafe-inline", "unsafe-eval", "facebook"); // sin APP_META_PIXEL_ID: sin dominios externos
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    }

    @Test
    void ningunaPaginaUsaScriptsOEstilosInline() throws Exception {
        for (String path : new String[]{"/", "/ingresar", "/registro", "/app/", "/app/negocio", "/app/cotizadores",
                "/app/editor", "/app/onboarding", "/app/consultas", "/app/estadisticas", "/app/plan", "/app/plan/simulador",
                "/app/admin", "/app/cuenta", "/cotizacion/COT-ABC234", "/ayuda", "/ayuda/preguntas-frecuentes",
                "/offline.html", "/cotizar.html", "/saas/"}) {
            String html = page(path).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(html).as(path).contains("<!doctype html>");
            assertThat(html).as(path).doesNotContainPattern("<script(?![^>]*\\ssrc=)[^>]*>")
                    .doesNotContain(" style=\"", "onclick=", "<style");
        }
    }

    @Test
    void error404EsHtmlEnPaginasYJsonEnLaApi() throws Exception {
        MvcResult page = mockMvc.perform(anonymousGet("/no-existe")).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(404);
        assertThat(page.getResponse().getContentType()).startsWith("text/html");
        assertThat(page.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("No encontramos esta página");

        Browser browser = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        MvcResult api = browser.get("/api/v1/no-existe");
        assertThat(api.getResponse().getStatus()).isEqualTo(404);
        assertThat((String) JsonPath.read(api.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void erroresNoExponenDetallesInternos() throws Exception {
        Browser browser = new Browser();
        MvcResult malformed = browser.post("/api/v1/auth/login", "{esto no es json");
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        String body = malformed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("MALFORMED_REQUEST").doesNotContain("Exception", "jackson", "at com.");
    }

    @Test
    void configuracionDelSitioYHealthcheck() throws Exception {
        MvcResult config = mockMvc.perform(anonymousGet("/site-config.js")).andReturn();
        assertThat(config.getResponse().getContentType()).startsWith("application/javascript");
        assertThat(config.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("window.CLIMIND_CONFIG", "\"appUrl\"", "\"supportWhatsapp\"", "\"metaPixelId\":\"\"");

        MvcResult health = mockMvc.perform(anonymousGet("/actuator/health")).andReturn();
        assertThat(health.getResponse().getStatus()).isEqualTo(200);
        assertThat(health.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("UP");
        assertThat(mockMvc.perform(anonymousGet("/actuator/env")).andReturn().getResponse().getStatus())
                .isIn(401, 403, 404);
    }

    @Test
    void endpointsDeDesarrolloNoExistenFueraDelPerfilDev() throws Exception {
        assertThat(mockMvc.perform(anonymousGet("/api/dev/emails")).andReturn().getResponse().getStatus())
                .isIn(401, 403, 404);
    }

    @Test
    void elSuperAdminVeElResumenGlobal() throws Exception {
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        User admin = new User("admin@climind.test", passwordEncoder.encode(PASSWORD), "Climind", "Admin", clock.instant());
        admin.promoteToSuperAdmin();
        admin.markEmailVerified(clock.instant());
        users.save(admin);

        Browser browser = new Browser();
        MvcResult login = browser.post("/api/v1/auth/login", loginJson("admin@climind.test", PASSWORD));
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(login.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("\"tenant\":null", "SUPER_ADMIN");

        MvcResult summary = browser.get("/api/v1/admin/summary");
        assertThat(summary.getResponse().getStatus()).isEqualTo(200);
        String body = summary.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat((Integer) JsonPath.read(body, "$.totalTenants")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.verifiedUsers")).isEqualTo(1);

        // Sin negocio propio, los endpoints de negocio no aplican.
        assertThat(browser.get("/api/v1/business/profile").getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void catalogoDeRubrosEsPublico() throws Exception {
        MvcResult result = mockMvc.perform(anonymousGet("/api/v1/catalog/industries")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("LIMPIEZA", "Catering y eventos");
    }

    @Test
    void documentacionOpenApiDisponibleFueraDeProduccion() throws Exception {
        MvcResult result = mockMvc.perform(anonymousGet("/api/docs/openapi.json")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("/api/v1/auth/login", "/api/v1/business/profile");
        // La redirección de /api/docs a la interfaz Swagger no debe quedar bloqueada por la regla /api/**.
        MvcResult ui = mockMvc.perform(anonymousGet("/api/docs")).andReturn();
        assertThat(ui.getResponse().getStatus()).isEqualTo(302);
        String location = ui.getResponse().getHeader("Location");
        assertThat(location).contains("swagger-ui");
        String path = location.replaceFirst("^https?://[^/]+", "");
        assertThat(mockMvc.perform(anonymousGet(path)).andReturn().getResponse().getStatus()).isNotIn(401, 403);
    }
}
