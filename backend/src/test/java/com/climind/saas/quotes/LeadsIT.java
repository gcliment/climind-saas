package com.climind.saas.quotes;

import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Gestión de consultas del negocio: listado, filtros, estados, notas, CSV, PDF y protección de datos. */
class LeadsIT extends IntegrationTest {

    @Autowired
    private LeadService leadService;

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Un cliente cotiza y deja sus datos. Devuelve el código. */
    private String lead(Published calc, int m2, String name, String phone, String detail) throws Exception {
        Browser visitor = new Browser();
        String quote = body(visitor.post(calc.api() + "/quotes", """
                {"answers":{"tipo":"basica","m2":%d,"ambientes":1,"frecuencia":"unica","detalle":"%s"}}"""
                .formatted(m2, detail)));
        String code = JsonPath.read(quote, "$.code");
        MvcResult sent = visitor.perform(post("/api/v1/public/quotes/" + code + "/request")
                .header("X-Quote-Token", (String) JsonPath.read(quote, "$.token"))
                .contentType("application/json").content("""
                        {"name":"%s","whatsapp":"%s","email":"cliente@ejemplo.com","comments":"Llamar a la tarde","consent":true}"""
                        .formatted(name, phone)));
        assertThat(sent.getResponse().getStatus()).as(body(sent)).isEqualTo(201);
        return code;
    }

    private String leadId(String code) {
        return jdbc.queryForObject("select id::text from quote_requests where code = ?", String.class, code);
    }

    @Test
    void listarFiltrarBuscarYOrdenar() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        String carla = lead(calc, 100, "Carla Gómez", "11 2345-6789", "");
        clock.advance(Duration.ofHours(1));
        String diego = lead(calc, 40, "Diego Ruiz", "351 15 555-1234", "");
        clock.advance(Duration.ofHours(1));
        String elena = lead(calc, 300, "Elena Paz", "+54 9 261 444-5566", "");

        String all = body(ana.get("/api/v1/leads"));
        assertThat((List<String>) JsonPath.read(all, "$.items[*].code")).containsExactly(elena, diego, carla);
        assertThat((Integer) JsonPath.read(all, "$.totalItems")).isEqualTo(3);
        assertThat((String) JsonPath.read(all, "$.items[0].statusLabel")).isEqualTo("Nueva");

        assertThat((List<String>) JsonPath.read(body(ana.get("/api/v1/leads?sort=mayor_importe")), "$.items[*].code"))
                .containsExactly(elena, carla, diego);
        assertThat((List<String>) JsonPath.read(body(ana.get("/api/v1/leads?sort=antiguas&size=2")), "$.items[*].code"))
                .containsExactly(carla, diego);

        // Búsqueda por nombre (sin distinguir mayúsculas), código y teléfono.
        assertThat((List<String>) JsonPath.read(body(ana.get("/api/v1/leads?q=gómez")), "$.items[*].code"))
                .containsExactly(carla);
        assertThat((List<String>) JsonPath.read(body(ana.get("/api/v1/leads?q=" + diego.toLowerCase())), "$.items[*].code"))
                .containsExactly(diego);
        assertThat((List<String>) JsonPath.read(body(ana.get("/api/v1/leads?q=5551234")), "$.items[*].code"))
                .containsExactly(diego);
        // Los comodines de LIKE se buscan literalmente.
        assertThat((Integer) JsonPath.read(body(ana.get("/api/v1/leads?q=%25")), "$.totalItems")).isZero();

        // Filtro por estado y cantidades por estado.
        ana.put("/api/v1/leads/" + leadId(diego) + "/status", "{\"status\":\"CONTACTADA\"}");
        assertThat((List<String>) JsonPath.read(body(ana.get("/api/v1/leads?status=CONTACTADA")), "$.items[*].code"))
                .containsExactly(diego);
        String counts = body(ana.get("/api/v1/leads/status-counts"));
        assertThat((List<Integer>) JsonPath.read(counts, "$[*].count")).containsExactly(2, 1, 0, 0, 0);
        // Filtro por fecha (zona del negocio).
        assertThat((Integer) JsonPath.read(body(ana.get("/api/v1/leads?from=2026-10-08")), "$.totalItems")).isZero();
        assertThat((Integer) JsonPath.read(body(ana.get("/api/v1/leads?from=2026-10-07&to=2026-10-07")), "$.totalItems"))
                .isEqualTo(3);
    }

    @Test
    void estadosNotasYLineaDeTiempo() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        String id = leadId(lead(calc, 50, "Carla Gómez", "11 2345-6789", ""));

        String detail = body(ana.get("/api/v1/leads/" + id));
        assertThat((String) JsonPath.read(detail, "$.lead.customerWhatsapp")).isEqualTo("5491123456789");
        assertThat((String) JsonPath.read(detail, "$.comments")).isEqualTo("Llamar a la tarde");
        assertThat((List<?>) JsonPath.read(detail, "$.result.lines")).isNotEmpty();
        String wa = JsonPath.read(detail, "$.whatsappUrl");
        assertThat(wa).startsWith("https://wa.me/5491123456789?text=");
        assertThat(java.net.URLDecoder.decode(wa, StandardCharsets.UTF_8)).contains("Hola Carla", "Limpiezas Ana");

        MvcResult changed = ana.put("/api/v1/leads/" + id + "/status",
                "{\"status\":\"EN_NEGOCIACION\",\"note\":\"Pidió precio por 2 limpiezas\"}");
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        ana.post("/api/v1/leads/" + id + "/notes", "{\"note\":\"Confirma el viernes\"}");
        ana.put("/api/v1/leads/" + id + "/status", "{\"status\":\"CONVERTIDA\"}");

        String after = body(ana.get("/api/v1/leads/" + id));
        assertThat((String) JsonPath.read(after, "$.lead.status")).isEqualTo("CONVERTIDA");
        assertThat((List<String>) JsonPath.read(after, "$.history[*].kind"))
                .containsExactly("CREATED", "STATUS_CHANGED", "NOTE", "STATUS_CHANGED");
        assertThat((List<String>) JsonPath.read(after, "$.history[*].note"))
                .containsExactly(null, "Pidió precio por 2 limpiezas", "Confirma el viernes", null);
        assertThat((List<String>) JsonPath.read(after, "$.history[*].toStatus"))
                .containsExactly("Nueva", "En negociación", null, "Convertida");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'LEAD_STATUS_CHANGED'", Long.class))
                .isEqualTo(2);

        MvcResult invalid = ana.put("/api/v1/leads/" + id + "/status", "{\"status\":\"VENDIDA\"}");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(ana.post("/api/v1/leads/" + id + "/notes", "{\"note\":\"  \"}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void exportarCsvSinInyeccionDeFormulas() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        grantPlan("ana@ejemplo.com", "PRO"); // la exportación es de los planes pagos
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        lead(calc, 50, "=HYPERLINK(\\\"http://malo\\\";\\\"x\\\")", "11 2345-6789", "");
        lead(calc, 60, "Diego; Ruiz", "11 2345-0000", "");

        MvcResult csv = ana.get("/api/v1/leads/export.csv");
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getHeader("Content-Disposition")).contains("consultas.csv");
        String text = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Código;Fecha;Estado;Nombre;WhatsApp");
        assertThat(text).contains("\"'=HYPERLINK(\"\"http://malo\"\";\"\"x\"\")\"", "\"Diego; Ruiz\"",
                "wa.me/5491123456789", "Nueva", "Limpieza de hogares");
        assertThat(text.lines()).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'LEADS_EXPORTED'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void anonimizarBorraLosDatosPersonalesPeroNoElImporte() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        String code = lead(calc, 50, "Carla Gómez", "11 2345-6789", "Calle Falsa 123, timbre B");
        String id = leadId(code);
        ana.post("/api/v1/leads/" + id + "/notes", "{\"note\":\"Vive en Calle Falsa 123\"}");
        String totalBefore = jdbc.queryForObject("select total::text from quote_simulations", String.class);

        MvcResult result = ana.post("/api/v1/leads/" + id + "/anonymize", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(body(result), "$.lead.anonymized")).isTrue();

        Map<String, Object> row = jdbc.queryForMap("select * from quote_requests");
        assertThat(row.get("customer_name")).isNull();
        assertThat(row.get("customer_whatsapp")).isNull();
        assertThat(row.get("customer_email")).isNull();
        assertThat(row.get("comments")).isNull();
        assertThat(row.get("code")).isEqualTo(code);
        String everything = jdbc.queryForObject("""
                select coalesce(string_agg(h.note, ' '), '') || (select s.answers::text || s.result::text from quote_simulations s)
                from quote_status_history h""", String.class);
        assertThat(everything).doesNotContain("Calle Falsa", "Carla");
        assertThat(jdbc.queryForObject("select total::text from quote_simulations", String.class)).isEqualTo(totalBefore);

        assertThat(ana.post("/api/v1/leads/" + id + "/notes", "{\"note\":\"otra\"}").getResponse().getStatus())
                .isEqualTo(409);
        // El PDF del negocio sigue disponible, sin el nombre del cliente.
        MvcResult pdf = ana.get("/api/v1/leads/" + id + "/pdf");
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
    }

    @Test
    void laRetencionAnonimizaConsultasViejas() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        ana.put("/api/v1/business/settings", "{\"notifyNewLeadEmail\":true,\"leadRetentionMonths\":6}");
        String old = lead(calc, 50, "Carla Gómez", "11 2345-6789", "");
        String recent = lead(calc, 60, "Diego Ruiz", "11 2345-0000", "");
        jdbc.update("update quote_requests set created_at = created_at - interval '7 months' where code = ?", old);

        assertThat(leadService.applyRetention(clock.instant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select customer_name from quote_requests where code = ?", String.class, old)).isNull();
        assertThat(jdbc.queryForObject("select customer_name from quote_requests where code = ?", String.class, recent))
                .isEqualTo("Diego Ruiz");
        assertThat(leadService.applyRetention(clock.instant())).isZero();
    }

    @Test
    void preferenciasDelNegocio() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String settings = body(ana.get("/api/v1/business/settings"));
        assertThat((Boolean) JsonPath.read(settings, "$.notifyNewLeadEmail")).isTrue();
        assertThat((Integer) JsonPath.read(settings, "$.leadRetentionMonths")).isEqualTo(24);
        assertThat(ana.put("/api/v1/business/settings", "{\"notifyNewLeadEmail\":false,\"leadRetentionMonths\":0}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(ana.put("/api/v1/business/settings", "{\"notifyNewLeadEmail\":false,\"leadRetentionMonths\":12}")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(ana.get("/api/v1/business/settings")), "$.leadRetentionMonths"))
                .isEqualTo(12);
    }
}
