package com.climind.saas.demo;

import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demos públicas: negocio ficticio separado de los clientes reales, sin datos personales, sin cupo que se agote
 * y con estimación en vivo que no guarda nada.
 */
class DemoIT extends IntegrationTest {

    private static final String ANSWERS = """
            {"answers":{"tipo":"profunda","m2":"60","ambientes":"3","frecuencia":"unica","extras":["horno"]}}""";
    private static final String CONTACT = """
            "contact":{"name":"Carla Gómez","whatsapp":"11 2345-6789","consent":true}""";

    @Autowired
    private DemoService demos;

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    @BeforeEach
    void seed() {
        assertThat(demos.seed()).isEqualTo(3);
    }

    @Test
    void lasDemosSeCreanUnaSolaVezConCotizadoresPublicadosYSinDueno() throws Exception {
        assertThat(demos.seed()).as("idempotente").isZero();
        assertThat(jdbc.queryForObject("select count(*) from tenants where demo", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenant_memberships", Long.class))
                .as("nadie puede entrar al panel del negocio de demostración").isZero();
        assertThat(jdbc.queryForObject("select count(*) from calculators where status = 'PUBLICADO'", Long.class))
                .isEqualTo(3);

        MvcResult list = mockMvc.perform(anonymousGet("/api/v1/public/demos")).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        List<String> paths = JsonPath.read(body(list), "$[*].path");
        assertThat(paths).containsExactly("/c/demo/limpieza", "/c/demo/catering", "/c/demo/instalaciones");

        MvcResult page = mockMvc.perform(anonymousGet("/api/v1/public/c/demo/limpieza")).andReturn();
        String json = body(page);
        assertThat((Boolean) JsonPath.read(json, "$.demo")).isTrue();
        assertThat((Boolean) JsonPath.read(json, "$.branding")).as("muestra 'Creado con Climind Cotiza'").isTrue();
        assertThat((Boolean) JsonPath.read(json, "$.available")).isTrue();
        assertThat(json).doesNotContain("unitPrice", "prices");
    }

    @Test
    void laEstimacionEnVivoCalculaEnElServidorSinGuardarNada() throws Exception {
        MvcResult result = mockMvc.perform(anonymousPost("/api/v1/public/demos/limpieza/estimate")
                .contentType("application/json").content(ANSWERS)).andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        // 60 m² × 1.500 + 3 ambientes × 3.000 + horno 9.000 (el mismo ejemplo de la landing).
        assertThat(new BigDecimal(JsonPath.read(body(result), "$.total").toString())).isEqualByComparingTo("108000");
        assertThat(count("quote_simulations")).isZero();

        // Respuestas incompletas: calcula con lo que hay (modo vista previa) y avisa que falta información.
        MvcResult partial = mockMvc.perform(anonymousPost("/api/v1/public/demos/limpieza/estimate")
                .contentType("application/json").content("{\"answers\":{\"tipo\":\"basica\"}}")).andReturn();
        assertThat(partial.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(body(partial), "$.complete")).isFalse();

        assertThat(mockMvc.perform(anonymousPost("/api/v1/public/demos/no-existe/estimate")
                .contentType("application/json").content(ANSWERS)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void laEstimacionSoloFuncionaConElNegocioDeDemostracion() throws Exception {
        // Un negocio real con un cotizador "limpieza" no expone sus precios por el endpoint de demos.
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published real = publishTemplate(ana, "Limpiezas Ana", "Limpieza", "limpieza");
        assertThat(real.slug()).isEqualTo("limpieza");
        jdbc.update("update calculators set status = 'PAUSADO' where tenant_id = (select id from tenants where demo)");
        assertThat(mockMvc.perform(anonymousPost("/api/v1/public/demos/limpieza/estimate")
                .contentType("application/json").content(ANSWERS)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void lasDemosNoRecibenDatosPersonales() throws Exception {
        Browser visitor = new Browser();
        MvcResult withContact = visitor.post("/api/v1/public/c/demo/limpieza/quotes",
                ANSWERS.substring(0, ANSWERS.length() - 1) + "," + CONTACT + "}");
        assertThat(withContact.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(body(withContact), "$.code")).isEqualTo("DEMO_NO_LEADS");

        MvcResult quote = visitor.post("/api/v1/public/c/demo/limpieza/quotes", ANSWERS);
        assertThat(quote.getResponse().getStatus()).as(body(quote)).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(body(quote), "$.demo")).isTrue();
        String code = JsonPath.read(body(quote), "$.code");
        String token = JsonPath.read(body(quote), "$.token");
        MvcResult lead = visitor.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/public/quotes/" + code + "/request").header("X-Quote-Token", token)
                .contentType("application/json").content("{" + CONTACT.substring(CONTACT.indexOf("{") + 1)));
        assertThat(lead.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("quote_requests")).isZero();
    }

    @Test
    void elCupoDeLasDemosNoSeAgotaYSusCotizacionesSeBorranALas48Horas() throws Exception {
        jdbc.update("""
                insert into usage_counters (tenant_id, metric, period_start, count, updated_at)
                select id, 'QUOTES', date '2026-10-01', 100000, now() from tenants where demo""");
        Browser visitor = new Browser();
        assertThat(visitor.post("/api/v1/public/c/demo/catering/quotes", """
                {"answers":{"invitados":"40","menu":"clasico","bebidas":true,"mozos":false,"duracion":"4"}}""")
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(count("quote_simulations")).isEqualTo(1);

        clock.advance(Duration.ofHours(47));
        assertThat(demos.purgeOldQuotes()).isZero();
        clock.advance(Duration.ofHours(2));
        assertThat(demos.purgeOldQuotes()).isEqualTo(1);
        assertThat(count("quote_simulations")).isZero();
    }

    @Test
    void elNegocioDeDemostracionNoApareceEnLaAdministracion() throws Exception {
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        new Browser().post("/api/v1/public/c/demo/limpieza/quotes", ANSWERS);
        Browser admin = loginAsSuperAdmin();

        String tenants = body(admin.get("/api/v1/admin/tenants"));
        assertThat((Integer) JsonPath.read(tenants, "$.totalItems")).isEqualTo(1);
        assertThat(tenants).doesNotContain("\"slug\":\"demo\"");

        String summary = body(admin.get("/api/v1/admin/summary"));
        assertThat((Integer) JsonPath.read(summary, "$.totalTenants")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(summary, "$.quotesThisMonth")).as("las cotizaciones de prueba no cuentan").isZero();
    }
}
