package com.climind.saas.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Estadísticas del negocio. Definiciones en docs/BUSINESS_RULES.md (sección Estadísticas). */
public final class StatsDtos {

    private StatsDtos() {
    }

    /**
     * @param simulations         cotizaciones calculadas en el período (sin duplicados por recálculo)
     * @param leads               consultas recibidas en el período
     * @param simulationsWithLead cotizaciones del período en las que el cliente dejó sus datos
     * @param conversionRate      simulationsWithLead / simulations, en %; null si no hubo cotizaciones
     * @param converted           consultas del período que el negocio marcó como venta (CONVERTIDA)
     * @param whatsappClicks      cotizaciones del período en las que el cliente abrió WhatsApp
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Totals(long simulations, long leads, long simulationsWithLead, BigDecimal conversionRate,
                         long converted, long whatsappClicks, BigDecimal averageTotal, BigDecimal medianTotal) {
    }

    public record Point(LocalDate date, long simulations, long leads) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CalculatorStats(UUID id, String name, long simulations, long simulationsWithLead,
                                  BigDecimal conversionRate) {
    }

    /** Rango [from, to) de importes estimados y cantidad de cotizaciones en ese rango. */
    public record Bucket(BigDecimal from, BigDecimal to, long count) {
    }

    /**
     * @param granularity DAY (períodos de hasta 92 días) o MONTH
     */
    public record StatsResponse(LocalDate from, LocalDate to, String granularity, String currency, Totals totals,
                                List<Point> series, List<CalculatorStats> calculators, List<Bucket> distribution) {
    }
}
