package com.climind.saas.pricing;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Ayudas para tests del motor: configuraciones escritas en el mismo JSON que se persiste. */
final class PricingTestSupport {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private PricingTestSupport() {
    }

    static CalculatorConfig config(String json) {
        return JSON.readValue(json, CalculatorConfig.class);
    }

    /** Config mínima con las preguntas y reglas dadas (fragmentos JSON). */
    static CalculatorConfig config(String questionsJson, String rulesJson, String extraPricing) {
        return config("""
                {"schemaVersion":1,"currency":"ARS","questions":%s,
                 "pricing":{"rules":%s%s}}""".formatted(questionsJson, rulesJson,
                extraPricing == null || extraPricing.isBlank() ? "" : "," + extraPricing));
    }

    static PriceBreakdown strict(CalculatorConfig config, Map<String, ?> answers) {
        return PricingEngine.calculate(config, answers, PricingEngine.Mode.STRICT, TODAY);
    }

    static PriceBreakdown preview(CalculatorConfig config, Map<String, ?> answers) {
        return PricingEngine.calculate(config, answers, PricingEngine.Mode.PREVIEW, TODAY);
    }

    static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
