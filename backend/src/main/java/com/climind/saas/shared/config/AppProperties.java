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
    /** Email de soporte que se muestra en la ayuda y la landing. Vacío = no se muestra. */
    private String supportEmail = "";
    /**
     * WhatsApp comercial (solo dígitos, con código de país: 5492211234567) para los botones «Escribinos» de la
     * landing y el portal (cotizador a medida). Vacío = esos botones escriben al email de soporte.
     */
    private String supportWhatsapp = "";
    /**
     * ID del píxel (dataset) de Meta para medir visitas y registros que llegan desde los anuncios. Solo se
     * carga en la landing, precios y registro; nunca en el panel ni en los cotizadores públicos de los
     * negocios. Vacío = sin píxel y CSP estricta sin dominios externos.
     */
    private String metaPixelId = "";

    /** Píxel de Meta configurado (solo dígitos; cualquier otro valor se ignora). */
    public String metaPixelIdOrEmpty() {
        String id = metaPixelId == null ? "" : metaPixelId.strip();
        return id.matches("\\d{5,20}") ? id : "";
    }

    private Security security = new Security();
    private Storage storage = new Storage();
    private RateLimit rateLimit = new RateLimit();
    private Mail mail = new Mail();
    private AdminBootstrap adminBootstrap = new AdminBootstrap();
    private Payments payments = new Payments();
    private Demo demo = new Demo();
    private Account account = new Account();
    private Assistant assistant = new Assistant();

    public String publicUrlWithoutTrailingSlash() {
        return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /** Asistente de ayuda del panel (chat). Sin clave de API responde con las preguntas frecuentes. */
    @Getter
    @Setter
    public static class Assistant {
        /** Clave de la API de OpenAI (OPENAI_API_KEY). Vacía = asistente sin IA. */
        private String apiKey = "";
        /** Modelo de OpenAI (el mismo que usa Control de Gastos). */
        private String model = "gpt-4.1-mini";
        private String baseUrl = "https://api.openai.com/v1";
        /** Tiempo máximo de espera de una respuesta. */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(30);
        /** Preguntas por usuario y por hora. */
        private int messagesPerHourPerUser = 30;
        /** Preguntas por usuario y por día. */
        private int messagesPerDayPerUser = 100;

        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
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
        /**
         * Solo para probar con credenciales de prueba: Mercado Pago exige que el pagador también sea una cuenta
         * de prueba. Si se define, todas las suscripciones se crean con este email de pagador (el del comprador de
         * prueba). Vacío en producción.
         */
        private String testPayerEmail = "";
    }

    @Getter
    @Setter
    public static class Demo {
        /** Crea (si falta) el negocio ficticio de las demos públicas al arrancar. */
        private boolean enabled = true;
        /** Dirección pública del negocio de demostración: /c/{slug}/... */
        private String slug = "demo";
        /** Las cotizaciones de las demos se borran pasado este plazo (son datos ficticios). */
        private Duration retention = Duration.ofHours(48);
    }

    @Getter
    @Setter
    public static class Account {
        /** Plazo para arrepentirse de una baja de cuenta antes de eliminar los datos. */
        private Duration deletionGrace = Duration.ofDays(30);
    }
}
