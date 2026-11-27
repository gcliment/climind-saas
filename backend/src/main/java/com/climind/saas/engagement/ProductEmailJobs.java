package com.climind.saas.engagement;

import com.climind.saas.auth.AccountSettingsService;
import com.climind.saas.auth.AuthService;
import com.climind.saas.notifications.EmailService;
import com.climind.saas.notifications.MailTemplate;
import com.climind.saas.shared.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Emails de producto (comerciales): ayudan a que el negocio saque provecho de la herramienta.
 * <ul>
 *   <li>Solo a dueños que los aceptan ({@code users.product_emails}); todos llevan enlace de baja en un clic.
 *       Nunca a negocios suspendidos, de demostración o con una baja en curso.</li>
 *   <li>El resumen mensual exige el email confirmado. El recordatorio de activación también llega a quien no lo
 *       confirmó (la cuenta se usa sin confirmar) y en ese caso trae el enlace para confirmarlo.</li>
 *   <li>Cada envío se registra en {@code product_email_sends} ANTES de mandarlo (clave única por usuario,
 *       campaña y período): aunque el job corra dos veces, nadie recibe el mismo email dos veces.</li>
 * </ul>
 */
@Slf4j
@Service
public class ProductEmailJobs {

    static final String ACTIVATION = "ACTIVATION";
    static final String MONTHLY_SUMMARY = "MONTHLY_SUMMARY";
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    /** El resumen del mes anterior se manda en los primeros días (por si la app no estaba corriendo el día 1). */
    private static final int SUMMARY_LAST_DAY = 3;

    private static final String ELIGIBLE_OWNER = """
            u.status = 'ACTIVE' AND u.product_emails AND u.deleted_at IS NULL
              AND t.status = 'ACTIVE' AND NOT t.demo AND t.deletion_requested_at IS NULL
            """;

    private final JdbcTemplate jdbc;
    private final EmailService emails;
    private final AccountSettingsService accounts;
    private final AuthService auth;
    private final AppProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ProductEmailJobs(JdbcTemplate jdbc, EmailService emails, AccountSettingsService accounts, AuthService auth,
                            AppProperties properties, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.emails = emails;
        this.accounts = accounts;
        this.auth = auth;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    private record Recipient(UUID userId, UUID tenantId, String email, String firstName, String businessName,
                             long quotes, long leads, long pending, boolean emailVerified) {
    }

    /**
     * Recordatorio único para quien todavía no publicó un cotizador, entre 2 y 14 días después de confirmar el
     * email o, si no lo confirmó, de registrarse. A quien no confirmó le llega con el enlace para confirmarlo.
     * La fecha de registro es {@code terms_accepted_at} (se graba con el {@link Clock} de la app al registrarse;
     * {@code created_at} la pone la base con su propio reloj).
     *
     * @return emails enviados
     */
    @Scheduled(cron = "${app.engagement.activation-cron:0 0 10 * * *}", zone = "America/Argentina/Buenos_Aires")
    public int sendActivationReminders() {
        Instant now = clock.instant();
        List<Recipient> recipients = jdbc.query("""
                        SELECT u.id, t.id AS tenant_id, u.email, u.first_name, coalesce(p.business_name, t.name) AS business,
                               u.email_verified_at IS NOT NULL AS verified
                        FROM users u
                        JOIN tenant_memberships m ON m.user_id = u.id AND m.role = 'OWNER'
                        JOIN tenants t ON t.id = m.tenant_id
                        LEFT JOIN business_profiles p ON p.tenant_id = t.id
                        """ + " WHERE " + ELIGIBLE_OWNER + """
                          AND coalesce(u.email_verified_at, u.terms_accepted_at) <= ?
                          AND coalesce(u.email_verified_at, u.terms_accepted_at) > ?
                          AND NOT EXISTS (SELECT 1 FROM calculators c WHERE c.tenant_id = t.id AND c.published_at IS NOT NULL)
                          AND NOT EXISTS (SELECT 1 FROM product_email_sends s WHERE s.user_id = u.id AND s.campaign = ?)
                        """,
                (rs, i) -> new Recipient(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("email"), rs.getString("first_name"), rs.getString("business"), 0, 0, 0,
                        rs.getBoolean("verified")),
                Timestamp.from(now.minus(Duration.ofDays(2))), Timestamp.from(now.minus(Duration.ofDays(14))),
                ACTIVATION);
        String appUrl = properties.publicUrlWithoutTrailingSlash();
        int sent = 0;
        for (Recipient r : recipients) {
            Map<String, Object> vars = Map.of(
                    "link", appUrl + "/app/cotizadores",
                    "demosLink", appUrl + "/#demos",
                    "helpLink", appUrl + "/ayuda/primeros-pasos");
            // El enlace de confirmación se genera dentro de la transacción del envío: solo si el email sale.
            Supplier<Map<String, Object>> verify = r.emailVerified() ? Map::of
                    : () -> auth.newVerificationLink(r.userId())
                            .<Map<String, Object>>map(link -> Map.of("verifyLink", link)).orElse(Map.of());
            if (send(r, ACTIVATION, "once", MailTemplate.ACTIVATION_REMINDER, vars, verify)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Recordatorios de activación enviados: {}", sent);
        }
        return sent;
    }

    /**
     * Resumen del mes anterior para los negocios que ya publicaron algún cotizador.
     *
     * @return emails enviados
     */
    @Scheduled(cron = "${app.engagement.summary-cron:0 0 9 * * *}", zone = "America/Argentina/Buenos_Aires")
    public int sendMonthlySummaries() {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        if (today.getDayOfMonth() > SUMMARY_LAST_DAY) {
            return 0;
        }
        YearMonth month = YearMonth.from(today).minusMonths(1);
        Timestamp from = Timestamp.from(month.atDay(1).atStartOfDay(ZONE).toInstant());
        Timestamp to = Timestamp.from(month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant());
        String period = month.toString();
        List<Recipient> recipients = jdbc.query("""
                        SELECT u.id, t.id AS tenant_id, u.email, u.first_name, coalesce(p.business_name, t.name) AS business,
                          (SELECT count(*) FROM quote_simulations s WHERE s.tenant_id = t.id
                             AND s.created_at >= ? AND s.created_at < ?) AS quotes,
                          (SELECT count(*) FROM quote_requests r WHERE r.tenant_id = t.id
                             AND r.created_at >= ? AND r.created_at < ?) AS leads,
                          (SELECT count(*) FROM quote_requests r WHERE r.tenant_id = t.id AND r.status = 'NUEVA') AS pending
                        FROM users u
                        JOIN tenant_memberships m ON m.user_id = u.id AND m.role = 'OWNER'
                        JOIN tenants t ON t.id = m.tenant_id
                        LEFT JOIN business_profiles p ON p.tenant_id = t.id
                        """ + " WHERE " + ELIGIBLE_OWNER + """
                          AND u.email_verified_at IS NOT NULL
                          AND EXISTS (SELECT 1 FROM calculators c WHERE c.tenant_id = t.id AND c.published_at IS NOT NULL
                                        AND c.published_at < ?)
                          AND NOT EXISTS (SELECT 1 FROM product_email_sends s WHERE s.user_id = u.id
                                            AND s.campaign = ? AND s.period_key = ?)
                        """,
                (rs, i) -> new Recipient(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("email"), rs.getString("first_name"), rs.getString("business"),
                        rs.getLong("quotes"), rs.getLong("leads"), rs.getLong("pending"), true),
                from, to, from, to, to, MONTHLY_SUMMARY, period);
        String monthName = month.getMonth().getDisplayName(TextStyle.FULL, ES_AR);
        String appUrl = properties.publicUrlWithoutTrailingSlash();
        int sent = 0;
        for (Recipient r : recipients) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", "Tu resumen de " + monthName);
            vars.put("businessName", r.businessName());
            vars.put("monthName", monthName + " de " + month.getYear());
            vars.put("quotes", r.quotes());
            vars.put("leads", r.leads());
            vars.put("pending", r.pending());
            vars.put("link", appUrl + "/app/estadisticas");
            if (send(r, MONTHLY_SUMMARY, period, MailTemplate.MONTHLY_SUMMARY, vars, Map::of)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Resúmenes mensuales de {} enviados: {}", period, sent);
        }
        return sent;
    }

    /**
     * Registra el envío (una sola vez por usuario, campaña y período) y encola el email al confirmar.
     * {@code extra} se evalúa solo si el envío se registró (por ejemplo, para generar un enlace de un solo uso).
     */
    private boolean send(Recipient r, String campaign, String period, MailTemplate template, Map<String, Object> vars,
                         Supplier<Map<String, Object>> extra) {
        Boolean sent = transactions.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO product_email_sends (user_id, campaign, period_key, sent_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, r.userId(), campaign, period, Timestamp.from(clock.instant()));
            if (inserted == 0) {
                return false;
            }
            Map<String, Object> all = new HashMap<>(vars);
            all.putAll(extra.get());
            all.put("firstName", r.firstName());
            all.put("unsubscribeToken", accounts.unsubscribeToken(r.userId()));
            emails.send(template, r.email(), all, r.userId(), r.tenantId());
            return true;
        });
        return Boolean.TRUE.equals(sent);
    }
}
