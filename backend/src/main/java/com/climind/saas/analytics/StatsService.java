package com.climind.saas.analytics;

import com.climind.saas.analytics.StatsDtos.Bucket;
import com.climind.saas.analytics.StatsDtos.CalculatorStats;
import com.climind.saas.analytics.StatsDtos.Point;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.tenants.CurrentTenant;
import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Estadísticas del negocio, calculadas con agregados en PostgreSQL sobre índices (tenant, fecha).
 * Límites para que ninguna consulta sea costosa: período máximo de 366 días, series por día hasta 92 días
 * (por mes en adelante), hasta 20 cotizadores y 8 rangos de importes.
 */
@Service
public class StatsService {

    static final int MAX_RANGE_DAYS = 366;
    static final int DAILY_UP_TO_DAYS = 92;
    private static final int MAX_BUCKETS = 8;

    private final JdbcTemplate jdbc;
    private final CurrentTenant currentTenant;
    private final TenantService tenants;
    private final Clock clock;

    public StatsService(JdbcTemplate jdbc, CurrentTenant currentTenant, TenantService tenants, Clock clock) {
        this.jdbc = jdbc;
        this.currentTenant = currentTenant;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StatsDtos.StatsResponse summary(LocalDate from, LocalDate to) {
        UUID tenantId = currentTenant.requireId();
        Tenant tenant = tenants.find(tenantId).orElseThrow();
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw ApiException.field("from", "La fecha de inicio tiene que ser anterior a la de fin.");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw ApiException.field("from", "Elegí un período de hasta 12 meses.");
        }
        Timestamp lower = Timestamp.from(start.atStartOfDay(zone).toInstant());
        Timestamp upper = Timestamp.from(end.plusDays(1).atStartOfDay(zone).toInstant());
        boolean daily = ChronoUnit.DAYS.between(start, end) < DAILY_UP_TO_DAYS;

        return new StatsDtos.StatsResponse(start, end, daily ? "DAY" : "MONTH", tenant.getCurrency(),
                totals(tenantId, lower, upper), series(tenantId, zone, lower, upper, start, end, daily),
                calculators(tenantId, lower, upper), distribution(tenantId, lower, upper));
    }

    private StatsDtos.Totals totals(UUID tenantId, Timestamp lower, Timestamp upper) {
        return jdbc.queryForObject("""
                        WITH sims AS (
                            SELECT s.total, s.whatsapp_clicked_at,
                                   EXISTS (SELECT 1 FROM quote_requests r WHERE r.simulation_id = s.id) AS has_lead
                            FROM quote_simulations s
                            WHERE s.tenant_id = ? AND s.created_at >= ? AND s.created_at < ?),
                        leads AS (
                            SELECT count(*) AS leads, count(*) FILTER (WHERE status = 'CONVERTIDA') AS converted
                            FROM quote_requests
                            WHERE tenant_id = ? AND created_at >= ? AND created_at < ?)
                        SELECT (SELECT count(*) FROM sims) AS sims,
                               (SELECT count(*) FILTER (WHERE has_lead) FROM sims) AS with_lead,
                               (SELECT count(*) FILTER (WHERE whatsapp_clicked_at IS NOT NULL) FROM sims) AS wa,
                               (SELECT avg(total) FROM sims) AS avg_total,
                               (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY total) FROM sims) AS median_total,
                               leads.leads, leads.converted
                        FROM leads
                        """,
                (rs, i) -> {
                    long sims = rs.getLong("sims");
                    long withLead = rs.getLong("with_lead");
                    return new StatsDtos.Totals(sims, rs.getLong("leads"), withLead, rate(withLead, sims),
                            rs.getLong("converted"), rs.getLong("wa"), money(rs.getBigDecimal("avg_total")),
                            money(rs.getBigDecimal("median_total")));
                },
                tenantId, lower, upper, tenantId, lower, upper);
    }

    private List<Point> series(UUID tenantId, ZoneId zone, Timestamp lower, Timestamp upper, LocalDate start,
                               LocalDate end, boolean daily) {
        String unit = daily ? "day" : "month";
        Map<LocalDate, long[]> points = new LinkedHashMap<>();
        for (LocalDate d = daily ? start : start.withDayOfMonth(1); !d.isAfter(end); d = daily ? d.plusDays(1) : d.plusMonths(1)) {
            points.put(d, new long[2]);
        }
        String sql = """
                SELECT date_trunc(?, created_at AT TIME ZONE ?)::date AS bucket, count(*)
                FROM %s WHERE tenant_id = ? AND created_at >= ? AND created_at < ? GROUP BY 1
                """;
        jdbc.query(sql.formatted("quote_simulations"), rs -> {
            long[] p = points.get(rs.getObject(1, LocalDate.class));
            if (p != null) {
                p[0] = rs.getLong(2);
            }
        }, unit, zone.getId(), tenantId, lower, upper);
        jdbc.query(sql.formatted("quote_requests"), rs -> {
            long[] p = points.get(rs.getObject(1, LocalDate.class));
            if (p != null) {
                p[1] = rs.getLong(2);
            }
        }, unit, zone.getId(), tenantId, lower, upper);
        return points.entrySet().stream().map(e -> new Point(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
    }

    private List<CalculatorStats> calculators(UUID tenantId, Timestamp lower, Timestamp upper) {
        return jdbc.query("""
                        SELECT c.id, c.name, count(*) AS sims,
                               count(*) FILTER (WHERE EXISTS (SELECT 1 FROM quote_requests r WHERE r.simulation_id = s.id)) AS with_lead
                        FROM quote_simulations s JOIN calculators c ON c.id = s.calculator_id AND c.tenant_id = s.tenant_id
                        WHERE s.tenant_id = ? AND s.created_at >= ? AND s.created_at < ?
                        GROUP BY c.id, c.name ORDER BY sims DESC, c.name LIMIT 20
                        """,
                (rs, i) -> new CalculatorStats(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getLong("sims"), rs.getLong("with_lead"), rate(rs.getLong("with_lead"), rs.getLong("sims"))),
                tenantId, lower, upper);
    }

    /** Histograma de importes con rangos "redondos" (p. ej. de 50.000 en 50.000). */
    private List<Bucket> distribution(UUID tenantId, Timestamp lower, Timestamp upper) {
        Map<String, Object> range = jdbc.queryForMap("""
                SELECT min(total) AS lo, max(total) AS hi FROM quote_simulations
                WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                """, tenantId, lower, upper);
        BigDecimal lo = (BigDecimal) range.get("lo");
        BigDecimal hi = (BigDecimal) range.get("hi");
        if (lo == null || hi == null) {
            return List.of();
        }
        BigDecimal step = niceStep(hi.subtract(lo));
        BigDecimal base = lo.divide(step, 0, RoundingMode.FLOOR).multiply(step);
        int count = hi.subtract(base).divide(step, 0, RoundingMode.FLOOR).intValue() + 1;
        long[] counts = new long[count];
        jdbc.query("""
                        SELECT floor((total - ?) / ?)::int AS b, count(*) FROM quote_simulations
                        WHERE tenant_id = ? AND created_at >= ? AND created_at < ? GROUP BY 1
                        """,
                rs -> {
                    int b = Math.min(Math.max(rs.getInt(1), 0), count - 1);
                    counts[b] += rs.getLong(2);
                }, base, step, tenantId, lower, upper);
        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BigDecimal from = base.add(step.multiply(BigDecimal.valueOf(i)));
            buckets.add(new Bucket(from, from.add(step), counts[i]));
        }
        return buckets;
    }

    /** Paso 1, 2, 2,5 o 5 × 10^n que divide el rango en a lo sumo {@value #MAX_BUCKETS} partes. */
    static BigDecimal niceStep(BigDecimal span) {
        if (span.signum() <= 0) {
            return BigDecimal.valueOf(1000);
        }
        BigDecimal raw = span.divide(BigDecimal.valueOf(MAX_BUCKETS - 1L), 10, RoundingMode.CEILING);
        BigDecimal magnitude = BigDecimal.TEN.pow(Math.max(raw.precision() - raw.scale() - 1, 0));
        for (String factor : new String[]{"1", "2", "2.5", "5", "10"}) {
            BigDecimal candidate = magnitude.multiply(new BigDecimal(factor));
            if (candidate.compareTo(raw) >= 0) {
                return candidate.stripTrailingZeros().setScale(Math.max(candidate.stripTrailingZeros().scale(), 0),
                        RoundingMode.UNNECESSARY);
            }
        }
        return magnitude.multiply(BigDecimal.TEN);
    }

    private static BigDecimal rate(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
