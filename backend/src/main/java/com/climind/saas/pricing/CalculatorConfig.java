package com.climind.saas.pricing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Configuración versionable de un cotizador ({@code schemaVersion = 1}). Es el contrato documentado en
 * docs/BUSINESS_RULES.md. Se persiste como JSONB; los importes son {@link BigDecimal}.
 * <p>
 * Este paquete es puro: no depende de Spring, JPA ni de otros módulos (solo de las anotaciones de Jackson).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CalculatorConfig(
        int schemaVersion,
        String currency,
        List<Question> questions,
        Pricing pricing,
        Commercial commercial) {

    public static final int CURRENT_SCHEMA = 1;

    public CalculatorConfig {
        questions = questions == null ? List.of() : List.copyOf(questions);
        pricing = pricing == null ? Pricing.empty() : pricing;
        commercial = commercial == null ? Commercial.defaults() : commercial;
        currency = currency == null || currency.isBlank() ? "ARS" : currency;
    }

    public static CalculatorConfig empty() {
        return new CalculatorConfig(CURRENT_SCHEMA, "ARS", List.of(), Pricing.empty(), Commercial.defaults());
    }

    public Question question(String key) {
        return questions.stream().filter(q -> q.key().equals(key)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ preguntas

    public enum QuestionType {
        SINGLE_CHOICE, MULTI_CHOICE, QUANTITY, NUMBER, YES_NO, TEXT, DATE;

        public boolean isChoice() {
            return this == SINGLE_CHOICE || this == MULTI_CHOICE;
        }

        public boolean isNumeric() {
            return this == QUANTITY || this == NUMBER;
        }

        /** TEXT y DATE se registran pero no modifican el precio (MVP). */
        public boolean affectsPrice() {
            return this != TEXT && this != DATE;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Question(
            String key,
            QuestionType type,
            String label,
            String help,
            Boolean required,
            List<Option> options,
            BigDecimal min,
            BigDecimal max,
            Boolean integer,
            String unit,
            Integer maxLength,
            List<Condition> visibleIf) {

        public Question {
            required = Boolean.TRUE.equals(required);
            options = options == null ? List.of() : List.copyOf(options);
            visibleIf = visibleIf == null ? List.of() : List.copyOf(visibleIf);
        }

        public Option option(String optionKey) {
            return options.stream().filter(o -> o.key().equals(optionKey)).findFirst().orElse(null);
        }

        /** QUANTITY siempre es entera; NUMBER solo si {@code integer = true}. */
        public boolean integerOnly() {
            return type == QuestionType.QUANTITY || Boolean.TRUE.equals(integer);
        }
    }

    public record Option(String key, String label) {
    }

    public enum Operator { EQUALS, NOT_EQUALS, INCLUDES, IS_TRUE, IS_FALSE, GT, GTE, LT, LTE }

    /** Condición simple sobre la respuesta de una pregunta. {@code value} siempre como texto. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Condition(String question, Operator op, String value) {
    }

    // ------------------------------------------------------------------ precios

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Pricing(List<PricingRule> rules, BigDecimal minimumTotal, Tax tax, Rounding rounding) {

        public Pricing {
            rules = rules == null ? List.of() : List.copyOf(rules);
            tax = tax == null ? Tax.finalPrices() : tax;
            rounding = rounding == null ? Rounding.wholePesos() : rounding;
        }

        public static Pricing empty() {
            return new Pricing(List.of(), null, Tax.finalPrices(), Rounding.wholePesos());
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = FixedRule.class, name = "FIXED"),
            @JsonSubTypes.Type(value = OptionPriceRule.class, name = "OPTION_PRICE"),
            @JsonSubTypes.Type(value = PerUnitRule.class, name = "PER_UNIT"),
            @JsonSubTypes.Type(value = OptionPerUnitRule.class, name = "OPTION_PER_UNIT"),
            @JsonSubTypes.Type(value = SurchargePercentRule.class, name = "SURCHARGE_PERCENT"),
            @JsonSubTypes.Type(value = DiscountPercentRule.class, name = "DISCOUNT_PERCENT")
    })
    public sealed interface PricingRule
            permits FixedRule, OptionPriceRule, PerUnitRule, OptionPerUnitRule, SurchargePercentRule,
            DiscountPercentRule {

        String id();

        String label();

        List<Condition> when();

        /** Conceptos que forman el subtotal (los porcentajes se aplican después). No forma parte del JSON. */
        @JsonIgnore
        default boolean isBase() {
            return !(this instanceof SurchargePercentRule) && !(this instanceof DiscountPercentRule);
        }
    }

    /** Importe fijo (por ejemplo, "Instalación +$20.000" con condición). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FixedRule(String id, String label, BigDecimal amount, List<Condition> when) implements PricingRule {
        public FixedRule {
            when = when == null ? List.of() : List.copyOf(when);
        }
    }

    /** Precio según la opción elegida (en selección múltiple, suma cada opción). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionPriceRule(String id, String label, String question, Map<String, BigDecimal> prices,
                                  List<Condition> when) implements PricingRule {
        public OptionPriceRule {
            prices = prices == null ? Map.of() : Map.copyOf(prices);
            when = when == null ? List.of() : List.copyOf(when);
        }
    }

    /**
     * Cantidad × precio unitario. Con tramos, se usa el precio del tramo más alto cuyo {@code from} sea
     * menor o igual a la cantidad (precio por volumen para toda la cantidad, no escalonado).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PerUnitRule(String id, String label, String question, BigDecimal unitPrice, List<Tier> tiers,
                              List<Condition> when) implements PricingRule {
        public PerUnitRule {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
            when = when == null ? List.of() : List.copyOf(when);
        }
    }

    public record Tier(BigDecimal from, BigDecimal unitPrice) {
    }

    /** Cantidad (de otra pregunta) × precio de la opción elegida. Ej.: invitados × precio del menú. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionPerUnitRule(String id, String label, String question, String quantityQuestion,
                                    Map<String, BigDecimal> prices, List<Condition> when) implements PricingRule {
        public OptionPerUnitRule {
            prices = prices == null ? Map.of() : Map.copyOf(prices);
            when = when == null ? List.of() : List.copyOf(when);
        }
    }

    /** Recargo porcentual sobre el subtotal. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SurchargePercentRule(String id, String label, BigDecimal percent, List<Condition> when)
            implements PricingRule {
        public SurchargePercentRule {
            when = when == null ? List.of() : List.copyOf(when);
        }
    }

    /** Descuento porcentual sobre subtotal + recargos. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiscountPercentRule(String id, String label, BigDecimal percent, List<Condition> when)
            implements PricingRule {
        public DiscountPercentRule {
            when = when == null ? List.of() : List.copyOf(when);
        }
    }

    public enum TaxMode {
        /** Precios finales, impuestos incluidos. */
        FINAL,
        /** Precios sin impuestos: solo se muestra la leyenda. */
        EXCLUDED,
        /** Se suma la tasa configurada como línea aparte. */
        ADDED
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tax(TaxMode mode, BigDecimal rate, String label) {
        public Tax {
            mode = mode == null ? TaxMode.FINAL : mode;
        }

        public static Tax finalPrices() {
            return new Tax(TaxMode.FINAL, null, null);
        }
    }

    /** Decimales del total: 0 (pesos enteros) o 2 (con centavos). Redondeo HALF_UP. */
    public record Rounding(Integer scale) {
        public Rounding {
            scale = scale == null ? 0 : scale;
        }

        public static Rounding wholePesos() {
            return new Rounding(0);
        }
    }

    // ------------------------------------------------------------------ textos comerciales

    public enum LeadCapture { AFTER_ESTIMATE, BEFORE_ESTIMATE }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Commercial(String disclaimer, Integer validityDays, LeadCapture leadCapture,
                             String whatsappTemplate) {

        public static final String DEFAULT_DISCLAIMER =
                "Este importe es una estimación. El precio final está sujeto a confirmación del negocio.";
        public static final String DEFAULT_WHATSAPP_TEMPLATE =
                "Hola, realicé una cotización desde su página. Me interesa {servicio} y el precio estimado es de {total}. Mi código de consulta es {codigo}.";

        public Commercial {
            disclaimer = disclaimer == null || disclaimer.isBlank() ? DEFAULT_DISCLAIMER : disclaimer;
            leadCapture = leadCapture == null ? LeadCapture.AFTER_ESTIMATE : leadCapture;
            whatsappTemplate = whatsappTemplate == null || whatsappTemplate.isBlank()
                    ? DEFAULT_WHATSAPP_TEMPLATE : whatsappTemplate;
        }

        public static Commercial defaults() {
            return new Commercial(DEFAULT_DISCLAIMER, 15, LeadCapture.AFTER_ESTIMATE, DEFAULT_WHATSAPP_TEMPLATE);
        }
    }
}
