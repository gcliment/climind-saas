package com.climind.saas.administration;

import com.climind.saas.subscriptions.BillingAdminService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/** Métricas globales (lectura agregada; no expone datos de negocios individuales). */
@Service
public class AdminService {

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final JdbcTemplate jdbc;
    private final BillingAdminService billing;
    private final Clock clock;

    public AdminService(JdbcTemplate jdbc, BillingAdminService billing, Clock clock) {
        this.jdbc = jdbc;
        this.billing = billing;
        this.clock = clock;
    }

    /**
     * @param activeTenants  negocios no suspendidos con al menos un cotizador publicado
     * @param freeTenants    negocios no suspendidos sin un plan pago vigente
     * @param quotesThisMonth cotizaciones de todos los negocios en el mes calendario (uso de Climind Cotiza)
     */
    public record PlatformSummary(long totalTenants, long activeTenants, long suspendedTenants, long freeTenants,
                                  long totalUsers, long verifiedUsers, long newTenantsLast30Days,
                                  long quotesThisMonth, long leadsThisMonth, BillingAdminService.RevenueMetrics revenue) {
    }

    @Transactional(readOnly = true)
    public PlatformSummary summary() {
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(30)));
        Timestamp monthStart = Timestamp.from(LocalDate.now(clock.withZone(ZONE)).withDayOfMonth(1)
                .atStartOfDay(ZONE).toInstant());
        BillingAdminService.RevenueMetrics revenue = billing.revenue();
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM tenants WHERE NOT demo AND status <> 'DELETED') AS total_tenants,
                  (SELECT count(*) FROM tenants t WHERE NOT t.demo AND t.status = 'ACTIVE'
                     AND EXISTS (SELECT 1 FROM calculators c WHERE c.tenant_id = t.id AND c.status = 'PUBLICADO')) AS active_tenants,
                  (SELECT count(*) FROM tenants WHERE NOT demo AND status = 'SUSPENDED') AS suspended_tenants,
                  (SELECT count(*) FROM tenants WHERE NOT demo AND status = 'ACTIVE')    AS not_suspended,
                  (SELECT count(*) FROM users WHERE platform_role IS NULL AND deleted_at IS NULL) AS total_users,
                  (SELECT count(*) FROM users WHERE platform_role IS NULL AND deleted_at IS NULL
                     AND email_verified_at IS NOT NULL)                                AS verified_users,
                  (SELECT count(*) FROM tenants WHERE NOT demo AND created_at >= ?)    AS new_tenants,
                  (SELECT count(*) FROM quote_simulations s JOIN tenants t ON t.id = s.tenant_id
                     WHERE NOT t.demo AND s.created_at >= ?)                           AS quotes_month,
                  (SELECT count(*) FROM quote_requests WHERE created_at >= ?)          AS leads_month
                """, (rs, i) -> new PlatformSummary(rs.getLong("total_tenants"), rs.getLong("active_tenants"),
                        rs.getLong("suspended_tenants"),
                        Math.max(rs.getLong("not_suspended") - revenue.paidSubscribers(), 0),
                        rs.getLong("total_users"), rs.getLong("verified_users"), rs.getLong("new_tenants"),
                        rs.getLong("quotes_month"), rs.getLong("leads_month"), revenue),
                since, monthStart, monthStart);
    }
}
