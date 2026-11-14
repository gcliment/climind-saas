package com.climind.saas.calculators;

import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Escenarios 2 y 5 del MVP: crear desde plantilla, editar precios, publicar y versionado inmutable. */
class CalculatorIT extends IntegrationTest {

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String create(Browser browser, String name, String template) throws Exception {
        MvcResult result = browser.post("/api/v1/calculators", template == null
                ? "{\"name\":\"%s\"}".formatted(name)
                : "{\"name\":\"%s\",\"templateCode\":\"%s\"}".formatted(name, template));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        return JsonPath.read(body(result), "$.id");
    }

    /** Toma el detalle y lo reenvía como PUT con el cambio aplicado sobre el JSON. */
    private MvcResult saveWith(Browser browser, String id, String from, String to) throws Exception {
        String detail = body(browser.get("/api/v1/calculators/" + id));
        Map<String, Object> d = JsonPath.read(detail, "$");
        String config = JsonPath.parse(d.get("config")).jsonString();
        assertThat(config).contains(from);
        String request = """
                {"name":%s,"description":null,"slug":"%s","appearance":{"accentColor":"#0071DB","showLogo":true},
                 "config":%s,"lockVersion":%d}""".formatted("\"" + d.get("name") + "\"", d.get("slug"),
                config.replace(from, to), (Integer) d.get("lockVersion"));
        return browser.put("/api/v1/calculators/" + id, request);
    }

    @Test
    void plantillasDisponibles() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String list = body(ana.get("/api/v1/templates"));
        assertThat((List<String>) JsonPath.read(list, "$[*].code")).containsExactly("limpieza", "catering", "instalaciones");
        assertThat(body(ana.get("/api/v1/templates/catering"))).contains("invitados", "OPTION_PER_UNIT");
        assertThat(ana.get("/api/v1/templates/no-existe").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void crearDesdePlantillaEditarPreciosYPublicarConVersiones() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String id = create(ana, "Limpieza de hogares", "limpieza");

        String detail = body(ana.get("/api/v1/calculators/" + id));
        assertThat((String) JsonPath.read(detail, "$.status")).isEqualTo("BORRADOR");
        assertThat((String) JsonPath.read(detail, "$.slug")).isEqualTo("limpieza-de-hogares");
        assertThat((List<?>) JsonPath.read(detail, "$.publishIssues")).isEmpty();

        // Vista previa con el borrador (no guarda nada).
        String preview = body(ana.post("/api/v1/calculators/" + id + "/preview", """
                {"answers":{"tipo":"profunda","m2":60,"ambientes":3,"frecuencia":"unica"}}"""));
        assertThat(new BigDecimal(JsonPath.read(preview, "$.result.total").toString())).isEqualByComparingTo("99000");

        // Publicar: versión 1.
        MvcResult published = ana.post("/api/v1/calculators/" + id + "/publish", null);
        assertThat(published.getResponse().getStatus()).as(body(published)).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(published), "$.versionNo")).isEqualTo(1);
        assertThat((String) JsonPath.read(body(published), "$.calculator.status")).isEqualTo("PUBLICADO");
        assertThat((String) JsonPath.read(body(published), "$.calculator.publicUrl"))
                .matches("http://localhost:8080/c/negocio-de-ana-[a-z2-9]{5}/limpieza-de-hogares");
        // Publicar fija la dirección del negocio.
        assertThat(jdbc.queryForObject("select onboarding_completed_at is not null from tenants", Boolean.class)).isTrue();

        // Publicar sin cambios no crea otra versión.
        assertThat((Boolean) JsonPath.read(body(ana.post("/api/v1/calculators/" + id + "/publish", null)),
                "$.newVersion")).isFalse();

        // Cambiar el precio del m² profundo: el borrador cambia, lo publicado no.
        MvcResult saved = saveWith(ana, id, "\"profunda\":1500", "\"profunda\":1800");
        assertThat(saved.getResponse().getStatus()).as(body(saved)).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(body(saved), "$.hasUnpublishedChanges")).isTrue();
        assertThat(jdbc.queryForObject("select config->'pricing'->'rules'->0->'prices'->>'profunda' from calculator_versions where version_no = 1",
                String.class)).isEqualTo("1500");

        // Versión 2 con el precio nuevo; la versión 1 queda intacta.
        assertThat((Integer) JsonPath.read(body(ana.post("/api/v1/calculators/" + id + "/publish", null)),
                "$.versionNo")).isEqualTo(2);
        assertThat(jdbc.queryForList("select config->'pricing'->'rules'->0->'prices'->>'profunda' from calculator_versions order by version_no",
                String.class)).containsExactly("1500", "1800");
        String versions = body(ana.get("/api/v1/calculators/" + id + "/versions"));
        assertThat((List<Integer>) JsonPath.read(versions, "$[*].versionNo")).containsExactly(2, 1);
        assertThat((List<Boolean>) JsonPath.read(versions, "$[*].current")).containsExactly(true, false);
    }

    @Test
    void lasVersionesPublicadasSonInmutablesEnLaBase() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String id = create(ana, "Eventos", "catering");
        ana.post("/api/v1/calculators/" + id + "/publish", null);

        assertThatThrownBy(() -> jdbc.update("update calculator_versions set name = 'otro'"))
                .hasMessageContaining("inmutable");
        assertThatThrownBy(() -> jdbc.update("delete from calculator_versions"))
                .hasMessageContaining("inmutable");
    }

    @Test
    void validacionesAlGuardar() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String id = create(ana, "Instalaciones", "instalaciones");
        create(ana, "Otro", null);

        // Configuración inconsistente: precio negativo → 400 con el problema ubicado en la regla.
        MvcResult invalid = saveWith(ana, id, "\"aire_split\":85000", "\"aire_split\":-1");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(body(invalid), "$.code")).isEqualTo("CONFIG_INVALID");
        assertThat((String) JsonPath.read(body(invalid), "$.issues[0].item")).isEqualTo("instalacion");

        // Propiedad desconocida (p. ej. intento de inyectar "script") → formato inválido.
        MvcResult malformed = saveWith(ana, id, "\"schemaVersion\":1", "\"schemaVersion\":1,\"script\":\"alert(1)\"");
        assertThat((String) JsonPath.read(body(malformed), "$.code")).isEqualTo("CONFIG_MALFORMED");

        // Dirección repetida dentro del mismo negocio.
        String detail = body(ana.get("/api/v1/calculators/" + id));
        String config = JsonPath.parse((Object) JsonPath.read(detail, "$.config")).jsonString();
        MvcResult dupSlug = ana.put("/api/v1/calculators/" + id, """
                {"name":"Instalaciones","slug":"otro","config":%s,"lockVersion":%d}"""
                .formatted(config, (Integer) JsonPath.read(detail, "$.lockVersion")));
        assertThat(body(dupSlug)).contains("\"field\":\"slug\"");

        // Color con poco contraste.
        MvcResult light = ana.put("/api/v1/calculators/" + id, """
                {"name":"Instalaciones","slug":"instalaciones","appearance":{"accentColor":"#FFFF00"},"config":%s,"lockVersion":%d}"""
                .formatted(config, (Integer) JsonPath.read(detail, "$.lockVersion")));
        assertThat(body(light)).contains("appearance.accentColor");
    }

    @Test
    void bloqueoOptimistaEntreDosPestanas() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String id = create(ana, "Limpieza", "limpieza");
        String detail = body(ana.get("/api/v1/calculators/" + id));
        String config = JsonPath.parse((Object) JsonPath.read(detail, "$.config")).jsonString();
        String request = """
                {"name":"Limpieza","slug":"limpieza","config":%s,"lockVersion":0}""".formatted(config);

        assertThat(ana.put("/api/v1/calculators/" + id, request).getResponse().getStatus()).isEqualTo(200);
        MvcResult stale = ana.put("/api/v1/calculators/" + id, request);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(stale)).contains("STALE_VERSION");
    }

    @Test
    void cotizadorVacioNoSePuedePublicar() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String id = create(ana, "Desde cero", null);
        MvcResult result = ana.post("/api/v1/calculators/" + id + "/publish", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(result)).contains("NOT_PUBLISHABLE", "Agregá al menos una pregunta.");
        assertThat(jdbc.queryForObject("select count(*) from calculator_versions", Long.class)).isZero();
    }

    @Test
    void estadosPausarReactivarArchivarRestaurarYDuplicar() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String id = create(ana, "Eventos", "catering");
        String base = "/api/v1/calculators/" + id;

        assertThat(ana.post(base + "/pause", null).getResponse().getStatus()).isEqualTo(409); // borrador
        ana.post(base + "/publish", null);
        assertThat((String) JsonPath.read(body(ana.post(base + "/pause", null)), "$.status")).isEqualTo("PAUSADO");
        assertThat((String) JsonPath.read(body(ana.post(base + "/resume", null)), "$.status")).isEqualTo("PUBLICADO");
        assertThat((String) JsonPath.read(body(ana.post(base + "/archive", null)), "$.status")).isEqualTo("ARCHIVADO");

        // Archivado: no se lista por defecto, no se edita, no se publica.
        assertThat(body(ana.get("/api/v1/calculators"))).doesNotContain(id);
        assertThat(body(ana.get("/api/v1/calculators?archived=true"))).contains(id);
        assertThat(ana.post(base + "/publish", null).getResponse().getStatus()).isEqualTo(409);

        // Restaurar: vuelve pausado (tenía versión publicada), sin borrar nada.
        assertThat((String) JsonPath.read(body(ana.post(base + "/restore", null)), "$.status")).isEqualTo("PAUSADO");

        String copy = body(ana.post(base + "/duplicate", null));
        assertThat((String) JsonPath.read(copy, "$.name")).isEqualTo("Copia de Eventos");
        assertThat((String) JsonPath.read(copy, "$.status")).isEqualTo("BORRADOR");
        assertThat((String) JsonPath.read(copy, "$.slug")).isEqualTo("copia-de-eventos");
        assertThat(jdbc.queryForObject("select count(*) from calculators", Long.class)).isEqualTo(2);
    }

    @Test
    void vistaPreviaConConfiguracionSinGuardar() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String id = create(ana, "Simple", null);
        String preview = body(ana.post("/api/v1/calculators/" + id + "/preview", """
                {"answers":{"cantidad":4},
                 "config":{"schemaVersion":1,"currency":"ARS",
                   "questions":[{"key":"cantidad","type":"QUANTITY","label":"Cantidad","required":true,"min":1,"max":10}],
                   "pricing":{"rules":[{"type":"PER_UNIT","id":"u","label":"Unidades","question":"cantidad","unitPrice":2500}]}}}"""));
        assertThat(new BigDecimal(JsonPath.read(preview, "$.result.total").toString())).isEqualByComparingTo("10000");

        // Configuración con problemas: devuelve los problemas, sin resultado.
        String broken = body(ana.post("/api/v1/calculators/" + id + "/preview", """
                {"answers":{},"config":{"schemaVersion":1,"questions":[],
                   "pricing":{"rules":[{"type":"PER_UNIT","id":"u","label":"U","question":"no_existe","unitPrice":1}]}}}"""));
        assertThat((Object) JsonPath.read(broken, "$.result")).isNull();
        assertThat((String) JsonPath.read(broken, "$.configIssues[0].item")).isEqualTo("u");
    }

    @Test
    void unNegocioNoAccedeALosCotizadoresDeOtro() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Browser bruno = registerVerifiedAndLogin("Bruno", "bruno@ejemplo.com");
        String anaCalc = create(ana, "Limpieza de Ana", "limpieza");
        String base = "/api/v1/calculators/" + anaCalc;

        assertThat(bruno.get(base).getResponse().getStatus()).isEqualTo(404);
        assertThat(bruno.get(base + "/versions").getResponse().getStatus()).isEqualTo(404);
        for (String action : List.of("/publish", "/pause", "/resume", "/archive", "/restore", "/duplicate")) {
            assertThat(bruno.post(base + action, null).getResponse().getStatus()).as(action).isEqualTo(404);
        }
        assertThat(bruno.post(base + "/preview", "{\"answers\":{}}").getResponse().getStatus()).isEqualTo(404);
        String anaDetail = body(ana.get(base));
        String config = JsonPath.parse((Object) JsonPath.read(anaDetail, "$.config")).jsonString();
        assertThat(bruno.put(base, """
                {"name":"Hackeado","slug":"hackeado","config":%s,"lockVersion":0}""".formatted(config))
                .getResponse().getStatus()).isEqualTo(404);

        assertThat(body(bruno.get("/api/v1/calculators"))).isEqualTo("[]");
        assertThat(jdbc.queryForObject("select name from calculators", String.class)).isEqualTo("Limpieza de Ana");
        assertThat(jdbc.queryForObject("select status from calculators", String.class)).isEqualTo("BORRADOR");
    }

    @Test
    void elSuperAdminSinNegocioNoUsaEndpointsDeCotizadores() throws Exception {
        assertThat(mockMvc.perform(anonymousGet("/api/v1/calculators")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(anonymousGet("/api/v1/templates")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }
}
