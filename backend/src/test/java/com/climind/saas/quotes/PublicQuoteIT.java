package com.climind.saas.quotes;

import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Escenarios 3, 4 y 5 del MVP: el cliente cotiza desde el enlace público (el servidor calcula), deja sus datos,
 * el negocio recibe la consulta, y la cotización conserva su importe aunque cambien los precios.
 */
class PublicQuoteIT extends IntegrationTest {

    private static final String LIMPIEZA_ANSWERS = """
            {"answers":{"tipo":"profunda","m2":60,"ambientes":3,"frecuencia":"unica"}}""";
    private static final String CONTACT = """
            {"name":"Carla Gómez","whatsapp":"11 2345-6789","email":"carla@ejemplo.com",
             "comments":"Es un PH con patio","consent":true}""";

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult quote(Browser visitor, Published calc, String json) throws Exception {
        return visitor.post(calc.api() + "/quotes", json);
    }

    private MvcResult withToken(Browser visitor, String method, String url, String token, String json) throws Exception {
        var builder = "GET".equals(method) ? get(url) : post(url);
        builder.header("X-Quote-Token", token);
        if (json != null) {
            builder.contentType("application/json").content(json);
        }
        return visitor.perform(builder);
    }

    private long simulations() {
        return jdbc.queryForObject("select count(*) from quote_simulations", Long.class);
    }

    @Test
    void elFormularioPublicoNoExponePreciosNiDatosInternos() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");

        MvcResult result = new Browser().get(calc.api());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String json = body(result);
        assertThat((Boolean) JsonPath.read(json, "$.available")).isTrue();
        assertThat((String) JsonPath.read(json, "$.business.name")).isEqualTo("Limpiezas Ana");
        assertThat((String) JsonPath.read(json, "$.business.whatsapp")).isEqualTo("5491155550000");
        assertThat((List<String>) JsonPath.read(json, "$.questions[*].key"))
                .containsExactly("tipo", "m2", "ambientes", "frecuencia", "extras", "detalle");
        assertThat((String) JsonPath.read(json, "$.leadCapture")).isEqualTo("AFTER_ESTIMATE");
        // Ni reglas de precio, ni ids internos, ni datos del dueño.
        assertThat(json).doesNotContain("pricing", "rules", "1500", "tenantId", calc.id(), "ana@ejemplo.com",
                "minimumTotal");
    }

    @Test
    void elServidorCalculaYGuardaLaCotizacion() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Browser visitor = new Browser();

        // El cliente intenta mandar su propio total: se ignora.
        MvcResult result = quote(visitor, calc, """
                {"answers":{"tipo":"profunda","m2":60,"ambientes":3,"frecuencia":"unica"},"total":1,"result":{"total":1}}""");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        String json = body(result);
        assertThat(new BigDecimal(JsonPath.read(json, "$.result.total").toString())).isEqualByComparingTo("99000");
        assertThat((String) JsonPath.read(json, "$.code")).matches("COT-[2-9A-HJ-NP-Z]{6}");
        assertThat((String) JsonPath.read(json, "$.token")).hasSize(43);
        assertThat((String) JsonPath.read(json, "$.validUntil")).isEqualTo("2026-10-22"); // 15 días de vigencia
        assertThat((Boolean) JsonPath.read(json, "$.request.sent")).isFalse();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(visitor.cookie("cotiza_vid")).isPresent();
        assertThat(visitor.cookie("cotiza_vid").get().isHttpOnly()).isTrue();

        // WhatsApp oficial (wa.me) con el mensaje del negocio: servicio, total y código.
        String wa = JsonPath.read(json, "$.whatsappUrl");
        assertThat(wa).startsWith("https://wa.me/5491155550000?text=");
        assertThat(java.net.URLDecoder.decode(wa.substring(wa.indexOf("text=") + 5), StandardCharsets.UTF_8))
                .contains("Limpieza de hogares", "$ 99.000", JsonPath.read(json, "$.code"));

        Map<String, Object> row = jdbc.queryForMap("""
                select s.total, s.price_scale, s.calculator_name, v.version_no, s.answers->>'tipo' as tipo
                from quote_simulations s join calculator_versions v on v.id = s.calculator_version_id""");
        assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("99000");
        assertThat(row.get("version_no")).isEqualTo(1);
        assertThat(row.get("tipo")).isEqualTo("profunda");
    }

    @Test
    void recalcularLoMismoNoDuplicaLaCotizacion() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Browser visitor = new Browser();

        String first = JsonPath.read(body(quote(visitor, calc, LIMPIEZA_ANSWERS)), "$.code");
        String again = JsonPath.read(body(quote(visitor, calc, LIMPIEZA_ANSWERS)), "$.code");
        assertThat(again).isEqualTo(first);
        assertThat(simulations()).isEqualTo(1);

        // Otras respuestas: otra cotización.
        quote(visitor, calc, LIMPIEZA_ANSWERS.replace("\"m2\":60", "\"m2\":80"));
        assertThat(simulations()).isEqualTo(2);

        // Otro visitante con las mismas respuestas: otra cotización (no comparte código ni enlace).
        String other = JsonPath.read(body(quote(new Browser(), calc, LIMPIEZA_ANSWERS)), "$.code");
        assertThat(other).isNotEqualTo(first);
        assertThat(simulations()).isEqualTo(3);

        // Pasada la ventana de 30 minutos, cuenta como una cotización nueva.
        clock.advance(Duration.ofMinutes(31));
        assertThat((String) JsonPath.read(body(quote(visitor, calc, LIMPIEZA_ANSWERS)), "$.code")).isNotEqualTo(first);
        assertThat(simulations()).isEqualTo(4);
    }

    @Test
    void respuestasInvalidasNoSeGuardanNiCuentan() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Browser visitor = new Browser();

        MvcResult missing = quote(visitor, calc, """
                {"answers":{"tipo":"profunda","m2":60}}""");
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(body(missing), "$.code")).isEqualTo("INVALID_ANSWERS");
        assertThat((List<String>) JsonPath.read(body(missing), "$.fieldErrors[*].field")).contains("ambientes", "frecuencia");

        assertThat(quote(visitor, calc, """
                {"answers":{"tipo":"profunda","m2":-5,"ambientes":3,"frecuencia":"unica"}}""").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(quote(visitor, calc, """
                {"answers":{"tipo":"inventada","m2":60,"ambientes":3,"frecuencia":"unica"}}""").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(quote(visitor, calc, """
                {"answers":{"tipo":"profunda","m2":60,"ambientes":3,"frecuencia":"unica","precio":1}}""")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(simulations()).isZero();
    }

    @Test
    void laCotizacionHistoricaConservaSuImporteAunqueCambienLosPrecios() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Browser visitor = new Browser();
        String old = body(quote(visitor, calc, LIMPIEZA_ANSWERS));
        String code = JsonPath.read(old, "$.code");
        String token = JsonPath.read(old, "$.token");

        // El negocio sube el m² profundo de 1500 a 1800 y publica la versión 2.
        String detail = body(ana.get("/api/v1/calculators/" + calc.id()));
        String config = JsonPath.parse((Object) JsonPath.read(detail, "$.config")).jsonString()
                .replace("\"profunda\":1500", "\"profunda\":1800");
        assertThat(ana.put("/api/v1/calculators/" + calc.id(), """
                {"name":"Limpieza de hogares","slug":"%s","config":%s,"lockVersion":%d}"""
                .formatted(calc.slug(), config, (Integer) JsonPath.read(detail, "$.lockVersion")))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(ana.post("/api/v1/calculators/" + calc.id() + "/publish", null).getResponse().getStatus()).isEqualTo(200);

        // La cotización vieja sigue igual.
        String reread = body(withToken(visitor, "GET", "/api/v1/public/quotes/" + code, token, null));
        assertThat(new BigDecimal(JsonPath.read(reread, "$.result.total").toString())).isEqualByComparingTo("99000");
        assertThat(reread).doesNotContain("\"token\"");

        // Una nueva (otro visitante) usa el precio nuevo: 60 m² × 1800 + 3 × 3000 = 117.000.
        String fresh = body(quote(new Browser(), calc, LIMPIEZA_ANSWERS));
        assertThat(new BigDecimal(JsonPath.read(fresh, "$.result.total").toString())).isEqualByComparingTo("117000");
        assertThat(jdbc.queryForList("""
                select v.version_no from quote_simulations s join calculator_versions v on v.id = s.calculator_version_id
                order by s.created_at, v.version_no""", Integer.class)).containsExactly(1, 2);

        // Ni la base permite cambiar el importe de una cotización emitida.
        assertThatThrownBy(() -> jdbc.update("update quote_simulations set total = 1"))
                .hasMessageContaining("inmutable");
    }

    @Test
    void elClienteDejaSusDatosYElNegocioRecibeLaConsulta() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Browser visitor = new Browser();
        String quote = body(quote(visitor, calc, """
                {"answers":{"tipo":"profunda","m2":60,"ambientes":3,"frecuencia":"unica","detalle":"Piso 3"}}"""));
        String code = JsonPath.read(quote, "$.code");
        String token = JsonPath.read(quote, "$.token");
        emails().clear();

        // Sin token, o con otro token, no hay acceso (el código solo viaja por WhatsApp).
        assertThat(visitor.post("/api/v1/public/quotes/" + code + "/request", CONTACT).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(withToken(visitor, "POST", "/api/v1/public/quotes/" + code + "/request",
                "x".repeat(43), CONTACT).getResponse().getStatus()).isEqualTo(404);

        MvcResult sent = withToken(visitor, "POST", "/api/v1/public/quotes/" + code + "/request", token, CONTACT);
        assertThat(sent.getResponse().getStatus()).as(body(sent)).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(body(sent), "$.request.sent")).isTrue();

        Map<String, Object> lead = jdbc.queryForMap("select * from quote_requests");
        assertThat(lead.get("customer_name")).isEqualTo("Carla Gómez");
        assertThat(lead.get("customer_whatsapp")).isEqualTo("5491123456789"); // normalizado para wa.me
        assertThat(lead.get("status")).isEqualTo("NUEVA");
        assertThat(lead.get("code")).isEqualTo(code);
        assertThat((String) lead.get("summary")).contains("tipo de limpieza", "Limpieza profunda", "60 m²", "Piso 3");
        assertThat(jdbc.queryForObject("select kind from quote_status_history", String.class)).isEqualTo("CREATED");

        // Aviso al negocio (al email de contacto, que por defecto es el del registro).
        var mail = emails().lastTo("ana@ejemplo.com").orElseThrow();
        assertThat(mail.html()).contains("Carla Gómez", code, "$ 99.000", "/app/consultas?id=");

        // Una sola consulta por cotización.
        MvcResult twice = withToken(visitor, "POST", "/api/v1/public/quotes/" + code + "/request", token, CONTACT);
        assertThat(twice.getResponse().getStatus()).isEqualTo(409);

        // El negocio la ve en su panel.
        String list = body(ana.get("/api/v1/leads"));
        assertThat((List<String>) JsonPath.read(list, "$.items[*].code")).containsExactly(code);
    }

    @Test
    void datosDeContactoInvalidosNoSeGuardan() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Browser visitor = new Browser();
        String quote = body(quote(visitor, calc, LIMPIEZA_ANSWERS));
        String url = "/api/v1/public/quotes/" + JsonPath.read(quote, "$.code") + "/request";
        String token = JsonPath.read(quote, "$.token");

        MvcResult noConsent = withToken(visitor, "POST", url, token, CONTACT.replace("\"consent\":true", "\"consent\":false"));
        assertThat(noConsent.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(noConsent)).contains("consent");
        MvcResult badPhone = withToken(visitor, "POST", url, token, CONTACT.replace("11 2345-6789", "123"));
        assertThat(badPhone.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(badPhone)).contains("whatsapp");
        assertThat(withToken(visitor, "POST", url, token, CONTACT.replace("\"name\":\"Carla Gómez\"", "\"name\":\"\""))
                .getResponse().getStatus()).isEqualTo(400);
        // Campo trampa completado (bot).
        assertThat(withToken(visitor, "POST", url, token, CONTACT.replace("\"consent\":true", "\"consent\":true,\"website\":\"http://spam\""))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from quote_requests", Long.class)).isZero();
    }

    @Test
    void cotizadorQuePideDatosAntesDelPrecio() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        String detail = body(ana.get("/api/v1/calculators/" + calc.id()));
        String config = JsonPath.parse((Object) JsonPath.read(detail, "$.config")).jsonString()
                .replace("\"leadCapture\":\"AFTER_ESTIMATE\"", "\"leadCapture\":\"BEFORE_ESTIMATE\"");
        assertThat(config).contains("BEFORE_ESTIMATE");
        assertThat(ana.put("/api/v1/calculators/" + calc.id(), """
                {"name":"Limpieza de hogares","slug":"%s","config":%s,"lockVersion":%d}"""
                .formatted(calc.slug(), config, (Integer) JsonPath.read(detail, "$.lockVersion")))
                .getResponse().getStatus()).isEqualTo(200);
        ana.post("/api/v1/calculators/" + calc.id() + "/publish", null);

        Browser visitor = new Browser();
        assertThat((String) JsonPath.read(body(visitor.get(calc.api())), "$.leadCapture")).isEqualTo("BEFORE_ESTIMATE");
        MvcResult withoutContact = quote(visitor, calc, LIMPIEZA_ANSWERS);
        assertThat(withoutContact.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(body(withoutContact), "$.code")).isEqualTo("CONTACT_REQUIRED");
        assertThat(simulations()).isZero();

        MvcResult withContact = quote(visitor, calc, """
                {"answers":{"tipo":"profunda","m2":60,"ambientes":3,"frecuencia":"unica"},"contact":%s}""".formatted(CONTACT));
        assertThat(withContact.getResponse().getStatus()).as(body(withContact)).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(body(withContact), "$.request.sent")).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from quote_requests", Long.class)).isEqualTo(1);
    }

    @Test
    void elAvisoPorEmailRespetaLaPreferenciaDelNegocio() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        assertThat(ana.put("/api/v1/business/settings", """
                {"notifyNewLeadEmail":false,"leadRetentionMonths":12}""").getResponse().getStatus()).isEqualTo(200);
        emails().clear();

        Browser visitor = new Browser();
        String quote = body(quote(visitor, calc, LIMPIEZA_ANSWERS));
        withToken(visitor, "POST", "/api/v1/public/quotes/" + JsonPath.read(quote, "$.code") + "/request",
                JsonPath.read(quote, "$.token"), CONTACT);
        assertThat(jdbc.queryForObject("select count(*) from quote_requests", Long.class)).isEqualTo(1);
        assertThat(emails().lastTo("ana@ejemplo.com")).isEmpty();
    }

    @Test
    void cotizadoresPausadosArchivadosYNegociosSuspendidos() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Browser visitor = new Browser();

        ana.post("/api/v1/calculators/" + calc.id() + "/pause", null);
        String paused = body(visitor.get(calc.api()));
        assertThat((Boolean) JsonPath.read(paused, "$.available")).isFalse();
        assertThat(paused).doesNotContain("questions");
        assertThat((String) JsonPath.read(paused, "$.business.whatsapp")).isEqualTo("5491155550000");
        MvcResult blocked = quote(visitor, calc, LIMPIEZA_ANSWERS);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(body(blocked), "$.code")).isEqualTo("CALCULATOR_UNAVAILABLE");

        ana.post("/api/v1/calculators/" + calc.id() + "/archive", null);
        assertThat(visitor.get(calc.api()).getResponse().getStatus()).isEqualTo(404);

        // Un borrador nunca publicado no existe para el público.
        String draft = JsonPath.read(body(ana.post("/api/v1/calculators", "{\"name\":\"Borrador\"}")), "$.slug");
        assertThat(visitor.get("/api/v1/public/c/" + calc.tenantSlug() + "/" + draft).getResponse().getStatus())
                .isEqualTo(404);

        // Negocio suspendido: ni el cotizador ni sus cotizaciones.
        ana.post("/api/v1/calculators/" + calc.id() + "/restore", null);
        ana.post("/api/v1/calculators/" + calc.id() + "/resume", null);
        String quote = body(quote(visitor, calc, LIMPIEZA_ANSWERS));
        jdbc.update("update tenants set status = 'SUSPENDED'");
        assertThat(visitor.get(calc.api()).getResponse().getStatus()).isEqualTo(404);
        assertThat(withToken(visitor, "GET", "/api/v1/public/quotes/" + JsonPath.read(quote, "$.code"),
                JsonPath.read(quote, "$.token"), null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void pdfDeLaCotizacionYClicEnWhatsapp() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Browser visitor = new Browser();
        String quote = body(quote(visitor, calc, LIMPIEZA_ANSWERS));
        String code = JsonPath.read(quote, "$.code");
        String token = JsonPath.read(quote, "$.token");

        MvcResult pdf = withToken(visitor, "GET", "/api/v1/public/quotes/" + code + "/pdf", token, null);
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(pdf.getResponse().getHeader("Content-Disposition")).contains("cotizacion-" + code + ".pdf");
        try (PDDocument doc = Loader.loadPDF(pdf.getResponse().getContentAsByteArray())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Limpiezas Ana", code, "Limpieza de hogares", "Limpieza profunda", "$ 99.000",
                    "22/10/2026", "No es una factura");
        }
        assertThat(visitor.perform(get("/api/v1/public/quotes/" + code + "/pdf")).getResponse().getStatus()).isEqualTo(404);

        assertThat(withToken(visitor, "POST", "/api/v1/public/quotes/" + code + "/whatsapp", token, null)
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select whatsapp_clicked_at is not null from quote_simulations", Boolean.class))
                .isTrue();
    }

    @Test
    void limiteDeCotizacionesPorIp() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Browser visitor = new Browser();
        int last = 0;
        for (int i = 0; i < 31; i++) {
            last = quote(visitor, calc, LIMPIEZA_ANSWERS).getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
        assertThat(simulations()).isEqualTo(1);
    }

    @Test
    void laPaginaPublicaTieneMetadatosParaCompartir() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas <Ana>", "Limpieza de hogares", "limpieza");

        MvcResult page = new Browser().get(calc.page());
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        String html = body(page);
        assertThat(html).contains("<title>Limpieza de hogares | Limpiezas &lt;Ana&gt;</title>",
                "property=\"og:title\" content=\"Limpieza de hogares | Limpiezas &lt;Ana&gt;\"",
                "http://localhost:8080" + calc.page());
        assertThat(html).doesNotContain("{{", "<Ana>");
        assertThat(new Browser().get("/c/" + calc.tenantSlug() + "/no-existe").getResponse().getStatus()).isEqualTo(404);
    }
}
