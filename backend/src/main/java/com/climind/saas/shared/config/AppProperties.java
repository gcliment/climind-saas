package com.climind.saas.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuración propia de la aplicación (prefijo {@code app.*}). Ver docs/ENVIRONMENT.md. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** dev | test | prod. Solo informativo; el comportamiento depende de los perfiles de Spring. */
    private String env = "dev";
    /** URL pública de Climind Cotiza (links de emails, enlaces públicos). Sin barra final. */
    private String publicUrl = "http://localhost:8080";
    /** URL pública del portal Climind SaaS. */
    private String portalUrl = "http://localhost:8080/saas/";
    /** Host que debe mostrar el portal en "/" (p. ej. saas.climind-ia.com). Vacío = deshabilitado. */
    private String portalHost = "";

    private Security security = new Security();
    private Storage storage = new Storage();
    private RateLimit rateLimit = new RateLimit();
    private Mail mail = new Mail();
    private AdminBootstrap adminBootstrap = new AdminBootstrap();
    private Payments payments = new Payments();

    public String publicUrlWithoutTrailingSlash() {
        return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    @Getter
    @Setter
    public static class Security {
        /** Header con la IP real del cliente puesto por un proxy de confianza (p. ej. CF-Connecting-IP). */
        private String clientIpHeader = "";
        private int maxJsonBodyBytes = 65_536;
        private int loginMaxFailures = 5;
        private Duration loginLockDuration = Duration.ofMinutes(15);
        private Duration verificationTokenTtl = Duration.ofHours(24);
        private Duration resetTokenTtl = Duration.ofHours(1);
    }

    @Getter
    @Setter
    public static class Storage {
        /** Carpeta del volumen persistente de archivos (logos). En Docker: /app/storage. */
        private String dir = "./storage";
        /** Tamaño máximo de un logo. */
        private int maxLogoBytes = 2 * 1024 * 1024;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private int authPerMinutePerIp = 20;
        private int apiPerMinutePerIp = 300;
        private int emailActionsPerHour = 5;
        /** Cotizaciones públicas calculadas por IP y por minuto. */
        private int publicQuotesPerMinutePerIp = 30;
        /** Consultas (datos de contacto) enviadas por IP y por hora. */
        private int publicLeadsPerHourPerIp = 10;
    }

    @Getter
    @Setter
    public static class Mail {
        /** log (solo desarrollo/tests) | smtp. */
        private String provider = "log";
        private String from = "Climind Cotiza <no-reply@climind-ia.com>";
        /** Envío en segundo plano. En tests se desactiva para poder verificar los emails al instante. */
        private boolean async = true;
    }

    @Getter
    @Setter
    public static class AdminBootstrap {
        private String email = "";
        private String password = "";
    }

    @Getter
    @Setter
    public static class Payments {
        /** mercadopago | fake. El simulador (fake) solo existe en los perfiles dev y test: en prod la app no arranca. */
        private String provider = "mercadopago";
        /** Días de gracia con el plan pago después de un cobro rechazado (estado PAST_DUE). */
        private int graceDays = 7;
        /** Un checkout que nunca se completa se descarta pasado este plazo. */
        private Duration pendingCheckoutTtl = Duration.ofHours(48);
        private MercadoPago mercadopago = new MercadoPago();
    }

    @Getter
    @Setter
    public static class MercadoPago {
        /** Access token de la aplicación (solo backend; nunca se expone). Vacío = pagos no configurados. */
        private String accessToken = "";
        /** Clave secreta de Webhooks (Tus integraciones → Webhooks) para validar x-signature. */
        private String webhookSecret = "";
        private String apiUrl = "https://api.mercadopago.com";
        /** Tolerancia de antigüedad de una notificación firmada. */
        private Duration webhookTolerance = Duration.ofMinutes(15);
    }
}
