package com.climind.saas.shared.security;

import com.climind.saas.shared.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSecurityPolicyTest {

    @Test
    void sinPixelLaCspNoPermiteDominiosExternos() {
        assertThat(SecurityConfig.contentSecurityPolicy(false))
                .contains("script-src 'self';", "connect-src 'self';", "img-src 'self' data: blob:;")
                .doesNotContain("facebook", "unsafe-inline", "unsafe-eval");
    }

    @Test
    void conPixelSoloAgregaLosDominiosDeMetaYSigueSinInline() {
        assertThat(SecurityConfig.contentSecurityPolicy(true))
                .contains("script-src 'self' https://connect.facebook.net;",
                        "img-src 'self' data: blob: https://www.facebook.com;",
                        "connect-src 'self' https://www.facebook.com https://connect.facebook.net;",
                        "frame-ancestors 'none'")
                .doesNotContain("unsafe-inline", "unsafe-eval");
    }

    @Test
    void elIdDelPixelSoloSeAceptaSiSonDigitos() {
        AppProperties properties = new AppProperties();
        assertThat(properties.metaPixelIdOrEmpty()).isEmpty();
        properties.setMetaPixelId(" 2159951434614928 ");
        assertThat(properties.metaPixelIdOrEmpty()).isEqualTo("2159951434614928");
        properties.setMetaPixelId("123\");alert(1);//");
        assertThat(properties.metaPixelIdOrEmpty()).isEmpty();
    }
}
