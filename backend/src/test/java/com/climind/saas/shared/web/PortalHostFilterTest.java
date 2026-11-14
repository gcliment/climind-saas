package com.climind.saas.shared.web;

import com.climind.saas.shared.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PortalHostFilterTest {

    private static PortalHostFilter filter(String host) {
        AppProperties properties = new AppProperties();
        properties.setPortalHost(host);
        return new PortalHostFilter(properties);
    }

    private static MockHttpServletResponse run(PortalHostFilter filter, String host, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServerName(host);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void elHostDelPortalMuestraElPortalEnLaRaiz() throws Exception {
        assertThat(run(filter("saas.climind-ia.com"), "saas.climind-ia.com", "/").getForwardedUrl())
                .isEqualTo("/saas/index.html");
    }

    @Test
    void otrosHostsYRutasNoSeTocan() throws Exception {
        PortalHostFilter filter = filter("saas.climind-ia.com");
        assertThat(run(filter, "cotiza.climind-ia.com", "/").getForwardedUrl()).isNull();
        assertThat(run(filter, "saas.climind-ia.com", "/ingresar").getForwardedUrl()).isNull();
        assertThat(run(filter(""), "saas.climind-ia.com", "/").getForwardedUrl()).isNull();
    }
}
