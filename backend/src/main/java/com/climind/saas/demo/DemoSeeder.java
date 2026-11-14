package com.climind.saas.demo;

import com.climind.saas.shared.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Crea las demos públicas al arrancar si faltan ({@code APP_DEMO_ENABLED}, activado por defecto). */
@Slf4j
@Component
public class DemoSeeder implements ApplicationRunner {

    private final AppProperties properties;
    private final DemoService demos;

    public DemoSeeder(AppProperties properties, DemoService demos) {
        this.properties = properties;
        this.demos = demos;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getDemo().isEnabled()) {
            return;
        }
        try {
            int created = demos.seed();
            if (created > 0) {
                log.info("Demos públicas: se crearon {} cotizadores de ejemplo en /c/{}/", created,
                        properties.getDemo().getSlug());
            }
        } catch (RuntimeException e) {
            // Las demos no son críticas: un problema acá no debe impedir que la app arranque.
            log.warn("No se pudieron crear las demos públicas", e);
        }
    }
}
