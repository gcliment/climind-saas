package com.climind.saas.pricing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.climind.saas.pricing.PricingTestSupport.config;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigValidatorTest {

    private static final String VALID_QUESTIONS = """
            [{"key":"tipo","type":"SINGLE_CHOICE","label":"Tipo","required":true,
              "options":[{"key":"a","label":"A"},{"key":"b","label":"B"}]},
             {"key":"cantidad","type":"QUANTITY","label":"Cantidad","required":true,"min":1,"max":100}]""";
    private static final String VALID_RULES = """
            [{"type":"OPTION_PRICE","id":"p","question":"tipo","prices":{"a":"100","b":"200"}}]""";

    private static List<String> messages(List<ConfigIssue> issues) {
        return issues.stream().map(ConfigIssue::message).toList();
    }

    @Test
    void configuracionValidaPasaAmbosNiveles() {
        var config = config(VALID_QUESTIONS, VALID_RULES, null);
        assertThat(ConfigValidator.validateStructure(config)).isEmpty();
        assertThat(ConfigValidator.validateForPublish(config)).isEmpty();
    }

    @Test
    void borradorVacioEsValidoPeroNoPublicable() {
        var config = CalculatorConfig.empty();
        assertThat(ConfigValidator.validateStructure(config)).isEmpty();
        assertThat(messages(ConfigValidator.validateForPublish(config)))
                .contains("Agregá al menos una pregunta.", "Agregá al menos un precio (fijo, por opción o por unidad).");
    }

    @Test
    void identificadoresDuplicadosOInvalidos() {
        var config = config("""
                [{"key":"tipo","type":"YES_NO","label":"A"},{"key":"tipo","type":"YES_NO","label":"B"},
                 {"key":"Mal Key","type":"YES_NO","label":"C"}]""", """
                [{"type":"FIXED","id":"f","label":"F","amount":"1"},{"type":"FIXED","id":"f","label":"G","amount":"1"}]""", null);
        assertThat(messages(ConfigValidator.validateStructure(config))).contains(
                "Hay dos preguntas con el mismo identificador.", "Identificador de pregunta inválido.",
                "Hay dos reglas con el mismo identificador.");
    }

    @Test
    void referenciasInexistentesOIncompatibles() {
        var config = config(VALID_QUESTIONS, """
                [{"type":"OPTION_PRICE","id":"r1","question":"cantidad","prices":{}},
                 {"type":"PER_UNIT","id":"r2","label":"X","question":"tipo","unitPrice":"10"},
                 {"type":"OPTION_PRICE","id":"r3","question":"tipo","prices":{"zzz":"10"}},
                 {"type":"OPTION_PER_UNIT","id":"r4","question":"tipo","quantityQuestion":"tipo","prices":{"a":"1"}},
                 {"type":"FIXED","id":"r5","label":"X","amount":"1","when":[{"question":"no_existe","op":"IS_TRUE"}]}]""", null);
        var issues = ConfigValidator.validateStructure(config);
        assertThat(issues).extracting(ConfigIssue::item).contains("r1", "r2", "r3", "r4", "r5");
        assertThat(messages(issues)).contains("Hay un precio para una opción que ya no existe.");
    }

    @Test
    void visibilidadSoloPuedeDependerDePreguntasAnteriores() {
        var config = config("""
                [{"key":"primera","type":"YES_NO","label":"A","visibleIf":[{"question":"segunda","op":"IS_TRUE"}]},
                 {"key":"segunda","type":"YES_NO","label":"B","visibleIf":[{"question":"primera","op":"IS_TRUE"}]}]""",
                "[]", null);
        var issues = ConfigValidator.validateStructure(config);
        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.item()).isEqualTo("primera");
            assertThat(i.message()).contains("después");
        });
    }

    @Test
    void operadoresIncompatiblesConElTipoDePregunta() {
        var config = config("""
                [{"key":"tipo","type":"SINGLE_CHOICE","label":"T","options":[{"key":"a","label":"A"}]},
                 {"key":"n","type":"QUANTITY","label":"N","max":10},
                 {"key":"txt","type":"TEXT","label":"Texto"}]""", """
                [{"type":"FIXED","id":"f1","label":"F","amount":"1","when":[{"question":"tipo","op":"GT","value":"1"}]},
                 {"type":"FIXED","id":"f2","label":"F","amount":"1","when":[{"question":"n","op":"GT","value":"diez"}]},
                 {"type":"FIXED","id":"f3","label":"F","amount":"1","when":[{"question":"txt","op":"EQUALS","value":"x"}]},
                 {"type":"FIXED","id":"f4","label":"F","amount":"1","when":[{"question":"tipo","op":"EQUALS","value":"zzz"}]}]""", null);
        assertThat(ConfigValidator.validateStructure(config)).extracting(ConfigIssue::item)
                .containsExactlyInAnyOrder("f1", "f2", "f3", "f4");
    }

    @Test
    void importesYPorcentajesFueraDeRango() {
        var config = config(VALID_QUESTIONS, """
                [{"type":"FIXED","id":"neg","label":"X","amount":"-1"},
                 {"type":"FIXED","id":"enorme","label":"X","amount":"20000000000"},
                 {"type":"FIXED","id":"decimales","label":"X","amount":"1.001"},
                 {"type":"FIXED","id":"vacio","label":"X"},
                 {"type":"DISCOUNT_PERCENT","id":"desc","label":"X","percent":"101"},
                 {"type":"SURCHARGE_PERCENT","id":"rec","label":"X","percent":"600"}]""",
                "\"minimumTotal\":\"-5\",\"tax\":{\"mode\":\"ADDED\"},\"rounding\":{\"scale\":3}");
        var issues = ConfigValidator.validateStructure(config);
        assertThat(issues).extracting(ConfigIssue::item).contains("neg", "enorme", "decimales", "vacio", "desc", "rec");
        assertThat(messages(issues)).contains("Indicá el porcentaje de impuesto a sumar.",
                "El redondeo debe ser a pesos enteros o con centavos.");
    }

    @Test
    void rangosNumericosYTramos() {
        var config = config("""
                [{"key":"a","type":"QUANTITY","label":"A","min":10,"max":5},
                 {"key":"b","type":"QUANTITY","label":"B","min":0.5,"max":10},
                 {"key":"c","type":"NUMBER","label":"C","min":-1,"max":2000000}]""", """
                [{"type":"PER_UNIT","id":"t","label":"T","question":"c","unitPrice":"1",
                  "tiers":[{"from":"10","unitPrice":"1"},{"from":"5","unitPrice":"1"}]}]""", null);
        var issues = ConfigValidator.validateStructure(config);
        assertThat(messages(issues)).contains("El mínimo no puede ser mayor que el máximo.",
                "Para cantidades, el mínimo y el máximo deben ser números enteros.",
                "El mínimo no puede ser negativo.", "El máximo no puede superar 1.000.000.",
                "Los tramos deben empezar en cantidades mayores a cero y crecientes.");
    }

    @Test
    void limitesDeTamano() {
        StringBuilder questions = new StringBuilder("[");
        for (int i = 0; i < 41; i++) {
            questions.append(i == 0 ? "" : ",").append("{\"key\":\"q").append(i).append("\",\"type\":\"YES_NO\",\"label\":\"Q\"}");
        }
        var config = config(questions.append("]").toString(), "[]", null);
        assertThat(messages(ConfigValidator.validateStructure(config)))
                .contains("Un cotizador admite hasta 40 preguntas.");
    }

    @Test
    void requisitosParaPublicar() {
        var config = config("""
                [{"key":"tipo","type":"SINGLE_CHOICE","label":"","options":[{"key":"a","label":"A"}]},
                 {"key":"n","type":"NUMBER","label":"N"}]""", """
                [{"type":"FIXED","id":"f","label":" ","amount":"10"}]""", null);
        assertThat(ConfigValidator.validateStructure(config)).isEmpty();
        assertThat(messages(ConfigValidator.validateForPublish(config))).contains(
                "Escribí el texto de la pregunta.", "Agregá al menos dos opciones.", "Indicá un valor máximo.",
                "Poné un nombre a este importe: es lo que ve el cliente.");
    }

    @Test
    void textosComerciales() {
        var config = config("""
                {"schemaVersion":1,"currency":"ARS","questions":[],"pricing":{"rules":[]},
                 "commercial":{"validityDays":400,"disclaimer":"%s"}}""".formatted("x".repeat(501)));
        assertThat(messages(ConfigValidator.validateStructure(config))).contains(
                "La vigencia debe estar entre 1 y 365 días.", "La aclaración admite hasta 500 caracteres.");
    }

    @Test
    void monedaYVersionNoSoportadas() {
        assertThat(messages(ConfigValidator.validateStructure(config("""
                {"schemaVersion":2,"currency":"ARS"}""")))).containsExactly("Versión de configuración no soportada.");
        assertThat(messages(ConfigValidator.validateStructure(config("""
                {"schemaVersion":1,"currency":"USD"}""")))).contains(
                "Por ahora la única moneda disponible es el peso argentino (ARS).");
    }
}
