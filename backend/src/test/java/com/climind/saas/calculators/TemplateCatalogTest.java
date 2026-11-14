package com.climind.saas.calculators;

import com.climind.saas.pricing.PriceBreakdown;
import com.climind.saas.pricing.PricingEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Las plantillas reales cargan, son publicables y cotizan lo esperado (cuentas hechas a mano). */
class TemplateCatalogTest {

    private static TemplateCatalog catalog;

    @BeforeAll
    static void load() throws Exception {
        catalog = new TemplateCatalog(new ConfigJson());
    }

    private static PriceBreakdown quote(String code, Map<String, ?> answers) {
        var template = catalog.find(code).orElseThrow();
        return PricingEngine.calculate(template.config(), answers, PricingEngine.Mode.STRICT, LocalDate.of(2026, 10, 7));
    }

    @Test
    void cargaLasTresPlantillasEnOrden() {
        assertThat(catalog.all()).extracting(TemplateCatalog.Template::code)
                .containsExactly("limpieza", "catering", "instalaciones");
        assertThat(catalog.all()).allSatisfy(t -> assertThat(t.description()).contains("ejemplos"));
    }

    @Test
    void limpieza() {
        // 60 m² × $1.500 + 3 ambientes × $3.000 + vidrios $12.000 = $111.000; −10% quincenal = $99.900
        var result = quote("limpieza", Map.of("tipo", "profunda", "m2", 60, "ambientes", 3,
                "frecuencia", "quincenal", "extras", List.of("vidrios")));
        assertThat(result.subtotal()).isEqualByComparingTo("111000");
        assertThat(result.total()).isEqualByComparingTo("99900");
    }

    @Test
    void limpiezaChicaLlegaAlMinimo() {
        var result = quote("limpieza", Map.of("tipo", "basica", "m2", 10, "ambientes", 1, "frecuencia", "unica"));
        assertThat(result.total()).isEqualByComparingTo("35000");
        assertThat(result.minimumAdjustment()).isEqualByComparingTo("23000"); // 10 m² × $900 + 1 × $3.000 = $12.000
    }

    @Test
    void catering() {
        // 40 × $12.000 + 40 × $4.000 + mozos $60.000 + torta $45.000 = $745.000; +10% (más de 6 h) = $819.500
        var result = quote("catering", Map.of("invitados", 40, "menu", "clasico", "bebidas", true, "mozos", true,
                "duracion", 7, "extras", List.of("torta")));
        assertThat(result.subtotal()).isEqualByComparingTo("745000");
        assertThat(result.total()).isEqualByComparingTo("819500");
    }

    @Test
    void instalaciones() {
        // 3 × $85.000 + 3 × $25.000 + retiro $15.000 = $345.000; −10% por cantidad = $310.500
        var result = quote("instalaciones", Map.of("tipo", "aire_split", "cantidad", 3, "materiales", true,
                "urgente", false, "extras", List.of("retiro")));
        assertThat(result.total()).isEqualByComparingTo("310500");
    }
}
