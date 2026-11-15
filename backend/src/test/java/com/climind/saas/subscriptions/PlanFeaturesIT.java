package com.climind.saas.subscriptions;

import com.climind.saas.notifications.EmailMessage;
import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Matriz de planes: cada plan (Gratis, Pro, Negocio) aplica en el backend exactamente sus límites y funciones
 * (cotizadores publicados, cotizaciones por período, CSV, color propio y marca en la página y en el PDF, aviso
 * por email de cada consulta).
 */
class PlanFeaturesIT extends IntegrationTest {

    private static final String ANSWERS = """
            {"answers":{"tipo":"profunda","m2":60,"ambientes":3,"frecuencia":"unica"}}""";
    private static final String WITH_CONTACT = """
            {"answers":{"tipo":"basica","m2":40,"ambientes":2,"frecuencia":"unica"},
             "contact":{"name":"Carla Gómez","whatsapp":"11 2345-6789","consent":true}}""";

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Negocio con el plan pedido (GRATIS = sin suscripción) y un primer cotizador publicado. */
    private Browser owner(String email, String plan) throws Exception {
        Browser owner = registerVerifiedAndLogin("Ana", email);
        if (!"GRATIS".equals(plan)) {
            grantPlan(email, plan);
        }
        return owner;
    }

    private String createCalculator(Browser owner, String name) throws Exception {
        MvcResult created = owner.post("/api/v1/calculators",
                "{\"name\":\"%s\",\"templateCode\":\"limpieza\"}".formatted(name));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(body(created), "$.id");
    }

    @ParameterizedTest(name = "{0}: hasta {1} cotizadores publicados")
    @CsvSource({"GRATIS, 1", "PRO, 3", "NEGOCIO, 10"})
    void cotizadoresPublicadosSegunElPlan(String plan, int limit) throws Exception {
        Browser ana = owner("ana-" + plan.toLowerCase() + "@ejemplo.com", plan);
        publishTemplate(ana, "Limpiezas Ana", "Cotizador 1", "limpieza");
        for (int i = 2; i <= limit; i++) {
            String id = createCalculator(ana, "Cotizador " + i);
            assertThat(ana.post("/api/v1/calculators/" + id + "/publish", null).getResponse().getStatus())
                    .as("cotizador %d de %d", i, limit).isEqualTo(200);
        }
        String extra = createCalculator(ana, "Uno de más");
        MvcResult blocked = ana.post("/api/v1/calculators/" + extra + "/publish", null);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(body(blocked), "$.code")).isEqualTo("PLAN_LIMIT_CALCULATORS");

        String overview = body(ana.get("/api/v1/billing"));
        assertThat((String) JsonPath.read(overview, "$.current.code")).isEqualTo(plan);
        assertThat((Integer) JsonPath.read(overview, "$.usage.publishedCalculators")).isEqualTo(limit);
        assertThat((Integer) JsonPath.read(overview, "$.usage.publishedLimit")).isEqualTo(limit);
    }

    @ParameterizedTest(name = "{0}: corta en {1} cotizaciones por período")
    @CsvSource({"GRATIS, 30", "PRO, 300", "NEGOCIO, 1500"})
    void cupoDeCotizacionesSegunElPlan(String plan, int limit) throws Exception {
        Browser ana = owner("ana-" + plan.toLowerCase() + "@ejemplo.com", plan);
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Hogares", "limpieza");
        String usage = body(ana.get("/api/v1/billing"));
        assertThat((Integer) JsonPath.read(usage, "$.usage.quotesLimit")).isEqualTo(limit);
        LocalDate periodStart = LocalDate.parse(JsonPath.read(usage, "$.usage.periodStart"));

        // Se lleva el consumo a uno menos que el límite (sin hacer cientos de pedidos).
        jdbc.update("""
                INSERT INTO usage_counters (tenant_id, metric, period_start, count, updated_at)
                SELECT m.tenant_id, 'QUOTES', ?, ?, now() FROM tenant_memberships m JOIN users u ON u.id = m.user_id
                WHERE u.email = ?""", Date.valueOf(periodStart), limit - 1, "ana-" + plan.toLowerCase() + "@ejemplo.com");

        assertThat(new Browser().post(calc.api() + "/quotes", ANSWERS).getResponse().getStatus())
                .as("la cotización número %d todavía entra", limit).isEqualTo(201);
        MvcResult over = new Browser().post(calc.api() + "/quotes", ANSWERS);
        assertThat(over.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(body(over), "$.code")).isEqualTo("QUOTA_EXCEEDED");
        assertThat((Boolean) JsonPath.read(body(new Browser().get(calc.api())), "$.available"))
                .as("con el cupo agotado la página muestra el contacto del negocio").isFalse();
        assertThat((Integer) JsonPath.read(body(ana.get("/api/v1/billing")), "$.usage.quotesUsed")).isEqualTo(limit);
    }

    @ParameterizedTest(name = "{0}: CSV={1}, color propio y sin marca={2}")
    @CsvSource({"GRATIS, false, false", "PRO, true, true", "NEGOCIO, true, true"})
    void funcionesYMarcaEnLaPaginaYEnElPdf(String plan, boolean csv, boolean customBranding) throws Exception {
        Browser ana = owner("ana-" + plan.toLowerCase() + "@ejemplo.com", plan);
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Hogares", "limpieza");
        String detail = body(ana.get("/api/v1/calculators/" + calc.id()));
        String config = JsonPath.parse((Object) JsonPath.read(detail, "$.config")).jsonString();
        ana.put("/api/v1/calculators/" + calc.id(), """
                {"name":"Hogares","slug":"%s","appearance":{"accentColor":"#7A1F5C","showLogo":true},"config":%s,"lockVersion":%d}"""
                .formatted(calc.slug(), config, (Integer) JsonPath.read(detail, "$.lockVersion")));
        assertThat(ana.post("/api/v1/calculators/" + calc.id() + "/publish", null).getResponse().getStatus()).isEqualTo(200);

        // Página pública: marca de Climind y color.
        String page = body(new Browser().get(calc.api()));
        assertThat((Boolean) JsonPath.read(page, "$.branding")).isEqualTo(!customBranding);
        assertThat((String) JsonPath.read(page, "$.accentColor")).isEqualTo(customBranding ? "#7A1F5C" : "#0071DB");

        // PDF de la cotización: la leyenda "Creado con Climind Cotiza" solo en el plan sin personalización.
        Browser visitor = new Browser();
        MvcResult quote = visitor.post(calc.api() + "/quotes", ANSWERS);
        String code = JsonPath.read(body(quote), "$.code");
        String token = JsonPath.read(body(quote), "$.token");
        MvcResult pdf = visitor.perform(get("/api/v1/public/quotes/" + code + "/pdf").header("X-Quote-Token", token));
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        try (PDDocument doc = Loader.loadPDF(pdf.getResponse().getContentAsByteArray())) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            if (customBranding) {
                assertThat(text).doesNotContain("Creado con Climind Cotiza");
            } else {
                assertThat(text).contains("Creado con Climind Cotiza");
            }
        }

        // Exportación de consultas a CSV.
        MvcResult export = ana.get("/api/v1/leads/export.csv");
        assertThat(export.getResponse().getStatus()).isEqualTo(csv ? 200 : 403);
        if (!csv) {
            assertThat((String) JsonPath.read(body(export), "$.code")).isEqualTo("PLAN_FEATURE");
        }
    }

    @Test
    void elAvisoPorEmailDeCadaConsultaRespetaElPlan() throws Exception {
        Browser ana = owner("ana@ejemplo.com", "GRATIS");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Hogares", "limpieza");

        // Hoy todos los planes lo incluyen: llega el aviso.
        emails().clear();
        assertThat(new Browser().post(calc.api() + "/quotes", WITH_CONTACT).getResponse().getStatus()).isEqualTo(201);
        assertThat(emails().recent()).extracting(EmailMessage::template).contains("NEW_LEAD");

        // Si el administrador lo saca del plan Gratis, deja de enviarse (la consulta se guarda igual).
        jdbc.update("UPDATE subscription_plans SET lead_email_notifications = false WHERE code = 'GRATIS'");
        emails().clear();
        assertThat(new Browser().post(calc.api() + "/quotes", """
                {"answers":{"tipo":"profunda","m2":80,"ambientes":4,"frecuencia":"unica"},
                 "contact":{"name":"Beto","whatsapp":"11 3333-4444","consent":true}}""").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(emails().recent()).extracting(EmailMessage::template).doesNotContain("NEW_LEAD");
        assertThat(jdbc.queryForObject("select count(*) from quote_requests", Long.class)).isEqualTo(2);
    }
}
