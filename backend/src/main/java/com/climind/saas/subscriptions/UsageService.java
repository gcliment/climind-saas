package com.climind.saas.subscriptions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Cupos del plan, verificados SIEMPRE en el backend.
 * <ul>
 *   <li>Cotizaciones por período: incremento atómico condicionado en PostgreSQL
 *       ({@code INSERT … ON CONFLICT DO UPDATE … WHERE count < límite}). Dos pedidos simultáneos nunca superan el
 *       límite, y como corre en la transacción que guarda la cotización, si esta falla el consumo se revierte.</li>
 *   <li>Cotizadores publicados: se cuentan al publicar o reactivar.</li>
 * </ul>
 * Solo consume cupo una cotización NUEVA y válida: los recálculos deduplicados y los intentos fallidos no.
 */
@Service
public class UsageService {

    public static final String QUOTES = "QUOTES";

    private final EntitlementService entitlements;
    private final BillingNotifier notifier;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public UsageService(EntitlementService entitlements, BillingNotifier notifier, JdbcTemplate jdbc, Clock clock) {
        this.entitlements = entitlements;
        this.notifier = notifier;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Usage(int used, int limit, java.time.LocalDate periodStart, java.time.LocalDate periodEnd) {

        public boolean exhausted() {
            return used >= limit;
        }
    }

    /**
     * Consume una cotización del período. Debe llamarse dentro de la transacción que guarda la cotización.
     *
     * @return false si el cupo del período está agotado (no se consume nada)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryConsumeQuote(UUID tenantId) {
        Entitlements e = entitlements.forTenant(tenantId);
        int limit = e.maxMonthlyQuotes();
        List<Integer> updated = jdbc.query("""
                        INSERT INTO usage_counters (tenant_id, metric, period_start, count, updated_at)
                        VALUES (?, ?, ?, 1, ?)
                        ON CONFLICT (tenant_id, metric, period_start)
                        DO UPDATE SET count = usage_counters.count + 1, updated_at = EXCLUDED.updated_at
                        WHERE usage_counters.count < ?
                        RETURNING count
                        """, (rs, i) -> rs.getInt(1), tenantId, QUOTES, Date.valueOf(e.periodStart()),
                Timestamp.from(clock.instant()), limit);
        if (updated.isEmpty()) {
            return false;
        }
        int count = updated.getFirst();
        // Avisos al 80 % y al 100 % (una sola vez por período: el UPDATE condicionado lo garantiza).
        if (count >= Math.ceil(limit * 0.8) && count < limit && markWarned(tenantId, e, "warned_80")) {
            notifier.usage(tenantId, count, limit, false);
        }
        if (count >= limit && markWarned(tenantId, e, "warned_100")) {
            notifier.usage(tenantId, count, limit, true);
        }
        return true;
    }

    private boolean markWarned(UUID tenantId, Entitlements e, String column) {
        return jdbc.update("UPDATE usage_counters SET " + column + " = true WHERE tenant_id = ? AND metric = ? "
                + "AND period_start = ? AND " + column + " = false", tenantId, QUOTES, Date.valueOf(e.periodStart())) == 1;
    }

    @Transactional(readOnly = true)
    public Usage quoteUsage(UUID tenantId) {
        return quoteUsage(tenantId, entitlements.forTenant(tenantId));
    }

    @Transactional(readOnly = true)
    public Usage quoteUsage(UUID tenantId, Entitlements e) {
        int used = jdbc.query("SELECT count FROM usage_counters WHERE tenant_id = ? AND metric = ? AND period_start = ?",
                (rs, i) -> rs.getInt(1), tenantId, QUOTES, Date.valueOf(e.periodStart())).stream().findFirst().orElse(0);
        return new Usage(used, e.maxMonthlyQuotes(), e.periodStart(), e.periodEnd());
    }

    @Transactional(readOnly = true)
    public long publishedCalculators(UUID tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM calculators WHERE tenant_id = ? AND status = 'PUBLICADO'",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }
}
