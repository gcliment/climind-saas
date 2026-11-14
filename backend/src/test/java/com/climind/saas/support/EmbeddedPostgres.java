package com.climind.saas.support;

import java.io.IOException;

/**
 * PostgreSQL real (binarios oficiales empaquetados por zonky, versión 17) compartido por toda la JVM de tests.
 * Se usa en lugar de Testcontainers para no depender de un daemon de Docker (ver docs/TESTING.md).
 */
public final class EmbeddedPostgres {

    private static io.zonky.test.db.postgres.embedded.EmbeddedPostgres instance;

    private EmbeddedPostgres() {
    }

    public static synchronized String jdbcUrl() {
        if (instance == null) {
            try {
                instance = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo iniciar PostgreSQL embebido", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    instance.close();
                } catch (IOException ignored) {
                    // cierre best-effort
                }
            }));
        }
        return instance.getJdbcUrl("postgres", "postgres");
    }
}
