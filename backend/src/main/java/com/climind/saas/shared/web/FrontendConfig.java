package com.climind.saas.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Rutas "lindas" del frontend estático (sin .html). El frontend se sirve desde
 * {@code spring.web.resources.static-locations}: primero {@code frontend/cotiza/} (la app vive en "/")
 * y luego {@code frontend/} (portal en /saas/, recursos compartidos en /shared/ y /vendor/).
 */
@Configuration
public class FrontendConfig implements WebMvcConfigurer {

    /** Ruta pública → archivo HTML. */
    static final String[][] PAGES = {
            {"/ingresar", "/ingresar.html"},
            {"/registro", "/registro.html"},
            {"/verificar", "/verificar.html"},
            {"/recuperar", "/recuperar.html"},
            {"/restablecer", "/restablecer.html"},
            {"/app/", "/app/index.html"},
            {"/app/negocio", "/app/negocio.html"},
            {"/app/cotizadores", "/app/cotizadores.html"},
            {"/app/editor", "/app/editor.html"},
            {"/app/onboarding", "/app/onboarding.html"},
            {"/app/consultas", "/app/consultas.html"},
            {"/app/estadisticas", "/app/estadisticas.html"},
            {"/app/plan", "/app/plan.html"},
            // Simulador del checkout (la página existe siempre, pero su API /api/dev solo existe en dev).
            {"/app/plan/simulador", "/app/plan-simulador.html"},
            // Cotización emitida (el token viaja en el fragmento #, que no llega al servidor).
            {"/cotizacion/{code}", "/cotizacion.html"},
            {"/app/admin", "/app/admin.html"},
            {"/legal/terminos", "/legal/terminos.html"},
            {"/legal/privacidad", "/legal/privacidad.html"},
            {"/saas/", "/saas/index.html"},
    };

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String[] page : PAGES) {
            registry.addViewController(page[0]).setViewName("forward:" + page[1]);
        }
        registry.addRedirectViewController("/app", "/app/");
        registry.addRedirectViewController("/saas", "/saas/");
    }
}
