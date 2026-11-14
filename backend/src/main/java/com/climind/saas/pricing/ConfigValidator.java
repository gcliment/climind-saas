package com.climind.saas.pricing;

import com.climind.saas.pricing.CalculatorConfig.Condition;
import com.climind.saas.pricing.CalculatorConfig.DiscountPercentRule;
import com.climind.saas.pricing.CalculatorConfig.FixedRule;
import com.climind.saas.pricing.CalculatorConfig.Operator;
import com.climind.saas.pricing.CalculatorConfig.OptionPerUnitRule;
import com.climind.saas.pricing.CalculatorConfig.OptionPriceRule;
import com.climind.saas.pricing.CalculatorConfig.PerUnitRule;
import com.climind.saas.pricing.CalculatorConfig.PricingRule;
import com.climind.saas.pricing.CalculatorConfig.Question;
import com.climind.saas.pricing.CalculatorConfig.QuestionType;
import com.climind.saas.pricing.CalculatorConfig.SurchargePercentRule;
import com.climind.saas.pricing.CalculatorConfig.TaxMode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validación de configuraciones de cotizadores.
 * <ul>
 *   <li>{@link #validateStructure}: coherencia (tipos, referencias, rangos, límites). Requerida para guardar
 *       un borrador: un borrador puede estar incompleto, pero nunca ser inconsistente.</li>
 *   <li>{@link #validateForPublish}: además, completitud para que un cliente pueda usarlo.</li>
 * </ul>
 */
public final class ConfigValidator {

    public static final int MAX_QUESTIONS = 40;
    public static final int MAX_OPTIONS = 30;
    public static final int MAX_RULES = 60;
    public static final int MAX_TIERS = 10;
    public static final int MAX_CONDITIONS = 5;
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000000");
    public static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000");
    public static final BigDecimal MAX_SURCHARGE_PERCENT = new BigDecimal("500");
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    private ConfigValidator() {
    }

    public static List<ConfigIssue> validateStructure(CalculatorConfig config) {
        List<ConfigIssue> issues = new ArrayList<>();
        if (config.schemaVersion() != CalculatorConfig.CURRENT_SCHEMA) {
            issues.add(ConfigIssue.general("Versión de configuración no soportada."));
            return issues;
        }
        if (!"ARS".equals(config.currency())) {
            issues.add(ConfigIssue.general("Por ahora la única moneda disponible es el peso argentino (ARS)."));
        }
        validateQuestions(config, issues);
        validatePricing(config, issues);
        validateCommercial(config, issues);
        return issues;
    }

    public static List<ConfigIssue> validateForPublish(CalculatorConfig config) {
        List<ConfigIssue> issues = validateStructure(config);
        if (config.questions().isEmpty()) {
            issues.add(ConfigIssue.general("Agregá al menos una pregunta."));
        }
        for (Question q : config.questions()) {
            if (blank(q.label())) {
                issues.add(ConfigIssue.question(q.key(), "Escribí el texto de la pregunta."));
            }
            if (q.type() != null && q.type().isChoice()) {
                if (q.options().size() < 2) {
                    issues.add(ConfigIssue.question(q.key(), "Agregá al menos dos opciones."));
                }
                q.options().stream().filter(o -> blank(o.label())).findFirst().ifPresent(o ->
                        issues.add(ConfigIssue.question(q.key(), "Hay una opción sin texto.")));
            }
            if (q.type() != null && q.type().isNumeric() && q.max() == null) {
                issues.add(ConfigIssue.question(q.key(), "Indicá un valor máximo."));
            }
        }
        if (config.pricing().rules().stream().noneMatch(PricingRule::isBase)) {
            issues.add(ConfigIssue.pricing("Agregá al menos un precio (fijo, por opción o por unidad)."));
        }
        for (PricingRule rule : config.pricing().rules()) {
            if (rule instanceof FixedRule && blank(rule.label())) {
                issues.add(ConfigIssue.rule(rule.id(), "Poné un nombre a este importe: es lo que ve el cliente."));
            }
            if ((rule instanceof SurchargePercentRule || rule instanceof DiscountPercentRule) && blank(rule.label())) {
                issues.add(ConfigIssue.rule(rule.id(), "Poné un nombre a este recargo o descuento."));
            }
        }
        return issues;
    }

    // ------------------------------------------------------------------ preguntas

    private static void validateQuestions(CalculatorConfig config, List<ConfigIssue> issues) {
        List<Question> questions = config.questions();
        if (questions.size() > MAX_QUESTIONS) {
            issues.add(ConfigIssue.general("Un cotizador admite hasta " + MAX_QUESTIONS + " preguntas."));
        }
        Set<String> keys = new HashSet<>();
        Map<String, Question> previous = new HashMap<>();
        for (Question q : questions) {
            String key = q.key();
            if (key == null || !KEY.matcher(key).matches()) {
                issues.add(ConfigIssue.question(key, "Identificador de pregunta inválido."));
                continue;
            }
            if (!keys.add(key)) {
                issues.add(ConfigIssue.question(key, "Hay dos preguntas con el mismo identificador."));
                continue;
            }
            if (q.type() == null) {
                issues.add(ConfigIssue.question(key, "Elegí el tipo de pregunta."));
                continue;
            }
            text(q.label(), 200, ConfigIssue.question(key, "El texto de la pregunta admite hasta 200 caracteres."), issues);
            text(q.help(), 500, ConfigIssue.question(key, "La ayuda admite hasta 500 caracteres."), issues);
            text(q.unit(), 20, ConfigIssue.question(key, "La unidad admite hasta 20 caracteres."), issues);

            if (q.type().isChoice()) {
                validateOptions(q, issues);
            } else if (!q.options().isEmpty()) {
                issues.add(ConfigIssue.question(key, "Este tipo de pregunta no lleva opciones."));
            }
            if (q.type().isNumeric()) {
                validateNumericRange(q, issues);
            }
            if (q.type() == QuestionType.TEXT && q.maxLength() != null
                    && (q.maxLength() < 1 || q.maxLength() > 2000)) {
                issues.add(ConfigIssue.question(key, "El largo máximo debe estar entre 1 y 2000 caracteres."));
            }
            if (q.visibleIf().size() > MAX_CONDITIONS) {
                issues.add(ConfigIssue.question(key, "Demasiadas condiciones de visibilidad."));
            }
            for (Condition c : q.visibleIf()) {
                // Solo puede depender de preguntas ANTERIORES: evita ciclos y dependencias confusas.
                String problem = conditionProblem(c, previous);
                if (problem != null) {
                    issues.add(ConfigIssue.question(key, "Condición para mostrar la pregunta: " + problem));
                }
            }
            previous.put(key, q);
        }
    }

    private static void validateOptions(Question q, List<ConfigIssue> issues) {
        if (q.options().size() > MAX_OPTIONS) {
            issues.add(ConfigIssue.question(q.key(), "Una pregunta admite hasta " + MAX_OPTIONS + " opciones."));
        }
        Set<String> optionKeys = new HashSet<>();
        q.options().forEach(o -> {
            if (o.key() == null || !KEY.matcher(o.key()).matches()) {
                issues.add(ConfigIssue.question(q.key(), "Identificador de opción inválido."));
            } else if (!optionKeys.add(o.key())) {
                issues.add(ConfigIssue.question(q.key(), "Hay dos opciones con el mismo identificador."));
            }
            text(o.label(), 200, ConfigIssue.question(q.key(), "El texto de una opción admite hasta 200 caracteres."),
                    issues);
        });
    }

    private static void validateNumericRange(Question q, List<ConfigIssue> issues) {
        BigDecimal min = q.min() == null ? BigDecimal.ZERO : q.min();
        BigDecimal max = q.max();
        if (min.signum() < 0) {
            issues.add(ConfigIssue.question(q.key(), "El mínimo no puede ser negativo."));
        }
        if (max != null && max.compareTo(MAX_QUANTITY) > 0) {
            issues.add(ConfigIssue.question(q.key(), "El máximo no puede superar 1.000.000."));
        }
        if (max != null && min.compareTo(max) > 0) {
            issues.add(ConfigIssue.question(q.key(), "El mínimo no puede ser mayor que el máximo."));
        }
        if (q.integerOnly() && (!isInteger(min) || (max != null && !isInteger(max)))) {
            issues.add(ConfigIssue.question(q.key(), "Para cantidades, el mínimo y el máximo deben ser números enteros."));
        }
    }

    /** Devuelve el problema de una condición o null si es válida. */
    private static String conditionProblem(Condition c, Map<String, Question> available) {
        if (c == null || c.question() == null || c.op() == null) {
            return "está incompleta.";
        }
        Question target = available.get(c.question());
        if (target == null || target.type() == null) {
            return "hace referencia a una pregunta que no existe o que está después.";
        }
        QuestionType type = target.type();
        if (!type.affectsPrice()) {
            return "no se puede usar una pregunta de texto o fecha.";
        }
        Operator op = c.op();
        switch (type) {
            case SINGLE_CHOICE -> {
                if (op != Operator.EQUALS && op != Operator.NOT_EQUALS) {
                    return "para una selección única usá \"es\" o \"no es\".";
                }
                if (target.option(c.value()) == null) {
                    return "la opción elegida no existe.";
                }
            }
            case MULTI_CHOICE -> {
                if (op != Operator.INCLUDES) {
                    return "para una selección múltiple usá \"incluye\".";
                }
                if (target.option(c.value()) == null) {
                    return "la opción elegida no existe.";
                }
            }
            case YES_NO -> {
                if (op != Operator.IS_TRUE && op != Operator.IS_FALSE) {
                    return "para una pregunta Sí/No usá \"es sí\" o \"es no\".";
                }
            }
            case QUANTITY, NUMBER -> {
                if (op == Operator.INCLUDES || op == Operator.IS_TRUE || op == Operator.IS_FALSE) {
                    return "para un número usá comparaciones (igual, mayor, menor).";
                }
                if (parseNumber(c.value()) == null) {
                    return "el valor de comparación debe ser un número.";
                }
            }
            default -> {
                return "tipo de pregunta no soportado.";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ precios

    private static void validatePricing(CalculatorConfig config, List<ConfigIssue> issues) {
        CalculatorConfig.Pricing pricing = config.pricing();
        if (pricing.rules().size() > MAX_RULES) {
            issues.add(ConfigIssue.pricing("Un cotizador admite hasta " + MAX_RULES + " reglas de precio."));
        }
        Map<String, Question> all = new HashMap<>();
        config.questions().stream().filter(q -> q.key() != null).forEach(q -> all.putIfAbsent(q.key(), q));
        Set<String> ids = new HashSet<>();
        for (PricingRule rule : pricing.rules()) {
            String id = rule.id();
            if (id == null || !KEY.matcher(id).matches()) {
                issues.add(ConfigIssue.rule(id, "Identificador de regla inválido."));
                continue;
            }
            if (!ids.add(id)) {
                issues.add(ConfigIssue.rule(id, "Hay dos reglas con el mismo identificador."));
                continue;
            }
            text(rule.label(), 200, ConfigIssue.rule(id, "El nombre admite hasta 200 caracteres."), issues);
            switch (rule) {
                case FixedRule r -> amount(r.amount(), id, "el importe", issues);
                case OptionPriceRule r -> {
                    Question q = all.get(r.question());
                    if (q == null || q.type() == null || !q.type().isChoice()) {
                        issues.add(ConfigIssue.rule(id, "Elegí una pregunta de opciones."));
                    } else {
                        optionPrices(r.prices(), q, id, issues);
                    }
                }
                case PerUnitRule r -> {
                    Question q = all.get(r.question());
                    if (q == null || q.type() == null || !q.type().isNumeric()) {
                        issues.add(ConfigIssue.rule(id, "Elegí una pregunta de cantidad o medida."));
                    }
                    amount(r.unitPrice(), id, "el precio por unidad", issues);
                    tiers(r, issues);
                }
                case OptionPerUnitRule r -> {
                    Question q = all.get(r.question());
                    Question qty = all.get(r.quantityQuestion());
                    if (q == null || q.type() != QuestionType.SINGLE_CHOICE) {
                        issues.add(ConfigIssue.rule(id, "Elegí una pregunta de selección única."));
                    } else {
                        optionPrices(r.prices(), q, id, issues);
                    }
                    if (qty == null || qty.type() == null || !qty.type().isNumeric()) {
                        issues.add(ConfigIssue.rule(id, "Elegí la pregunta de cantidad que multiplica el precio."));
                    }
                }
                case SurchargePercentRule r -> percent(r.percent(), MAX_SURCHARGE_PERCENT, id, "El recargo", issues);
                case DiscountPercentRule r -> percent(r.percent(), HUNDRED, id, "El descuento", issues);
            }
            if (rule.when().size() > MAX_CONDITIONS) {
                issues.add(ConfigIssue.rule(id, "Demasiadas condiciones."));
            }
            for (Condition c : rule.when()) {
                String problem = conditionProblem(c, all);
                if (problem != null) {
                    issues.add(ConfigIssue.rule(id, "Condición: " + problem));
                }
            }
        }
        if (pricing.minimumTotal() != null) {
            amount(pricing.minimumTotal(), null, "el precio mínimo", issues);
        }
        CalculatorConfig.Tax tax = pricing.tax();
        if (tax.mode() == TaxMode.ADDED) {
            if (tax.rate() == null) {
                issues.add(ConfigIssue.pricing("Indicá el porcentaje de impuesto a sumar."));
            } else {
                percent(tax.rate(), HUNDRED, null, "El impuesto", issues);
            }
        }
        text(tax.label(), 40, ConfigIssue.pricing("El nombre del impuesto admite hasta 40 caracteres."), issues);
        int scale = pricing.rounding().scale();
        if (scale != 0 && scale != 2) {
            issues.add(ConfigIssue.pricing("El redondeo debe ser a pesos enteros o con centavos."));
        }
    }

    private static void optionPrices(Map<String, BigDecimal> prices, Question q, String id, List<ConfigIssue> issues) {
        prices.forEach((optionKey, price) -> {
            if (q.option(optionKey) == null) {
                issues.add(ConfigIssue.rule(id, "Hay un precio para una opción que ya no existe."));
            }
            amount(price, id, "el precio de cada opción", issues);
        });
    }

    private static void tiers(PerUnitRule r, List<ConfigIssue> issues) {
        if (r.tiers().size() > MAX_TIERS) {
            issues.add(ConfigIssue.rule(r.id(), "Hasta " + MAX_TIERS + " tramos de precio."));
        }
        BigDecimal last = BigDecimal.ZERO;
        for (CalculatorConfig.Tier tier : r.tiers()) {
            if (tier.from() == null || tier.from().compareTo(last) <= 0) {
                issues.add(ConfigIssue.rule(r.id(), "Los tramos deben empezar en cantidades mayores a cero y crecientes."));
                return;
            }
            amount(tier.unitPrice(), r.id(), "el precio del tramo", issues);
            last = tier.from();
        }
    }

    // ------------------------------------------------------------------ textos comerciales

    private static void validateCommercial(CalculatorConfig config, List<ConfigIssue> issues) {
        CalculatorConfig.Commercial c = config.commercial();
        text(c.disclaimer(), 500, ConfigIssue.commercial("La aclaración admite hasta 500 caracteres."), issues);
        text(c.whatsappTemplate(), 500, ConfigIssue.commercial("El mensaje de WhatsApp admite hasta 500 caracteres."),
                issues);
        if (c.validityDays() != null && (c.validityDays() < 1 || c.validityDays() > 365)) {
            issues.add(ConfigIssue.commercial("La vigencia debe estar entre 1 y 365 días."));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void amount(BigDecimal value, String ruleId, String what, List<ConfigIssue> issues) {
        if (value == null) {
            issues.add(ruleId == null ? ConfigIssue.pricing("Completá " + what + ".")
                    : ConfigIssue.rule(ruleId, "Completá " + what + "."));
            return;
        }
        if (value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0 || value.stripTrailingZeros().scale() > 2) {
            String message = "Revisá " + what + ": debe ser un importe entre $ 0 y $ 10.000.000.000, con hasta 2 decimales.";
            issues.add(ruleId == null ? ConfigIssue.pricing(message) : ConfigIssue.rule(ruleId, message));
        }
    }

    private static void percent(BigDecimal value, BigDecimal max, String ruleId, String what, List<ConfigIssue> issues) {
        String message = what + " debe ser un porcentaje entre 0 y " + max.toPlainString() + ".";
        if (value == null || value.signum() < 0 || value.compareTo(max) > 0 || value.stripTrailingZeros().scale() > 2) {
            issues.add(ruleId == null ? ConfigIssue.pricing(message) : ConfigIssue.rule(ruleId, message));
        }
    }

    private static void text(String value, int max, ConfigIssue issue, List<ConfigIssue> issues) {
        if (value != null && value.length() > max) {
            issues.add(issue);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static boolean isInteger(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    static BigDecimal parseNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
