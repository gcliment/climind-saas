package com.climind.saas.pricing;

import com.climind.saas.pricing.CalculatorConfig.Condition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Evaluación de condiciones simples (AND). Regla explícita: una condición sobre una pregunta sin responder
 * (u oculta) es SIEMPRE falsa, incluso con NOT_EQUALS o IS_FALSE. Así un descuento "si la frecuencia no es
 * única" nunca se aplica cuando el cliente no eligió frecuencia.
 */
final class Conditions {

    private Conditions() {
    }

    static boolean allMatch(List<Condition> conditions, Map<String, Object> answers, CalculatorConfig config) {
        for (Condition c : conditions) {
            if (!matches(c, answers.get(c.question()))) {
                return false;
            }
        }
        return true;
    }

    static boolean matches(Condition c, Object answer) {
        if (answer == null) {
            return false;
        }
        return switch (c.op()) {
            case EQUALS -> equalsValue(answer, c.value());
            case NOT_EQUALS -> !equalsValue(answer, c.value());
            case INCLUDES -> answer instanceof List<?> list && list.contains(c.value());
            case IS_TRUE -> Boolean.TRUE.equals(answer);
            case IS_FALSE -> Boolean.FALSE.equals(answer);
            case GT -> compare(answer, c.value()) > 0;
            case GTE -> compare(answer, c.value()) >= 0;
            case LT -> compare(answer, c.value()) < 0;
            case LTE -> compare(answer, c.value()) <= 0;
        };
    }

    private static boolean equalsValue(Object answer, String value) {
        if (answer instanceof BigDecimal number) {
            BigDecimal other = ConfigValidator.parseNumber(value);
            return other != null && number.compareTo(other) == 0;
        }
        return String.valueOf(answer).equals(value);
    }

    /** Compara numéricamente; si no es comparable, devuelve un valor que hace falsa la condición. */
    private static int compare(Object answer, String value) {
        BigDecimal other = ConfigValidator.parseNumber(value);
        if (!(answer instanceof BigDecimal number) || other == null) {
            throw new IllegalStateException("Condición numérica sobre un valor no numérico");
        }
        return number.compareTo(other);
    }
}
