package com.climind.saas;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

/**
 * Arranque local SIN PostgreSQL instalado ni Docker: levanta un PostgreSQL 17 embebido y la app con perfil dev.
 *
 * <pre>
 * cd backend
 * ./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.climind.saas.LocalDevApplication
 * </pre>
 *
 * Los datos se pierden al cerrar. Para datos persistentes usá docker compose (ver README).
 * Emails: no se envían; se consultan en http://localhost:8080/api/dev/emails.
 */
public class LocalDevApplication {

    public static void main(String[] args) throws Exception {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(54329).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (Exception ignored) {
                // cierre best-effort
            }
        }));
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        System.setProperty("spring.profiles.active", "dev");
        SpringApplication.from(ClimindSaasApplication::main).run(args);
    }
}
