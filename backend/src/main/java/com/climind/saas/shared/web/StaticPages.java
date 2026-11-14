package com.climind.saas.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Lee páginas HTML del frontend desde las ubicaciones estáticas configuradas (en desarrollo, directo de
 * {@code frontend/}; en producción, del classpath). Se lee en cada request: son archivos chicos y así los
 * cambios se ven al recargar en desarrollo.
 */
@Component
public class StaticPages {

    private final ResourceLoader loader;
    private final String[] locations;

    public StaticPages(ResourceLoader loader,
                       @Value("${spring.web.resources.static-locations}") String[] locations) {
        this.loader = loader;
        this.locations = locations;
    }

    /** @param path ruta relativa a la raíz estática, sin barra inicial (p. ej. {@code "c.html"}) */
    public Optional<String> read(String path) {
        for (String location : locations) {
            String base = location.trim().endsWith("/") ? location.trim() : location.trim() + "/";
            Resource resource = loader.getResource(base + path);
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // se intenta la siguiente ubicación
                }
            }
        }
        return Optional.empty();
    }

    /** Escapa texto para insertarlo dentro de HTML (contenido o atributo entre comillas dobles). */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
