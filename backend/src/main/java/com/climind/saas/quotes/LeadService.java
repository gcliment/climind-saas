package com.climind.saas.quotes;

import com.climind.saas.businesses.PublicBusinesses;
import com.climind.saas.calculators.ConfigJson;
import com.climind.saas.calculators.PublicCalculators;
import com.climind.saas.pricing.CalculatorConfig;
import com.climind.saas.quotes.LeadDtos.LeadFilter;
import com.climind.saas.quotes.QuoteRequest.Status;
import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.security.CurrentUser;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.shared.web.PageResponse;
import com.climind.saas.tenants.CurrentTenant;
import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantService;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Consultas del negocio autenticado: listado con filtros, detalle, estados, notas, exportación, PDF y
 * anonimización. Todo acotado al negocio de la sesión (una consulta de otro negocio responde 404).
 */
@Slf4j
@Service
public class LeadService {

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_EXPORT_ROWS = 5000;
    private static final String REDACTED = "(dato eliminado)";

    private final QuoteRequestRepository requests;
    private final QuoteSimulationRepository simulations;
    private final QuoteHistoryRepository history;
    private final QuoteDocuments documents;
    private final PublicCalculators calculators;
    private final PublicBusinesses businesses;
    private final CurrentTenant currentTenant;
    private final TenantService tenants;
    private final ConfigJson json;
    private final AuditLogService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final com.climind.saas.subscriptions.EntitlementService entitlements;
    private final Clock clock;

    public LeadService(QuoteRequestRepository requests, QuoteSimulationRepository simulations,
                       QuoteHistoryRepository history, QuoteDocuments documents, PublicCalculators calculators,
                       PublicBusinesses businesses, CurrentTenant currentTenant, TenantService tenants, ConfigJson json,
                       AuditLogService audit, JdbcTemplate jdbc, TransactionTemplate transactions,
                       com.climind.saas.subscriptions.EntitlementService entitlements, Clock clock) {
        this.requests = requests;
        this.simulations = simulations;
        this.history = history;
        this.documents = documents;
        this.calculators = calculators;
        this.businesses = businesses;
        this.currentTenant = currentTenant;
        this.tenants = tenants;
        this.json = json;
        this.audit = audit;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public PageResponse<LeadDtos.LeadSummary> list(LeadFilter filter, int page, int size) {
        UUID tenantId = currentTenant.requireId();
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                sort(filter.sort()));
        return PageResponse.of(requests.findAll(spec(tenantId, filter, zone(tenantId)), pageable), LeadService::summary);
    }

    /** Cantidad por estado (para los filtros rápidos y el panel de inicio). */
    @Transactional(readOnly = true)
    public List<LeadDtos.StatusOption> statusCounts() {
        UUID tenantId = currentTenant.requireId();
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        jdbc.query("SELECT status, count(*) FROM quote_requests WHERE tenant_id = ? GROUP BY status",
                rs -> {
                    counts.put(Status.valueOf(rs.getString(1)), rs.getLong(2));
                }, tenantId);
        return Arrays.stream(Status.values())
                .map(s -> new LeadDtos.StatusOption(s.name(), s.label(), counts.getOrDefault(s, 0L))).toList();
    }

    @Transactional(readOnly = true)
    public LeadDtos.LeadDetail detail(UUID id) {
        UUID tenantId = currentTenant.requireId();
        return detail(load(id, tenantId));
    }

    // ------------------------------------------------------------------ seguimiento

    @Transactional
    public LeadDtos.LeadDetail changeStatus(UUID id, LeadDtos.ChangeStatusRequest request) {
        UUID tenantId = currentTenant.requireId();
        QuoteRequest lead = load(id, tenantId);
        Status next = parseStatus(request.status());
        String note = blankToNull(request.note());
        Instant now = clock.instant();
        UUID userId = CurrentUser.require().userId();
        if (next != lead.getStatus()) {
            Status previous = lead.getStatus();
            lead.changeStatus(next, now);
            history.save(new QuoteHistoryEntry(lead, QuoteHistoryEntry.Kind.STATUS_CHANGED, previous, next,
                    lead.isAnonymized() ? null : note, userId, now));
            audit.record(AuditAction.LEAD_STATUS_CHANGED, userId, tenantId, "quote_request", id.toString(),
                    Map.of("from", previous.name(), "to", next.name()));
        } else if (note != null) {
            addNote(lead, note, userId, now);
        }
        requests.saveAndFlush(lead);
        return detail(lead);
    }

    @Transactional
    public LeadDtos.LeadDetail addNote(UUID id, LeadDtos.NoteRequest request) {
        UUID tenantId = currentTenant.requireId();
        QuoteRequest lead = load(id, tenantId);
        addNote(lead, request.note().strip(), CurrentUser.require().userId(), clock.instant());
        requests.saveAndFlush(lead);
        return detail(lead);
    }

    private void addNote(QuoteRequest lead, String note, UUID userId, Instant now) {
        if (lead.isAnonymized()) {
            throw ApiException.conflict("LEAD_ANONYMIZED",
                    "Los datos de esta consulta se eliminaron: ya no se pueden agregar notas.");
        }
        history.save(new QuoteHistoryEntry(lead, QuoteHistoryEntry.Kind.NOTE, null, null, note, userId, now));
        lead.touch(now);
    }

    /**
     * Elimina los datos personales de la consulta (pedido del cliente o decisión del negocio): nombre,
     * contacto, comentarios, notas y respuestas de texto libre. Quedan código, importe y estado.
     */
    @Transactional
    public LeadDtos.LeadDetail anonymize(UUID id) {
        UUID tenantId = currentTenant.requireId();
        QuoteRequest lead = load(id, tenantId);
        if (!lead.isAnonymized()) {
            UUID userId = CurrentUser.require().userId();
            anonymize(lead, userId);
            audit.record(AuditAction.LEAD_ANONYMIZED, userId, tenantId, "quote_request", id.toString(), Map.of());
        }
        return detail(lead);
    }

    private void anonymize(QuoteRequest lead, UUID userId) {
        Instant now = clock.instant();
        lead.anonymize(now);
        history.findByRequestIdAndTenantIdOrderByCreatedAtAscIdAsc(lead.getId(), lead.getTenantId())
                .forEach(QuoteHistoryEntry::clearNote);
        history.save(new QuoteHistoryEntry(lead, QuoteHistoryEntry.Kind.ANONYMIZED, null, null, null, userId, now));
        simulations.findByIdAndTenantId(lead.getSimulationId(), lead.getTenantId())
                .ifPresent(simulation -> redactFreeText(simulation, now));
        requests.saveAndFlush(lead);
    }

    /** Las respuestas de texto libre pueden tener datos personales (dirección, teléfono): se borran. */
    private void redactFreeText(QuoteSimulation simulation, Instant now) {
        Set<String> textKeys = calculators.context(simulation.getTenantId(), simulation.getCalculatorId(),
                        simulation.getCalculatorVersionId())
                .map(c -> c.config().questions().stream()
                        .filter(q -> q.type() == CalculatorConfig.QuestionType.TEXT)
                        .map(CalculatorConfig.Question::key).collect(Collectors.toSet()))
                .orElse(Set.of());
        if (textKeys.isEmpty()) {
            return;
        }
        ObjectNode answers = (ObjectNode) json.tree(simulation.getAnswers());
        ObjectNode result = (ObjectNode) json.tree(simulation.getResult());
        textKeys.forEach(answers::remove);
        if (result.get("answers") instanceof ObjectNode resultAnswers) {
            textKeys.forEach(resultAnswers::remove);
        }
        if (result.get("answered") instanceof ArrayNode answered) {
            for (JsonNode item : answered) {
                if (item instanceof ObjectNode node && textKeys.contains(node.path("key").asString())) {
                    node.put("value", REDACTED);
                }
            }
        }
        simulation.redact(json.write(answers), json.write(result), now);
        simulations.save(simulation);
    }

    // ------------------------------------------------------------------ exportación y PDF

    /** No es de solo lectura: registra la exportación en la auditoría (datos personales que salen del sistema). */
    @Transactional
    public String exportCsv(LeadFilter filter) {
        UUID tenantId = currentTenant.requireId();
        if (!entitlements.forTenant(tenantId).csvExport()) {
            throw ApiException.forbidden("PLAN_FEATURE", "La exportación a CSV está incluida en los planes pagos.");
        }
        ZoneId zone = zone(tenantId);
        List<QuoteRequest> leads = requests.findAll(spec(tenantId, filter, zone),
                PageRequest.of(0, MAX_EXPORT_ROWS, sort(filter.sort()))).getContent();
        audit.record(AuditAction.LEADS_EXPORTED, CurrentUser.require().userId(), tenantId, "quote_request", null,
                Map.of("rows", leads.size()));
        return LeadCsv.write(leads, zone);
    }

    @Transactional(readOnly = true)
    public QuoteDocuments.Pdf pdf(UUID id) {
        UUID tenantId = currentTenant.requireId();
        QuoteRequest lead = load(id, tenantId);
        QuoteSimulation simulation = simulations.findByIdAndTenantId(lead.getSimulationId(), tenantId).orElseThrow();
        return documents.pdf(simulation, lead);
    }

    // ------------------------------------------------------------------ retención

    /**
     * Política de retención de cada negocio (Mi negocio → Datos de clientes): las consultas más viejas que el
     * plazo elegido se anonimizan solas. Corre todas las noches; procesa en lotes para no bloquear la base.
     */
    @Scheduled(cron = "${app.leads.retention-cron:0 30 4 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void applyRetentionPolicy() {
        int total = applyRetention(clock.instant());
        if (total > 0) {
            log.info("Retención de datos: {} consultas anonimizadas", total);
        }
    }

    /** @return cantidad de consultas anonimizadas */
    public int applyRetention(Instant now) {
        int total = 0;
        while (true) {
            List<UUID[]> batch = jdbc.query("""
                            SELECT r.id, r.tenant_id FROM quote_requests r
                            JOIN business_settings s ON s.tenant_id = r.tenant_id
                            WHERE r.anonymized_at IS NULL
                              AND r.created_at < ?::timestamptz - make_interval(months => s.lead_retention_months)
                            ORDER BY r.created_at LIMIT 200
                            """,
                    (rs, i) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)},
                    Timestamp.from(now));
            if (batch.isEmpty()) {
                return total;
            }
            Map<UUID, Long> perTenant = batch.stream().collect(Collectors.groupingBy(r -> r[1], Collectors.counting()));
            transactions.executeWithoutResult(status -> batch.forEach(row ->
                    requests.findByIdAndTenantId(row[0], row[1]).ifPresent(lead -> anonymize(lead, null))));
            perTenant.forEach((tenantId, count) -> audit.record(AuditAction.LEADS_RETENTION_APPLIED, null, tenantId,
                    "quote_request", null, Map.of("anonymized", count)));
            total += batch.size();
        }
    }

    // ------------------------------------------------------------------ helpers

    private QuoteRequest load(UUID id, UUID tenantId) {
        return requests.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> ApiException.notFound("No encontramos esa consulta."));
    }

    private LeadDtos.LeadDetail detail(QuoteRequest lead) {
        QuoteSimulation simulation = simulations.findByIdAndTenantId(lead.getSimulationId(), lead.getTenantId())
                .orElseThrow();
        String businessName = businesses.find(lead.getTenantId()).map(PublicBusinesses.PublicBusiness::name).orElse("");
        String whatsappUrl = lead.getCustomerWhatsapp() == null ? null : QuoteTexts.waUrl(lead.getCustomerWhatsapp(),
                QuoteTexts.businessMessage(lead.getCustomerName(), businessName, lead.getCode(), lead.getCalculatorName()));
        List<LeadDtos.HistoryItem> items = history
                .findByRequestIdAndTenantIdOrderByCreatedAtAscIdAsc(lead.getId(), lead.getTenantId()).stream()
                .map(h -> new LeadDtos.HistoryItem(h.getKind().name(),
                        h.getFromStatus() == null ? null : h.getFromStatus().label(),
                        h.getToStatus() == null ? null : h.getToStatus().label(), h.getNote(),
                        h.getActorUserId() != null, h.getCreatedAt()))
                .toList();
        return new LeadDtos.LeadDetail(summary(lead), lead.getComments(), lead.getConsentAt(),
                lead.getStatusChangedAt(), json.tree(simulation.getResult()), simulation.getPriceScale(),
                simulation.getValidUntil(), simulation.getDisclaimer(), simulation.getWhatsappClickedAt(), whatsappUrl,
                items);
    }

    static LeadDtos.LeadSummary summary(QuoteRequest r) {
        return new LeadDtos.LeadSummary(r.getId(), r.getCode(), r.getCreatedAt(), r.getCustomerName(),
                r.getCustomerWhatsapp(), r.getCustomerEmail(), r.getCalculatorId(), r.getCalculatorName(),
                r.getSummary(), r.getTotal(), r.getCurrency(), r.getStatus().name(), r.getStatus().label(),
                r.isAnonymized());
    }

    static Specification<QuoteRequest> spec(UUID tenantId, LeadFilter f, ZoneId zone) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("tenantId"), tenantId)); // SIEMPRE: aislamiento por negocio
            if (f.status() != null && !f.status().isBlank()) {
                where.add(cb.equal(root.get("status"), parseStatus(f.status())));
            }
            if (f.calculatorId() != null) {
                where.add(cb.equal(root.get("calculatorId"), f.calculatorId()));
            }
            if (f.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from().atStartOfDay(zone).toInstant()));
            }
            if (f.to() != null) {
                where.add(cb.lessThan(root.get("createdAt"), f.to().plusDays(1).atStartOfDay(zone).toInstant()));
            }
            String q = f.q() == null ? "" : f.q().strip();
            if (!q.isEmpty()) {
                String like = "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
                List<Predicate> any = new ArrayList<>(List.of(
                        cb.like(cb.lower(root.get("customerName")), like, '\\'),
                        cb.like(cb.lower(root.get("code")), like, '\\'),
                        cb.like(cb.lower(root.get("customerEmail")), like, '\\'),
                        cb.like(cb.lower(root.get("calculatorName")), like, '\\')));
                String digits = q.replaceAll("\\D", "");
                if (digits.length() >= 4) {
                    any.add(cb.like(root.get("customerWhatsapp"), "%" + digits + "%"));
                }
                where.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    static Sort sort(String sort) {
        return switch (sort == null ? "" : sort) {
            case "antiguas" -> Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
            case "mayor_importe" -> Sort.by(Sort.Order.desc("total"), Sort.Order.desc("createdAt"));
            case "menor_importe" -> Sort.by(Sort.Order.asc("total"), Sort.Order.desc("createdAt"));
            default -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        };
    }

    static Status parseStatus(String value) {
        try {
            return Status.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.field("status", "Elegí un estado de la lista.");
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private ZoneId zone(UUID tenantId) {
        return tenants.find(tenantId).map(Tenant::getTimezone).map(ZoneId::of)
                .orElse(ZoneId.of("America/Argentina/Buenos_Aires"));
    }
}
