package com.climind.saas.analytics;

import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Estadísticas: definiciones honestas (simulación ≠ consulta ≠ venta), período y límites. */
class StatsIT extends IntegrationTest {

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Cotiza (y opcionalmente deja datos). Devuelve el código. */
    private String quote(Published calc, int m2, boolean lead) throws Exception {
        Browser visitor = new Browser();
        String quote = body(visitor.post(calc.api() + "/quotes", """
                {"answers":{"tipo":"basica","m2":%d,"ambientes":1,"frecuencia":"unica"}}""".formatted(m2)));
        if (lead) {
            visitor.perform(post("/api/v1/public/quotes/" + JsonPath.read(quote, "$.code") + "/request")
                    .header("X-Quote-Token", (String) JsonPath.read(quote, "$.token"))
                    .contentType("application/json")
                    .content("{\"name\":\"Cliente\",\"whatsapp\":\"11 2345-6789\",\"consent\":true}"));
        }
        return JsonPath.read(quote, "$.code");
    }

    @Test
    void resumenDelPeriodo() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        grantPlan("ana@ejemplo.com", "PRO"); // dos cotizadores publicados
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        Published other = publishTemplate(ana, "Limpiezas Ana", "Oficinas", "limpieza");

        // 5/10: dos cotizaciones (una con consulta). 7/10: tres cotizaciones (una con consulta, luego vendida).
        clock.set(DEFAULT_NOW.minus(Duration.ofDays(2)));
        quote(calc, 40, true);
        quote(calc, 50, false);
        clock.set(DEFAULT_NOW);
        String sold = quote(calc, 100, true);
        quote(other, 300, false);
        quote(other, 40, false);
        String id = jdbc.queryForObject("select id::text from quote_requests where code = ?", String.class, sold);
        ana.put("/api/v1/leads/" + id + "/status", "{\"status\":\"CONVERTIDA\"}");
        // Cotizaciones de otro negocio no cuentan.
        Browser bruno = registerVerifiedAndLogin("Bruno", "bruno@ejemplo.com");
        quote(publishTemplate(bruno, "Bruno", "Catering Bruno", "limpieza"), 70, true);

        String stats = body(ana.get("/api/v1/stats/summary?from=2026-10-01&to=2026-10-07"));
        assertThat((Integer) JsonPath.read(stats, "$.totals.simulations")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(stats, "$.totals.leads")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(stats, "$.totals.simulationsWithLead")).isEqualTo(2);
        assertThat(new BigDecimal(JsonPath.read(stats, "$.totals.conversionRate").toString())).isEqualByComparingTo("40.0");
        assertThat((Integer) JsonPath.read(stats, "$.totals.converted")).isEqualTo(1);
        assertThat((String) JsonPath.read(stats, "$.granularity")).isEqualTo("DAY");

        // Serie diaria completa (días sin actividad en cero).
        assertThat((List<String>) JsonPath.read(stats, "$.series[*].date")).hasSize(7).startsWith("2026-10-01").endsWith("2026-10-07");
        assertThat((List<Integer>) JsonPath.read(stats, "$.series[*].simulations")).containsExactly(0, 0, 0, 0, 2, 0, 3);
        assertThat((List<Integer>) JsonPath.read(stats, "$.series[*].leads")).containsExactly(0, 0, 0, 0, 1, 0, 1);

        // Rendimiento por cotizador.
        assertThat((List<String>) JsonPath.read(stats, "$.calculators[*].name")).containsExactly("Limpieza de hogares", "Oficinas");
        assertThat((List<Integer>) JsonPath.read(stats, "$.calculators[*].simulations")).containsExactly(3, 2);

        // Distribución de importes: todas las cotizaciones caen en algún rango.
        List<Integer> counts = JsonPath.read(stats, "$.distribution[*].count");
        assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(5);
        assertThat(counts.size()).isBetween(1, 8);

        // Solo el día de hoy.
        String today = body(ana.get("/api/v1/stats/summary?from=2026-10-07&to=2026-10-07"));
        assertThat((Integer) JsonPath.read(today, "$.totals.simulations")).isEqualTo(3);
    }

    @Test
    void periodoPorDefectoVacioYLimites() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String empty = body(ana.get("/api/v1/stats/summary"));
        assertThat((String) JsonPath.read(empty, "$.from")).isEqualTo("2026-09-08");
        assertThat((String) JsonPath.read(empty, "$.to")).isEqualTo("2026-10-07");
        assertThat((Integer) JsonPath.read(empty, "$.totals.simulations")).isZero();
        assertThat((Object) JsonPath.read(empty, "$.totals.conversionRate")).isNull(); // sin cotizaciones no hay tasa
        assertThat((List<?>) JsonPath.read(empty, "$.distribution")).isEmpty();

        String yearly = body(ana.get("/api/v1/stats/summary?from=2025-10-08&to=2026-10-07"));
        assertThat((String) JsonPath.read(yearly, "$.granularity")).isEqualTo("MONTH");
        assertThat((List<?>) JsonPath.read(yearly, "$.series")).hasSize(13);

        assertThat(ana.get("/api/v1/stats/summary?from=2025-01-01&to=2026-10-07").getResponse().getStatus()).isEqualTo(400);
        assertThat(ana.get("/api/v1/stats/summary?from=2026-10-07&to=2026-10-01").getResponse().getStatus()).isEqualTo(400);
        assertThat(new Browser().get("/api/v1/stats/summary").getResponse().getStatus()).isEqualTo(401);
    }
}
