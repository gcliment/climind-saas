package com.climind.saas.calculators;

import com.climind.saas.calculators.Calculator.Status;
import com.climind.saas.calculators.CalculatorDtos.CalculatorDetail;
import com.climind.saas.calculators.CalculatorDtos.CalculatorSummary;
import com.climind.saas.pricing.CalculatorConfig;
import com.climind.saas.pricing.ConfigIssue;
import com.climind.saas.pricing.ConfigValidator;
import com.climind.saas.pricing.InvalidAnswersException;
import com.climind.saas.pricing.PriceBreakdown;
import com.climind.saas.pricing.PricingEngine;
import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.security.CurrentUser;
import com.climind.saas.shared.util.Slugs;
import com.climind.saas.shared.web.ApiError;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.tenants.CurrentTenant;
import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cotizadores del negocio autenticado: borrador, publicación de versiones inmutables, estados y vista previa.
 * <p>
 * Estados: BORRADOR → (publicar) → PUBLICADO ⇄ PAUSADO; cualquiera → ARCHIVADO → (restaurar) PAUSADO/BORRADOR.
 * Nunca se borra un cotizador ni una versión.
 */
@Service
public class CalculatorService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,58}[a-z0-9])$");
    private static final Set<Status> ACTIVE = EnumSet.of(Status.BORRADOR, Status.PUBLICADO, Status.PAUSADO);

    private final CalculatorRepository calculators;
    private final CalculatorVersionRepository versions;
    private final TemplateCatalog templates;
    private final ConfigJson json;
    private final CurrentTenant currentTenant;
    private final TenantService tenantService;
    private final AuditLogService audit;
    private final AppProperties properties;
    private final com.climind.saas.subscriptions.EntitlementService entitlements;
    private final com.climind.saas.subscriptions.UsageService usage;
    private final Clock clock;

    public CalculatorService(CalculatorRepository calculators, CalculatorVersionRepository versions,
                             TemplateCatalog templates, ConfigJson json, CurrentTenant currentTenant,
                             TenantService tenantService, AuditLogService audit, AppProperties properties,
                             com.climind.saas.subscriptions.EntitlementService entitlements,
                             com.climind.saas.subscriptions.UsageService usage, Clock clock) {
        this.calculators = calculators;
        this.versions = versions;
        this.templates = templates;
        this.json = json;
        this.currentTenant = currentTenant;
        this.tenantService = tenantService;
        this.audit = audit;
        this.properties = properties;
        this.entitlements = entitlements;
        this.usage = usage;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public List<CalculatorSummary> list(boolean includeArchived) {
        UUID tenantId = currentTenant.requireId();
        Set<Status> statuses = includeArchived ? EnumSet.allOf(Status.class) : ACTIVE;
        List<Calculator> list = calculators.findByTenantIdAndStatusInOrderByUpdatedAtDesc(tenantId, statuses);
        Map<UUID, CalculatorVersion> published = versions.findByIdInAndTenantId(
                        list.stream().map(Calculator::getPublishedVersionId).filter(Objects::nonNull).toList(), tenantId)
                .stream().collect(Collectors.toMap(CalculatorVersion::getId, Function.identity()));
        String tenantSlug = tenantSlug(tenantId);
        return list.stream().map(c -> {
            CalculatorVersion v = published.get(c.getPublishedVersionId());
            return new CalculatorSummary(c.getId(), c.getName(), c.getSlug(), c.getStatus().name(), c.getTemplateCode(),
                    c.getUpdatedAt(), v == null ? null : v.getVersionNo(), c.getPublishedAt(),
                    v != null && !v.getConfigHash().equals(draftHash(c)), publicUrl(tenantSlug, c));
        }).toList();
    }

    @Transactional(readOnly = true)
    public CalculatorDetail get(UUID id) {
        UUID tenantId = currentTenant.requireId();
        return detail(load(id, tenantId), tenantId);
    }

    @Transactional(readOnly = true)
    public List<CalculatorDtos.VersionSummary> versions(UUID id) {
        UUID tenantId = currentTenant.requireId();
        Calculator calculator = load(id, tenantId);
        return versions.findByCalculatorIdAndTenantIdOrderByVersionNoDesc(calculator.getId(), tenantId).stream()
                .map(v -> new CalculatorDtos.VersionSummary(v.getVersionNo(), v.getPublishedAt(),
                        v.getId().equals(calculator.getPublishedVersionId())))
                .toList();
    }

    // ------------------------------------------------------------------ alta y edición

    @Transactional
    public CalculatorDetail create(CalculatorDtos.CreateCalculatorRequest request) {
        UUID tenantId = currentTenant.requireId();
        CalculatorConfig config = CalculatorConfig.empty();
        String templateCode = null;
        String description = null;
        if (request.templateCode() != null && !request.templateCode().isBlank()) {
            TemplateCatalog.Template template = templates.find(request.templateCode())
                    .orElseThrow(() -> ApiException.field("templateCode", "La plantilla elegida no existe."));
            config = template.config();
            templateCode = template.code();
            description = template.calculatorDescription();
        }
        String name = request.name().strip();
        Calculator calculator = new Calculator(tenantId, name, uniqueSlug(tenantId, name, null), templateCode,
                json.write(config), json.write(Appearance.defaults()));
        calculator.setDescription(description);
        calculators.save(calculator);
        audit.record(AuditAction.CALCULATOR_CREATED, CurrentUser.require().userId(), tenantId, "calculator",
                calculator.getId().toString(), templateCode == null ? Map.of() : Map.of("template", templateCode));
        return detail(calculator, tenantId);
    }

    /**
     * Guarda el borrador. Acepta configuraciones incompletas pero nunca inconsistentes (validación estructural).
     * No afecta a la versión publicada hasta que se vuelva a publicar.
     */
    @Transactional
    public CalculatorDetail updateDraft(UUID id, CalculatorDtos.UpdateCalculatorRequest request) {
        UUID tenantId = currentTenant.requireId();
        Calculator calculator = load(id, tenantId);
        if (calculator.isArchived()) {
            throw ApiException.conflict("CALCULATOR_ARCHIVED", "Restaurá el cotizador para poder editarlo.");
        }
        if (request.lockVersion() != calculator.getLockVersion()) {
            throw ApiException.conflict("STALE_VERSION",
                    "Este cotizador se modificó en otra pestaña o dispositivo. Recargá para ver la última versión.");
        }
        CalculatorConfig config = json.parseRequest(request.config());
        List<ConfigIssue> issues = ConfigValidator.validateStructure(config);
        if (!issues.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIG_INVALID",
                    "Hay datos del cotizador para revisar.", List.of(), issues);
        }
        String slug = request.slug().strip().toLowerCase(java.util.Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) {
            throw ApiException.field("slug", "Usá solo minúsculas, números y guiones (entre 3 y 60 caracteres).");
        }
        if (calculators.existsByTenantIdAndSlugAndIdNot(tenantId, slug, id)) {
            throw ApiException.field("slug", "Ya tenés otro cotizador con esa dirección.");
        }
        Appearance appearance = (request.appearance() == null ? Appearance.defaults() : request.appearance()).validated();

        calculator.setName(request.name().strip());
        calculator.setDescription(request.description() == null || request.description().isBlank()
                ? null : request.description().strip());
        calculator.setSlug(slug);
        calculator.setDraftConfig(json.write(config));
        calculator.setAppearance(json.write(appearance));
        calculators.saveAndFlush(calculator);
        return detail(calculator, tenantId);
    }

    // ------------------------------------------------------------------ publicación y estados

    /**
     * Publica el borrador como una versión nueva e inmutable. Si el contenido no cambió desde la última
     * publicación no crea otra versión (solo reactiva si estaba pausado).
     */
    @Transactional
    public CalculatorDtos.PublishResponse publish(UUID id) {
        UUID tenantId = currentTenant.requireId();
        Calculator calculator = load(id, tenantId);
        if (calculator.isArchived()) {
            throw ApiException.conflict("CALCULATOR_ARCHIVED", "Restaurá el cotizador para poder publicarlo.");
        }
        CalculatorConfig config = json.parse(calculator.getDraftConfig());
        List<ConfigIssue> issues = ConfigValidator.validateForPublish(config);
        if (!issues.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_PUBLISHABLE",
                    "Antes de publicar, completá lo que falta.", List.of(), issues);
        }
        if (calculator.getStatus() != Status.PUBLICADO) {
            requirePublishSlot(tenantId);
        }
        UUID userId = CurrentUser.require().userId();
        String hash = draftHash(calculator);
        CalculatorVersion current = calculator.getPublishedVersionId() == null ? null
                : versions.findById(calculator.getPublishedVersionId()).orElse(null);
        boolean created = current == null || !current.getConfigHash().equals(hash);
        int versionNo;
        if (created) {
            versionNo = versions.maxVersionNo(calculator.getId()) + 1;
            CalculatorVersion version = versions.save(
                    new CalculatorVersion(calculator, versionNo, hash, userId, clock.instant()));
            calculator.published(version.getId(), version.getPublishedAt());
        } else {
            versionNo = current.getVersionNo();
            calculator.resume();
        }
        calculators.saveAndFlush(calculator);
        // Con el primer cotizador publicado, la dirección del negocio queda fija (ya puede estar compartida).
        tenantService.markOnboardingCompleted(tenantId);
        audit.record(AuditAction.CALCULATOR_PUBLISHED, userId, tenantId, "calculator", calculator.getId().toString(),
                Map.of("versionNo", versionNo, "newVersion", created));
        return new CalculatorDtos.PublishResponse(detail(calculator, tenantId), created, versionNo);
    }

    @Transactional
    public CalculatorDetail pause(UUID id) {
        return transition(id, Status.PUBLICADO, Calculator::pause, AuditAction.CALCULATOR_PAUSED,
                "Solo se puede pausar un cotizador publicado.");
    }

    @Transactional
    public CalculatorDetail resume(UUID id) {
        requirePublishSlot(currentTenant.requireId());
        return transition(id, Status.PAUSADO, Calculator::resume, AuditAction.CALCULATOR_RESUMED,
                "Solo se puede reactivar un cotizador pausado.");
    }

    @Transactional
    public CalculatorDetail archive(UUID id) {
        UUID tenantId = currentTenant.requireId();
        Calculator calculator = load(id, tenantId);
        if (calculator.isArchived()) {
            return detail(calculator, tenantId);
        }
        calculator.archive(clock.instant());
        calculators.saveAndFlush(calculator);
        audit.record(AuditAction.CALCULATOR_ARCHIVED, CurrentUser.require().userId(), tenantId, "calculator",
                id.toString(), Map.of());
        return detail(calculator, tenantId);
    }

    @Transactional
    public CalculatorDetail restore(UUID id) {
        return transition(id, Status.ARCHIVADO, Calculator::restore, AuditAction.CALCULATOR_RESTORED,
                "El cotizador no está archivado.");
    }

    @Transactional
    public CalculatorDetail duplicate(UUID id) {
        UUID tenantId = currentTenant.requireId();
        Calculator source = load(id, tenantId);
        String name = truncate("Copia de " + source.getName(), 120);
        Calculator copy = new Calculator(tenantId, name, uniqueSlug(tenantId, name, null), source.getTemplateCode(),
                source.getDraftConfig(), source.getAppearance());
        copy.setDescription(source.getDescription());
        calculators.save(copy);
        audit.record(AuditAction.CALCULATOR_DUPLICATED, CurrentUser.require().userId(), tenantId, "calculator",
                copy.getId().toString(), Map.of("source", id.toString()));
        return detail(copy, tenantId);
    }

    // ------------------------------------------------------------------ vista previa

    /**
     * Calcula con el motor real (el mismo de las cotizaciones públicas), en modo vista previa: no guarda nada
     * y no consume cupo. Permite enviar una configuración sin guardar para ver los cambios en vivo.
     */
    @Transactional(readOnly = true)
    public CalculatorDtos.PreviewResponse preview(UUID id, CalculatorDtos.PreviewRequest request) {
        UUID tenantId = currentTenant.requireId();
        Calculator calculator = load(id, tenantId);
        CalculatorConfig config = request.config() == null
                ? json.parse(calculator.getDraftConfig())
                : json.parseRequest(request.config());
        List<ConfigIssue> issues = ConfigValidator.validateStructure(config);
        if (!issues.isEmpty()) {
            return new CalculatorDtos.PreviewResponse(null, issues);
        }
        try {
            PriceBreakdown result = PricingEngine.calculate(config, request.answers(), PricingEngine.Mode.PREVIEW,
                    today(tenantId));
            return new CalculatorDtos.PreviewResponse(result, List.of());
        } catch (InvalidAnswersException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ANSWERS",
                    e.issues().getFirst().message(),
                    e.issues().stream().map(i -> new ApiError.FieldError(i.question(), i.message())).toList());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Límite de cotizadores publicados del plan vigente (verificado acá, nunca solo en el frontend). */
    private void requirePublishSlot(UUID tenantId) {
        int limit = entitlements.forTenant(tenantId).maxPublishedCalculators();
        if (usage.publishedCalculators(tenantId) >= limit) {
            throw ApiException.conflict("PLAN_LIMIT_CALCULATORS", limit == 1
                    ? "Tu plan permite 1 cotizador publicado. Pausá el que tenés publicado o pasate a un plan con más cotizadores."
                    : "Tu plan permite " + limit + " cotizadores publicados. Pausá alguno o pasate a un plan con más cotizadores.");
        }
    }

    private CalculatorDetail transition(UUID id, Status expected, java.util.function.Consumer<Calculator> change,
                                        AuditAction action, String errorMessage) {
        UUID tenantId = currentTenant.requireId();
        Calculator calculator = load(id, tenantId);
        if (calculator.getStatus() != expected) {
            throw ApiException.conflict("INVALID_STATUS", errorMessage);
        }
        change.accept(calculator);
        calculators.saveAndFlush(calculator);
        audit.record(action, CurrentUser.require().userId(), tenantId, "calculator", id.toString(), Map.of());
        return detail(calculator, tenantId);
    }

    /** Busca SIEMPRE dentro del negocio de la sesión: un id de otro negocio es un 404, no un 403. */
    private Calculator load(UUID id, UUID tenantId) {
        return calculators.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> ApiException.notFound("No encontramos ese cotizador."));
    }

    private CalculatorDetail detail(Calculator c, UUID tenantId) {
        CalculatorConfig config = json.parse(c.getDraftConfig());
        CalculatorVersion version = c.getPublishedVersionId() == null ? null
                : versions.findById(c.getPublishedVersionId()).orElse(null);
        return new CalculatorDetail(c.getId(), c.getName(), c.getDescription(), c.getSlug(), c.getStatus().name(),
                c.getTemplateCode(), c.getLockVersion(), json.tree(json.write(config)),
                json.read(c.getAppearance(), Appearance.class),
                version == null ? null : version.getVersionNo(), c.getPublishedAt(),
                version != null && !version.getConfigHash().equals(draftHash(c)),
                publicUrl(tenantSlug(tenantId), c), ConfigValidator.validateForPublish(config), c.getUpdatedAt());
    }

    /** Hash de todo lo que ve el cliente: nombre, descripción, configuración y apariencia. */
    private String draftHash(Calculator c) {
        return json.hash(c.getName(), Objects.toString(c.getDescription(), ""),
                json.parse(c.getDraftConfig()), json.read(c.getAppearance(), Appearance.class));
    }

    private String publicUrl(String tenantSlug, Calculator c) {
        return properties.publicUrlWithoutTrailingSlash() + "/c/" + tenantSlug + "/" + c.getSlug();
    }

    private String tenantSlug(UUID tenantId) {
        return tenantService.find(tenantId).map(Tenant::getSlug).orElseThrow();
    }

    private LocalDate today(UUID tenantId) {
        ZoneId zone = tenantService.find(tenantId).map(t -> ZoneId.of(t.getTimezone()))
                .orElse(ZoneId.of("America/Argentina/Buenos_Aires"));
        return LocalDate.now(clock.withZone(zone));
    }

    String uniqueSlug(UUID tenantId, String name, UUID excludeId) {
        String base = Slugs.slugify(name, 50);
        if (base.length() < 3) {
            base = "cotizador";
        }
        String candidate = base;
        for (int i = 2; i < 200; i++) {
            boolean taken = excludeId == null ? calculators.existsByTenantIdAndSlug(tenantId, candidate)
                    : calculators.existsByTenantIdAndSlugAndIdNot(tenantId, candidate, excludeId);
            if (!taken) {
                return candidate;
            }
            candidate = base + "-" + i;
        }
        return base + "-" + Slugs.randomSuffix(5);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).strip();
    }
}
