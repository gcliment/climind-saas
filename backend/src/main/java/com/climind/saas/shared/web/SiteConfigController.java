package com.climind.saas.shared.web;

import com.climind.saas.shared.config.AppProperties;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuración pública del frontend como script ({@code <script src="/site-config.js">}): URLs que dependen
 * del entorno. Va como archivo y no inline para respetar la CSP ({@code script-src 'self'}).
 */
@Hidden
@RestController
public class SiteConfigController {

    private final String body;

    public SiteConfigController(AppProperties properties, JsonMapper jsonMapper) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("appUrl", properties.publicUrlWithoutTrailingSlash());
        config.put("portalUrl", properties.getPortalUrl());
        config.put("env", properties.getEnv());
        config.put("supportEmail", properties.getSupportEmail() == null ? "" : properties.getSupportEmail().strip());
        this.body = "window.CLIMIND_CONFIG = Object.freeze(" + jsonMapper.writeValueAsString(config) + ");\n";
    }

    @GetMapping(value = "/site-config.js", produces = "application/javascript;charset=UTF-8")
    public ResponseEntity<String> siteConfig() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
    }
}
