package com.climind.saas.pricing;

import com.climind.saas.pricing.PriceBreakdown.LineKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.climind.saas.pricing.PricingTestSupport.config;
import static com.climind.saas.pricing.PricingTestSupport.preview;
import static com.climind.saas.pricing.PricingTestSupport.strict;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingEngineTest {

    private static final String QTY = """
            [{"key":"cantidad","type":"QUANTITY","label":"Cantidad","required":true,"min":1,"max":1000,"unit":"u."}]""";

    private static void assertTotal(PriceBreakdown result, String expected) {
        assertThat(result.total()).isEqualByComparingTo(expected);
    }

    @Nested
    class TiposDeRegla {

        @Test
        void precioFijo() {
            var config = config(QTY, """
                    [{"type":"FIXED","id":"base","label":"Servicio básico","amount":"50000"}]""", null);
            var result = strict(config, Map.of("cantidad", 1));
            assertTotal(result, "50000");
            assertThat(result.lines()).singleElement().satisfies(l -> {
                assertThat(l.kind()).isEqualTo(LineKind.BASE);
                assertThat(l.label()).isEqualTo("Servicio básico");
            });
        }

        @Test
        void precioPorCantidad() {
            // 30 invitados × $5.000 = $150.000
            var config = config("""
                    [{"key":"invitados","type":"QUANTITY","label":"Invitados","required":true,"min":1,"max":500}]""", """
                    [{"type":"PER_UNIT","id":"por_invitado","label":"Por invitado","question":"invitados","unitPrice":"5000"}]""", null);
            var result = strict(config, Map.of("invitados", 30));
            assertTotal(result, "150000");
            var line = result.lines().getFirst();
            assertThat(line.quantity()).isEqualByComparingTo("30");
            assertThat(line.unitPrice()).isEqualByComparingTo("5000");
        }

        @Test
        void precioPorMedidaConDecimales() {
            // 12,5 m² × $3.000 = $37.500 (acepta coma decimal)
            var config = config("""
                    [{"key":"m2","type":"NUMBER","label":"Superficie","unit":"m²","required":true,"min":1,"max":5000}]""", """
                    [{"type":"PER_UNIT","id":"m2_precio","label":"Superficie","question":"m2","unitPrice":"3000"}]""", null);
            assertTotal(strict(config, Map.of("m2", "12,5")), "37500");
            assertTotal(strict(config, Map.of("m2", 12.5)), "37500");
            assertThat(strict(config, Map.of("m2", "12.5")).lines().getFirst().unit()).isEqualTo("m²");
        }

        @Test
        void tramosPorVolumenSeAplicanATodaLaCantidad() {
            var config = config(QTY, """
                    [{"type":"PER_UNIT","id":"u","label":"Unidades","question":"cantidad","unitPrice":"1000",
                      "tiers":[{"from":"10","unitPrice":"900"},{"from":"50","unitPrice":"800"}]}]""", null);
            assertTotal(strict(config, Map.of("cantidad", 9)), "9000");
            assertTotal(strict(config, Map.of("cantidad", 10)), "9000");   // 10 × 900
            assertTotal(strict(config, Map.of("cantidad", 49)), "44100");  // 49 × 900
            assertTotal(strict(config, Map.of("cantidad", 50)), "40000");  // 50 × 800
        }

        @Test
        void precioPorOpcionUnicaYMultiple() {
            var config = config("""
                    [{"key":"tipo","type":"SINGLE_CHOICE","label":"Tipo","required":true,
                      "options":[{"key":"basica","label":"Limpieza básica"},{"key":"profunda","label":"Limpieza profunda"}]},
                     {"key":"extras","type":"MULTI_CHOICE","label":"Adicionales",
                      "options":[{"key":"vidrios","label":"Vidrios"},{"key":"horno","label":"Horno"},{"key":"alfombra","label":"Alfombras"}]}]""", """
                    [{"type":"OPTION_PRICE","id":"tipo_precio","question":"tipo","prices":{"basica":"50000","profunda":"80000"}},
                     {"type":"OPTION_PRICE","id":"extras_precio","question":"extras","prices":{"vidrios":"12000","horno":"9000","alfombra":"15000"}}]""", null);

            var result = strict(config, Map.of("tipo", "profunda", "extras", List.of("horno", "vidrios")));
            assertTotal(result, "101000");
            assertThat(result.lines()).extracting(PriceBreakdown.Line::label)
                    .containsExactly("Limpieza profunda", "Vidrios", "Horno"); // orden de la configuración
            assertThat(result.answers().get("extras")).isEqualTo(List.of("vidrios", "horno"));
        }

        @Test
        void precioPorUnidadSegunOpcion() {
            // 40 invitados × menú premium $18.000 = $720.000
            var config = config("""
                    [{"key":"invitados","type":"QUANTITY","label":"Invitados","required":true,"min":10,"max":500},
                     {"key":"menu","type":"SINGLE_CHOICE","label":"Menú","required":true,
                      "options":[{"key":"clasico","label":"Menú clásico"},{"key":"premium","label":"Menú premium"}]}]""", """
                    [{"type":"OPTION_PER_UNIT","id":"menu_x_inv","question":"menu","quantityQuestion":"invitados",
                      "prices":{"clasico":"12000","premium":"18000"}}]""", null);
            var result = strict(config, Map.of("invitados", 40, "menu", "premium"));
            assertTotal(result, "720000");
            assertThat(result.lines().getFirst().label()).isEqualTo("Menú premium");
        }

        @Test
        void adicionalFijoCondicional() {
            var config = config("""
                    [{"key":"instalacion","type":"YES_NO","label":"¿Necesitás instalación?"}]""", """
                    [{"type":"FIXED","id":"base","label":"Equipo","amount":"100000"},
                     {"type":"FIXED","id":"inst","label":"Instalación","amount":"20000",
                      "when":[{"question":"instalacion","op":"IS_TRUE"}]}]""", null);
            assertTotal(strict(config, Map.of("instalacion", true)), "120000");
            assertTotal(strict(config, Map.of("instalacion", "sí")), "120000");
            assertTotal(strict(config, Map.of("instalacion", false)), "100000");
            assertTotal(strict(config, Map.of()), "100000");
        }

        @Test
        void precioUnitarioDistintoSegunCantidadConCondiciones() {
            var config = config(QTY, """
                    [{"type":"PER_UNIT","id":"normal","label":"Unidades","question":"cantidad","unitPrice":"1000",
                      "when":[{"question":"cantidad","op":"LTE","value":"20"}]},
                     {"type":"PER_UNIT","id":"mayorista","label":"Unidades (mayorista)","question":"cantidad","unitPrice":"700",
                      "when":[{"question":"cantidad","op":"GT","value":"20"}]}]""", null);
            assertTotal(strict(config, Map.of("cantidad", 20)), "20000");
            assertTotal(strict(config, Map.of("cantidad", 21)), "14700");
        }
    }

    @Nested
    class OrdenDeAplicacion {

        private final String rules = """
                [{"type":"FIXED","id":"base","label":"Servicio","amount":"100000"},
                 {"type":"DISCOUNT_PERCENT","id":"promo","label":"Promoción","percent":"10"},
                 {"type":"SURCHARGE_PERCENT","id":"urgencia","label":"Urgencia","percent":"20",
                  "when":[{"question":"urgente","op":"IS_TRUE"}]}]""";
        private final String questions = """
                [{"key":"urgente","type":"YES_NO","label":"¿Es urgente?"}]""";

        @Test
        void recargoSobreSubtotalYDescuentoSobreSubtotalMasRecargo() {
            // 100.000 + 20% = 120.000; −10% de 120.000 = 108.000 (aunque el descuento esté antes en la lista)
            var result = strict(config(questions, rules, null), Map.of("urgente", true));
            assertThat(result.subtotal()).isEqualByComparingTo("100000");
            assertThat(result.surchargesTotal()).isEqualByComparingTo("20000");
            assertThat(result.discountsTotal()).isEqualByComparingTo("12000");
            assertTotal(result, "108000");
            assertThat(result.lines()).extracting(PriceBreakdown.Line::kind)
                    .containsExactly(LineKind.BASE, LineKind.SURCHARGE, LineKind.DISCOUNT);
            assertThat(result.lines().get(2).amount()).isEqualByComparingTo("-12000");
        }

        @Test
        void minimoDespuesDeDescuentosEImpuestoDespuesDelMinimo() {
            var config = config(questions, """
                    [{"type":"FIXED","id":"base","label":"Servicio","amount":"20000"},
                     {"type":"DISCOUNT_PERCENT","id":"promo","label":"Promoción","percent":"50"}]""", """
                    "minimumTotal":"30000","tax":{"mode":"ADDED","rate":"21","label":"IVA"}""");
            var result = strict(config, Map.of());
            // 20.000 − 50% = 10.000 → mínimo 30.000 (+20.000) → IVA 21% = 6.300 → 36.300
            assertThat(result.minimumAdjustment()).isEqualByComparingTo("20000");
            assertThat(result.taxAmount()).isEqualByComparingTo("6300");
            assertTotal(result, "36300");
            assertThat(result.lines()).extracting(PriceBreakdown.Line::kind)
                    .containsExactly(LineKind.BASE, LineKind.DISCOUNT, LineKind.MINIMUM, LineKind.TAX);
        }

        @Test
        void sinAjusteCuandoSuperaElMinimo() {
            var config = config(questions, """
                    [{"type":"FIXED","id":"base","label":"Servicio","amount":"50000"}]""", "\"minimumTotal\":\"30000\"");
            var result = strict(config, Map.of());
            assertThat(result.minimumAdjustment()).isZero();
            assertThat(result.lines()).noneMatch(l -> l.kind() == LineKind.MINIMUM);
        }

        @Test
        void losDescuentosNuncaDejanElTotalNegativo() {
            var config = config(questions, """
                    [{"type":"FIXED","id":"base","label":"Servicio","amount":"10000"},
                     {"type":"DISCOUNT_PERCENT","id":"d1","label":"Uno","percent":"60"},
                     {"type":"DISCOUNT_PERCENT","id":"d2","label":"Dos","percent":"60"}]""", null);
            var result = strict(config, Map.of());
            assertThat(result.discountsTotal()).isEqualByComparingTo("10000");
            assertTotal(result, "0");
        }
    }

    @Nested
    class Impuestos {

        private final String rules = """
                [{"type":"FIXED","id":"base","label":"Servicio","amount":"100000"}]""";

        @Test
        void preciosFinalesNoSumanNada() {
            var result = strict(config("[]", rules, "\"tax\":{\"mode\":\"FINAL\"}"), Map.of());
            assertTotal(result, "100000");
            assertThat(result.taxLabel()).isNull();
        }

        @Test
        void preciosSinImpuestosSoloMuestranLaLeyenda() {
            var result = strict(config("[]", rules, "\"tax\":{\"mode\":\"EXCLUDED\",\"label\":\"Más IVA\"}"), Map.of());
            assertTotal(result, "100000");
            assertThat(result.taxLabel()).isEqualTo("Más IVA");
            assertThat(result.taxAmount()).isZero();
        }

        @Test
        void impuestoSumado() {
            var result = strict(config("[]", rules, "\"tax\":{\"mode\":\"ADDED\",\"rate\":\"10.5\",\"label\":\"IVA\"}"), Map.of());
            assertThat(result.taxAmount()).isEqualByComparingTo("10500");
            assertTotal(result, "110500");
            assertThat(result.lines().getLast().label()).isEqualTo("IVA");
        }
    }

    @Nested
    class Redondeo {

        @Test
        void totalAPesosEnterosConHalfUp() {
            var config = config("[]", """
                    [{"type":"FIXED","id":"a","label":"A","amount":"1000.50"}]""", "\"rounding\":{\"scale\":0}");
            var result = strict(config, Map.of());
            assertTotal(result, "1001");
            assertThat(result.total().scale()).isZero();
            assertThat(result.roundingAdjustment()).isEqualByComparingTo("0.50");
        }

        @Test
        void totalConCentavos() {
            var config = config("[]", """
                    [{"type":"FIXED","id":"a","label":"A","amount":"1000.50"}]""", "\"rounding\":{\"scale\":2}");
            var result = strict(config, Map.of());
            assertThat(result.total()).isEqualTo(new BigDecimal("1000.50"));
            assertThat(result.roundingAdjustment()).isZero();
        }

        @Test
        void porcentajesConFraccionesSeRedondeanPorLinea() {
            // 33.333 × 7,5% = 2.499,975 → 2.499,98
            var config = config("[]", """
                    [{"type":"FIXED","id":"a","label":"A","amount":"33333"},
                     {"type":"SURCHARGE_PERCENT","id":"r","label":"Recargo","percent":"7.5"}]""", "\"rounding\":{\"scale\":2}");
            var result = strict(config, Map.of());
            assertThat(result.surchargesTotal()).isEqualTo(new BigDecimal("2499.98"));
            assertThat(result.total()).isEqualTo(new BigDecimal("35832.98"));
        }

        @Test
        void sinErroresDeComaFlotante() {
            var config = config(QTY, """
                    [{"type":"PER_UNIT","id":"u","label":"U","question":"cantidad","unitPrice":"0.10"}]""", "\"rounding\":{\"scale\":2}");
            assertThat(strict(config, Map.of("cantidad", 3)).total()).isEqualTo(new BigDecimal("0.30"));
        }
    }

    @Nested
    class VisibilidadYCondiciones {

        private final CalculatorConfig config = config("""
                [{"key":"tipo","type":"SINGLE_CHOICE","label":"Tipo","required":true,
                  "options":[{"key":"basica","label":"Básica"},{"key":"profunda","label":"Profunda"}]},
                 {"key":"ventanas","type":"QUANTITY","label":"Ventanas","required":true,"min":1,"max":50,
                  "visibleIf":[{"question":"tipo","op":"EQUALS","value":"profunda"}]},
                 {"key":"frecuencia","type":"SINGLE_CHOICE","label":"Frecuencia",
                  "options":[{"key":"unica","label":"Única vez"},{"key":"semanal","label":"Semanal"}]}]""", """
                [{"type":"OPTION_PRICE","id":"tipo_p","question":"tipo","prices":{"basica":"50000","profunda":"80000"}},
                 {"type":"PER_UNIT","id":"ventanas_p","label":"Ventanas","question":"ventanas","unitPrice":"2000"},
                 {"type":"DISCOUNT_PERCENT","id":"habitual","label":"Cliente habitual","percent":"10",
                  "when":[{"question":"frecuencia","op":"NOT_EQUALS","value":"unica"}]}]""", null);

        @Test
        void preguntaOcultaSeIgnoraAunqueLlegueValor() {
            var result = strict(config, Map.of("tipo", "basica", "ventanas", 10));
            assertTotal(result, "50000");
            assertThat(result.visibleQuestions()).containsExactly("tipo", "frecuencia");
            assertThat(result.answers()).doesNotContainKey("ventanas");
        }

        @Test
        void preguntaVisibleYObligatoria() {
            assertTotal(strict(config, Map.of("tipo", "profunda", "ventanas", 4)), "88000");
            assertThatThrownBy(() -> strict(config, Map.of("tipo", "profunda")))
                    .isInstanceOf(InvalidAnswersException.class)
                    .satisfies(e -> assertThat(((InvalidAnswersException) e).issues())
                            .extracting(PriceBreakdown.AnswerIssue::question).containsExactly("ventanas"));
        }

        @Test
        void condicionSobrePreguntaSinResponderEsFalsa() {
            // NOT_EQUALS sin respuesta NO aplica el descuento.
            assertTotal(strict(config, Map.of("tipo", "basica")), "50000");
            assertTotal(strict(config, Map.of("tipo", "basica", "frecuencia", "semanal")), "45000");
            assertTotal(strict(config, Map.of("tipo", "basica", "frecuencia", "unica")), "50000");
        }
    }

    @Nested
    class ValidacionDeRespuestas {

        private final CalculatorConfig config = config("""
                [{"key":"tipo","type":"SINGLE_CHOICE","label":"Tipo","required":true,
                  "options":[{"key":"a","label":"A"},{"key":"b","label":"B"}]},
                 {"key":"cantidad","type":"QUANTITY","label":"Cantidad","required":true,"min":1,"max":100},
                 {"key":"detalle","type":"TEXT","label":"Detalle","maxLength":20},
                 {"key":"fecha","type":"DATE","label":"Fecha"}]""", """
                [{"type":"OPTION_PRICE","id":"p","question":"tipo","prices":{"a":"100","b":"200"}}]""", null);

        private void assertRejected(Map<String, ?> answers, String question) {
            assertThatThrownBy(() -> strict(config, answers))
                    .isInstanceOf(InvalidAnswersException.class)
                    .satisfies(e -> assertThat(((InvalidAnswersException) e).issues())
                            .extracting(PriceBreakdown.AnswerIssue::question).contains(question));
        }

        @Test
        void rechazaValoresFueraDeRangoOMalFormados() {
            assertRejected(Map.of("tipo", "z", "cantidad", 1), "tipo");
            assertRejected(Map.of("tipo", "a", "cantidad", 0), "cantidad");
            assertRejected(Map.of("tipo", "a", "cantidad", 101), "cantidad");
            assertRejected(Map.of("tipo", "a", "cantidad", "1.5"), "cantidad");
            assertRejected(Map.of("tipo", "a", "cantidad", "abc"), "cantidad");
            assertRejected(Map.of("tipo", "a", "cantidad", 1, "detalle", "x".repeat(21)), "detalle");
            assertRejected(Map.of("tipo", "a", "cantidad", 1, "fecha", "2026-10-06"), "fecha");
            assertRejected(Map.of("tipo", "a", "cantidad", 1, "fecha", "mañana"), "fecha");
            assertRejected(Map.of("tipo", "a", "cantidad", 1, "inventada", "x"), "inventada");
            assertRejected(Map.of("tipo", List.of("a"), "cantidad", 1), "tipo");
        }

        @Test
        void textoYFechaNoModificanElPrecio() {
            var result = strict(config, Map.of("tipo", "b", "cantidad", 3, "detalle", "Piso 3", "fecha", "2026-10-20"));
            assertTotal(result, "200");
            assertThat(result.answered()).extracting(PriceBreakdown.AnsweredQuestion::value)
                    .containsExactly("B", "3", "Piso 3", "20/10/2026");
        }

        @Test
        void vistaPreviaCalculaConLoQueHayYDevuelveLosProblemas() {
            var result = preview(config, Map.of("tipo", "b", "cantidad", 500));
            assertThat(result.complete()).isFalse();
            assertThat(result.issues()).extracting(PriceBreakdown.AnswerIssue::question).containsExactly("cantidad");
            assertTotal(result, "200");
        }
    }

    @Nested
    class Limites {

        @Test
        void importesQueSuperanElMaximoSonRechazados() {
            var config = config("""
                    [{"key":"cantidad","type":"QUANTITY","label":"Cantidad","required":true,"min":1,"max":1000000}]""", """
                    [{"type":"PER_UNIT","id":"u","label":"U","question":"cantidad","unitPrice":"10000000000"}]""", null);
            assertThatThrownBy(() -> strict(config, Map.of("cantidad", 1000000)))
                    .isInstanceOf(InvalidAnswersException.class);
            assertThatThrownBy(() -> preview(config, Map.of("cantidad", 1000000)))
                    .isInstanceOf(InvalidAnswersException.class);
        }

        @Test
        void elResultadoEsDeterministico() {
            var config = config(QTY, """
                    [{"type":"PER_UNIT","id":"u","label":"U","question":"cantidad","unitPrice":"1234.56"},
                     {"type":"SURCHARGE_PERCENT","id":"s","label":"S","percent":"13.7"}]""", null);
            assertThat(strict(config, Map.of("cantidad", 77))).isEqualTo(strict(config, Map.of("cantidad", 77)));
        }
    }
}
