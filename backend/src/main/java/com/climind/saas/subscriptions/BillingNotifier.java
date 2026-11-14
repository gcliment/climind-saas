package com.climind.saas.subscriptions;

import com.climind.saas.notifications.EmailService;
import com.climind.saas.notifications.MailTemplate;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.util.ArMoney;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Emails de facturación al dueño del negocio. Los avisos de uso respetan la preferencia
 * {@code notify_usage_email}; los cambios de suscripción son transaccionales y se envían siempre.
 */
@Component
class BillingNotifier {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final EmailService email;
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final java.time.Clock clock;

    BillingNotifier(EmailService email, JdbcTemplate jdbc, AppProperties properties, java.time.Clock clock) {
        this.email = email;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    void usage(UUID tenantId, int used, int limit, boolean reached) {
        boolean enabled = jdbc.query("SELECT notify_usage_email FROM business_settings WHERE tenant_id = ?",
                (rs, i) -> rs.getBoolean(1), tenantId).stream().findFirst().orElse(true);
        if (!enabled) {
            return;
        }
        List<String> lines = reached
                ? List.of("Tus clientes ya usaron las " + limit + " cotizaciones de este período.",
                "Mientras tanto, tus cotizadores muestran tus datos de contacto en lugar del formulario. "
                        + "Podés pasarte a un plan con más capacidad y se habilitan al instante.")
                : List.of("Tus clientes ya usaron " + used + " de las " + limit + " cotizaciones de este período.",
                "Si esperás más consultas, podés pasarte a un plan con más capacidad.");
        send(tenantId, reached ? MailTemplate.USAGE_LIMIT_REACHED : MailTemplate.USAGE_WARNING,
                reached ? "Alcanzaste el límite de tu plan" : "Estás cerca del límite de tu plan", lines, "Ver planes");
    }

    void activated(TenantSubscription s) {
        send(s.getTenantId(), MailTemplate.SUBSCRIPTION_ACTIVATED, "Tu plan " + s.getPlanName() + " está activo",
                List.of("Recibimos el pago de " + ArMoney.format(s.getPrice(), 0) + " y ya tenés los beneficios del plan "
                        + s.getPlanName() + ".", "La próxima renovación es el " + date(s.getCurrentPeriodEnd()) + "."),
                "Ver mi plan");
    }

    void paymentFailed(TenantSubscription s) {
        send(s.getTenantId(), MailTemplate.PAYMENT_FAILED, "No pudimos cobrar tu plan",
                List.of("Mercado Pago rechazó el cobro del plan " + s.getPlanName() + " y lo va a reintentar.",
                        "Conservás los beneficios hasta el " + date(s.getGraceUntil())
                                + ". Si el pago no se acredita, tu cuenta pasa al plan Gratis: no se borra nada.",
                        "Revisá el medio de pago en tu cuenta de Mercado Pago."), "Ver mi plan");
    }

    void canceled(TenantSubscription s) {
        send(s.getTenantId(), MailTemplate.SUBSCRIPTION_CANCELED, "Cancelaste tu plan " + s.getPlanName(),
                List.of(s.getCurrentPeriodEnd() != null && s.getCurrentPeriodEnd().isAfter(clock.instant())
                                ? "Conservás los beneficios hasta el " + date(s.getCurrentPeriodEnd()) + ". Después tu cuenta pasa al plan Gratis."
                                : "Tu cuenta pasó al plan Gratis.",
                        "No se borra nada: tus cotizadores, consultas y estadísticas siguen ahí."), "Ver mi plan");
    }

    void expired(TenantSubscription s) {
        send(s.getTenantId(), MailTemplate.SUBSCRIPTION_EXPIRED, "Tu plan " + s.getPlanName() + " venció",
                List.of("No pudimos cobrar el plan y terminó el período de gracia. Tu cuenta pasó al plan Gratis.",
                        "No se borró nada. Si tenés más cotizadores publicados que los que permite el plan Gratis, "
                                + "los que exceden quedan no disponibles hasta que elijas cuáles mantener o vuelvas a un plan pago."),
                "Elegir un plan");
    }

    private void send(UUID tenantId, MailTemplate template, String title, List<String> lines, String linkText) {
        ownerEmail(tenantId).ifPresent(to -> email.send(template, to, Map.of(
                "title", title, "lines", lines, "linkText", linkText,
                "link", properties.publicUrlWithoutTrailingSlash() + "/app/plan"), null, tenantId));
    }

    private Optional<String> ownerEmail(UUID tenantId) {
        return jdbc.query("""
                        SELECT u.email FROM tenant_memberships m JOIN users u ON u.id = m.user_id
                        WHERE m.tenant_id = ? AND m.role = 'OWNER' ORDER BY m.created_at LIMIT 1
                        """, (rs, i) -> rs.getString(1), tenantId).stream().findFirst();
    }

    private static String date(Instant instant) {
        return instant == null ? "" : DATE.format(instant.atZone(ZoneId.of("America/Argentina/Buenos_Aires")));
    }
}
