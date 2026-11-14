package com.climind.saas.businesses;

import com.climind.saas.shared.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessNormalizationTest {

    @Test
    void whatsappQuedaEnFormatoWaMe() {
        assertThat(BusinessService.normalizeWhatsapp("+54 9 11 2345-6789")).isEqualTo("5491123456789");
        assertThat(BusinessService.normalizeWhatsapp("(54) 9 351 555 1234")).isEqualTo("5493515551234");
        assertThat(BusinessService.normalizeWhatsapp("  ")).isNull();
        assertThatThrownBy(() -> BusinessService.normalizeWhatsapp("11 2345"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void sitioWebAgregaHttpsYValidaElHost() {
        assertThat(BusinessService.normalizeWebsite("minegocio.com.ar")).isEqualTo("https://minegocio.com.ar");
        assertThat(BusinessService.normalizeWebsite("http://minegocio.com")).isEqualTo("http://minegocio.com");
        assertThat(BusinessService.normalizeWebsite("")).isNull();
        assertThatThrownBy(() -> BusinessService.normalizeWebsite("javascript:alert(1)"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BusinessService.normalizeWebsite("no es una url"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rubroDebeSerDeLaLista() {
        assertThat(BusinessService.parseIndustry("CATERING_EVENTOS")).isEqualTo(Industry.CATERING_EVENTOS);
        assertThat(BusinessService.parseIndustry(null)).isNull();
        assertThatThrownBy(() -> BusinessService.parseIndustry("CUALQUIERA")).isInstanceOf(ApiException.class);
    }
}
