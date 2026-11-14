package com.climind.saas.pricing;

import com.climind.saas.pricing.CalculatorConfig.Condition;
import com.climind.saas.pricing.CalculatorConfig.DiscountPercentRule;
import com.climind.saas.pricing.CalculatorConfig.FixedRule;
import com.climind.saas.pricing.CalculatorConfig.OptionPerUnitRule;
import com.climind.saas.pricing.CalculatorConfig.OptionPriceRule;
import com.climind.saas.pricing.CalculatorConfig.PerUnitRule;
import com.climind.saas.pricing.CalculatorConfig.PricingRule;
import com.climind.saas.pricing.CalculatorConfig.Question;
import com.climind.saas.pricing.CalculatorConfig.SurchargePercentRule;
import com.climind.saas.pricing.CalculatorConfig.TaxMode;
import com.climind.saas.pricing.PriceBreakdown.AnswerIssue;
import com.climind.saas.pricing.PriceBreakdown.AnsweredQuestion;
import com.climind.saas.pricing.PriceBreakdown.Line;
import com.climind.saas.pricing.PriceBreakdown.LineKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Motor de cálculo de cotizaciones. Determinístico, sin estado y sin dependencias externas.
 * <p>
 * Orden fijo (docs/BUSINESS_RULES.md): conceptos base → recargos % sobre el subtotal → descuentos % sobre
 * subtotal + recargos → precio mínimo → impuesto → redondeo final. Cada línea se calcula con 2 decimales
 * HALF_UP; el total se redondea a la escala configurada (0 o 2).
 */
public final class PricingEngine {

    public enum Mode {
        /** Cotización real: cualquier problema en las respuestas lanza {@link InvalidAnswersException}. */
        STRICT,
        /** Vista previa del constructor: calcula con lo que haya y devuelve los problemas. */
        PREVIEW
    }

    /** Máximo representable en numeric(14,2). */
    public static final BigDecimal MAX_TOTAL = new BigDecimal("999999999999.99");
    private static final int LINE_SCALE = 2;
    private static final int DEFAULT_TEXT_LENGTH = 1000;

    private PricingEngine() {
    }

    /**
     * @param config     configuración (se asume validada estructuralmente)
     * @param rawAnswers respuestas tal como llegan del JSON: String, Number, Boolean o List
     * @param today      fecha actual en la zona del negocio (para validar fechas); null = sin validar pasado
     */
    public static PriceBreakdown calculate(CalculatorConfig config, Map<String, ?> rawAnswers, Mode mode,
                                           LocalDate today) {
        Map<String, ?> raw = rawAnswers == null ? Map.of() : rawAnswers;
        List<AnswerIssue> issues = new ArrayList<>();
        Map<String, Object> answers = new LinkedHashMap<>();
        List<AnsweredQuestion> answered = new ArrayList<>();
        List<String> visible = new ArrayList<>();

        Set<String> known = config.questions().stream().map(Question::key).collect(Collectors.toSet());
        raw.keySet().stream().filter(k -> !known.contains(k))
                .forEach(k -> issues.add(new AnswerIssue(k, "Pregunta desconocida.")));

        boolean complete = true;
        for (Question q : config.questions()) {
            if (!Conditions.allMatch(q.visibleIf(), answers, config)) {
                continue; // oculta: no se valida, no se guarda, no participa del precio
            }
            visible.add(q.key());
            Object value = raw.get(q.key());
            if (isEmpty(value)) {
                if (q.required()) {
                    complete = false;
                    issues.add(new AnswerIssue(q.key(), "Respondé esta pregunta."));
                }
                continue;
            }
            try {
                Object parsed = parse(q, value, today);
                answers.put(q.key(), parsed);
                answered.add(new AnsweredQuestion(q.key(), q.label(), display(q, parsed)));
            } catch (IllegalArgumentException e) {
                if (q.required()) {
                    complete = false;
                }
                issues.add(new AnswerIssue(q.key(), e.getMessage()));
            }
        }
        if (mode == Mode.STRICT && !issues.isEmpty()) {
            throw new InvalidAnswersException(issues);
        }
        return price(config, answers, answered, visible, issues, complete && issues.isEmpty());
    }

    // ------------------------------------------------------------------ cálculo

    private static PriceBreakdown price(CalculatorConfig config, Map<String, Object> answers,
                                        List<AnsweredQuestion> answered, List<String> visible,
                                        List<AnswerIssue> issues, boolean complete) {
        CalculatorConfig.Pricing pricing = config.pricing();
        List<Line> lines = new ArrayList<>();

        // 1. Conceptos base
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PricingRule rule : pricing.rules()) {
            if (!rule.isBase() || !Conditions.allMatch(rule.when(), answers, config)) {
                continue;
            }
            for (Line line : baseLines(rule, config, answers)) {
                if (line.amount().signum() != 0) {
                    lines.add(line);
                    subtotal = subtotal.add(line.amount());
                }
            }
        }
        subtotal = checked(subtotal);

        // 2. Recargos sobre el subtotal
        BigDecimal surcharges = BigDecimal.ZERO;
        for (PricingRule rule : pricing.rules()) {
            if (rule instanceof SurchargePercentRule r && Conditions.allMatch(r.when(), answers, config)) {
                BigDecimal amount = percentOf(subtotal, r.percent());
                if (amount.signum() != 0) {
                    lines.add(new Line(r.id(), LineKind.SURCHARGE, r.label(), null, null, null,
                            r.percent().stripTrailingZeros(), amount));
                    surcharges = surcharges.add(amount);
                }
            }
        }

        // 3. Descuentos sobre subtotal + recargos (el total nunca queda negativo)
        BigDecimal discountBase = checked(subtotal.add(surcharges));
        BigDecimal discounts = BigDecimal.ZERO;
        for (PricingRule rule : pricing.rules()) {
            if (rule instanceof DiscountPercentRule r && Conditions.allMatch(r.when(), answers, config)) {
                BigDecimal amount = percentOf(discountBase, r.percent()).min(discountBase.subtract(discounts));
                if (amount.signum() > 0) {
                    lines.add(new Line(r.id(), LineKind.DISCOUNT, r.label(), null, null, null,
                            r.percent().stripTrailingZeros(), amount.negate()));
                    discounts = discounts.add(amount);
                }
            }
        }
        BigDecimal beforeMinimum = discountBase.subtract(discounts);

        // 4. Precio mínimo
        BigDecimal minimumAdjustment = BigDecimal.ZERO.setScale(LINE_SCALE);
        if (pricing.minimumTotal() != null && beforeMinimum.compareTo(pricing.minimumTotal()) < 0) {
            minimumAdjustment = scale(pricing.minimumTotal().subtract(beforeMinimum));
            lines.add(new Line(null, LineKind.MINIMUM, "Ajuste a precio mínimo", null, null, null, null,
                    minimumAdjustment));
        }
        BigDecimal beforeTax = beforeMinimum.add(minimumAdjustment);

        // 5. Impuesto
        CalculatorConfig.Tax tax = pricing.tax();
        BigDecimal taxAmount = BigDecimal.ZERO.setScale(LINE_SCALE);
        if (tax.mode() == TaxMode.ADDED && tax.rate() != null && tax.rate().signum() > 0) {
            taxAmount = percentOf(beforeTax, tax.rate());
            lines.add(new Line(null, LineKind.TAX, taxLabel(tax), null, null, null, tax.rate().stripTrailingZeros(),
                    taxAmount));
        }
        BigDecimal unrounded = checked(beforeTax.add(taxAmount));

        // 6. Redondeo final
        BigDecimal total = unrounded.setScale(pricing.rounding().scale(), RoundingMode.HALF_UP);
        BigDecimal roundingAdjustment = scale(total.subtract(unrounded));

        return new PriceBreakdown(config.currency(), List.copyOf(lines), scale(subtotal), scale(surcharges),
                scale(discounts), minimumAdjustment, tax.mode(), tax.mode() == TaxMode.FINAL ? null : taxLabel(tax),
                tax.mode() == TaxMode.ADDED ? tax.rate() : null, taxAmount, roundingAdjustment, total,
                answers, List.copyOf(answered), List.copyOf(visible), List.copyOf(issues), complete);
    }

    private static List<Line> baseLines(PricingRule rule, CalculatorConfig config, Map<String, Object> answers) {
        return switch (rule) {
            case FixedRule r -> List.of(new Line(r.id(), LineKind.BASE, r.label(), null, null, null, null,
                    scale(r.amount())));
            case OptionPriceRule r -> {
                Question q = config.question(r.question());
                List<Line> result = new ArrayList<>();
                for (String optionKey : selectedOptions(answers.get(r.question()))) {
                    BigDecimal price = r.prices().get(optionKey);
                    if (price != null) {
                        result.add(new Line(r.id(), LineKind.BASE, optionLabel(q, optionKey), null, null, null, null,
                                scale(price)));
                    }
                }
                yield result;
            }
            case PerUnitRule r -> {
                if (!(answers.get(r.question()) instanceof BigDecimal quantity)) {
                    yield List.of();
                }
                Question q = config.question(r.question());
                BigDecimal unitPrice = tierPrice(r, quantity);
                String label = r.label() == null || r.label().isBlank() ? q.label() : r.label();
                yield List.of(new Line(r.id(), LineKind.BASE, label, quantity.stripTrailingZeros(), q.unit(),
                        scale(unitPrice), null, scale(quantity.multiply(unitPrice))));
            }
            case OptionPerUnitRule r -> {
                if (!(answers.get(r.question()) instanceof String optionKey)
                        || !(answers.get(r.quantityQuestion()) instanceof BigDecimal quantity)) {
                    yield List.of();
                }
                BigDecimal price = r.prices().get(optionKey);
                if (price == null) {
                    yield List.of();
                }
                Question q = config.question(r.question());
                Question qtyQuestion = config.question(r.quantityQuestion());
                yield List.of(new Line(r.id(), LineKind.BASE, optionLabel(q, optionKey), quantity.stripTrailingZeros(),
                        qtyQuestion.unit(), scale(price), null, scale(quantity.multiply(price))));
            }
            default -> List.of();
        };
    }

    /** Precio del tramo más alto alcanzado (o el precio base si no alcanza ningún tramo). */
    static BigDecimal tierPrice(PerUnitRule rule, BigDecimal quantity) {
        BigDecimal price = rule.unitPrice();
        for (CalculatorConfig.Tier tier : rule.tiers()) {
            if (quantity.compareTo(tier.from()) >= 0) {
                price = tier.unitPrice();
            }
        }
        return price;
    }

    private static BigDecimal percentOf(BigDecimal base, BigDecimal percent) {
        return scale(base.multiply(percent).divide(ConfigValidator.HUNDRED, 10, RoundingMode.HALF_UP));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(LINE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal checked(BigDecimal value) {
        if (value.compareTo(MAX_TOTAL) > 0) {
            throw new InvalidAnswersException(List.of(new AnswerIssue(null,
                    "El importe calculado supera el máximo permitido. Revisá las cantidades.")));
        }
        return value;
    }

    private static String taxLabel(CalculatorConfig.Tax tax) {
        if (tax.label() != null && !tax.label().isBlank()) {
            return tax.label();
        }
        return tax.mode() == TaxMode.EXCLUDED ? "Precios sin impuestos" : "Impuestos";
    }

    // ------------------------------------------------------------------ respuestas

    /** Normaliza y valida una respuesta. Lanza IllegalArgumentException con un mensaje para el visitante. */
    static Object parse(Question q, Object value, LocalDate today) {
        return switch (q.type()) {
            case SINGLE_CHOICE -> {
                String key = String.valueOf(value);
                if (value instanceof Collection<?> || q.option(key) == null) {
                    throw new IllegalArgumentException("Elegí una de las opciones.");
                }
                yield key;
            }
            case MULTI_CHOICE -> {
                if (!(value instanceof Collection<?> list)) {
                    throw new IllegalArgumentException("Respuesta inválida.");
                }
                Set<String> keys = new LinkedHashSet<>();
                for (Object item : list) {
                    String key = String.valueOf(item);
                    if (q.option(key) == null) {
                        throw new IllegalArgumentException("Elegí opciones de la lista.");
                    }
                    keys.add(key);
                }
                // Orden de la configuración, no del cliente: el snapshot es estable.
                yield q.options().stream().map(CalculatorConfig.Option::key).filter(keys::contains).toList();
            }
            case QUANTITY, NUMBER -> parseQuantity(q, value);
            case YES_NO -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                String s = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
                if (s.equals("true") || s.equals("si") || s.equals("sí")) {
                    yield true;
                }
                if (s.equals("false") || s.equals("no")) {
                    yield false;
                }
                throw new IllegalArgumentException("Respondé sí o no.");
            }
            case TEXT -> {
                if (value instanceof Collection<?>) {
                    throw new IllegalArgumentException("Respuesta inválida.");
                }
                String text = String.valueOf(value).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
                int max = q.maxLength() == null ? DEFAULT_TEXT_LENGTH : q.maxLength();
                if (text.length() > max) {
                    throw new IllegalArgumentException("El texto admite hasta " + max + " caracteres.");
                }
                yield text;
            }
            case DATE -> {
                LocalDate date;
                try {
                    date = LocalDate.parse(String.valueOf(value).strip());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Ingresá una fecha válida.");
                }
                if (today != null && date.isBefore(today)) {
                    throw new IllegalArgumentException("Elegí una fecha a partir de hoy.");
                }
                if (today != null && date.isAfter(today.plusYears(3))) {
                    throw new IllegalArgumentException("Elegí una fecha más cercana.");
                }
                yield date.toString();
            }
        };
    }

    private static BigDecimal parseQuantity(Question q, Object value) {
        BigDecimal number;
        if (value instanceof BigDecimal bd) {
            number = bd;
        } else if (value instanceof Number n) {
            number = new BigDecimal(n.toString());
        } else {
            String s = String.valueOf(value).strip();
            if (s.indexOf(',') >= 0 && s.indexOf('.') < 0) {
                s = s.replace(',', '.'); // "12,5" (formato argentino)
            }
            try {
                number = new BigDecimal(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Ingresá un número.");
            }
        }
        if (q.integerOnly() && !ConfigValidator.isInteger(number)) {
            throw new IllegalArgumentException("Ingresá un número entero.");
        }
        if (number.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("Usá como máximo 4 decimales.");
        }
        BigDecimal min = q.min() == null ? BigDecimal.ZERO : q.min();
        BigDecimal max = q.max() == null ? ConfigValidator.MAX_QUANTITY : q.max();
        if (number.compareTo(min) < 0 || number.compareTo(max) > 0) {
            throw new IllegalArgumentException("Ingresá un valor entre " + plain(min) + " y " + plain(max) + ".");
        }
        return number;
    }

    private static String display(Question q, Object parsed) {
        return switch (q.type()) {
            case SINGLE_CHOICE -> optionLabel(q, (String) parsed);
            case MULTI_CHOICE -> {
                List<String> keys = selectedOptions(parsed);
                yield keys.isEmpty() ? "Ninguno" : keys.stream().map(k -> optionLabel(q, k))
                        .collect(Collectors.joining(", "));
            }
            case QUANTITY, NUMBER -> plain((BigDecimal) parsed) + (q.unit() == null || q.unit().isBlank() ? "" : " " + q.unit());
            case YES_NO -> Boolean.TRUE.equals(parsed) ? "Sí" : "No";
            case TEXT -> String.valueOf(parsed);
            case DATE -> LocalDate.parse((String) parsed).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        };
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    @SuppressWarnings("unchecked")
    private static List<String> selectedOptions(Object answer) {
        if (answer instanceof String s) {
            return List.of(s);
        }
        if (answer instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    private static String optionLabel(Question q, String optionKey) {
        CalculatorConfig.Option option = q == null ? null : q.option(optionKey);
        return option == null || option.label() == null ? optionKey : option.label();
    }

    /** Sin respuesta: null, texto vacío o lista vacía (selección múltiple sin opciones marcadas). */
    private static boolean isEmpty(Object value) {
        return value == null
                || (value instanceof String s && s.isBlank())
                || (value instanceof Collection<?> c && c.isEmpty());
    }
}
