package com.climind.saas.pricing;

import com.climind.saas.pricing.CalculatorConfig.TaxMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Resultado del cálculo. Es el snapshot que se guarda con cada cotización: con él se reproduce el
 * desglose sin volver a calcular.
 *
 * @param answers   respuestas normalizadas de las preguntas visibles (lo que se guarda)
 * @param answered  respuestas legibles (pregunta y valor) para el resumen, consultas y PDF
 * @param issues    problemas de las respuestas (solo en modo vista previa; en modo estricto se lanzan)
 * @param complete  true si todas las preguntas obligatorias visibles tienen respuesta válida
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PriceBreakdown(
        String currency,
        List<Line> lines,
        BigDecimal subtotal,
        BigDecimal surchargesTotal,
        BigDecimal discountsTotal,
        BigDecimal minimumAdjustment,
        TaxMode taxMode,
        String taxLabel,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        BigDecimal roundingAdjustment,
        BigDecimal total,
        Map<String, Object> answers,
        List<AnsweredQuestion> answered,
        List<String> visibleQuestions,
        List<AnswerIssue> issues,
        boolean complete) {

    public enum LineKind { BASE, SURCHARGE, DISCOUNT, MINIMUM, TAX }

    /**
     * Línea del desglose. {@code quantity}/{@code unit}/{@code unitPrice} solo en precios por unidad;
     * {@code percent} solo en recargos, descuentos e impuestos. Los descuentos tienen importe negativo.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(String ruleId, LineKind kind, String label, BigDecimal quantity, String unit,
                       BigDecimal unitPrice, BigDecimal percent, BigDecimal amount) {
    }

    public record AnsweredQuestion(String key, String label, String value) {
    }

    /** Problema en una respuesta. {@code question} null = problema general del cálculo. */
    public record AnswerIssue(String question, String message) {
    }
}
