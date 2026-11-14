package com.climind.saas.demo;

import com.climind.saas.businesses.BusinessService;
import com.climind.saas.calculators.CalculatorService;
import com.climind.saas.calculators.PublicCalculators;
import com.climind.saas.calculators.PublicCalculators.PublishedCalculator;
import com.climind.saas.calculators.TemplateCatalog;
import com.climind.saas.pricing.InvalidAnswersException;
import com.climind.saas.pricing.PriceBreakdown;
import com.climind.saas.pricing.PricingEngine;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.ratelimit.RateLimiter;
import com.climind.saas.shared.web.ApiError;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Demos públicas de Climind Cotiza: un negocio FICTICIO ("demo") con un cotizador por plantilla, para que
 * cualquiera pruebe la experiencia del cliente final sin registrarse.
 * <ul>
 *   <li>Datos independientes de los clientes reales: el negocio está marcado como demo, no tiene dueño (nadie
 *       entra a su panel) y queda fuera de las métricas.</li>
 *   <li>No recibe consultas: no hay nadie que vaya a responder, así que no se piden datos personales.</li>
 *   <li>Las cotizaciones de prueba se borran a las 48 h (son ficticias; no hay datos de nadie).</li>
 *   <li>La estimación en vivo de la landing usa el mismo motor y la misma versión publicada, sin guardar nada.</li>
 * </ul>
 */
@Slf4j
@Service
public class DemoService {

    /** Demos: dirección del cotizador → plantilla de la que se crea. El orden es el de la landing. */
    static final List<DemoDefinition> DEMOS = List.of(
            new DemoDefinition("limpieza", "limpieza"),
            new DemoDefinition("catering", "catering"),
            new DemoDefinition("instalaciones", "instalaciones"));

    static final String BUSINESS_NAME = "Servicios Ejemplo";
    static final String BUSINESS_DESCRIPTION = "Negocio ficticio para probar Climind Cotiza. Los precios son de "
            + "ejemplo y no se envía ninguna consulta.";

    record DemoDefinition(String slug, String templateCode) {
    }

    /** Demo disponible para la landing. */
    public record Demo(String slug, String name, String description, String industry, String path) {
    }

    private final AppProperties properties;
    private final TenantService tenants;
    private final BusinessService businesses;
    private final CalculatorService calculators;
    private final PublicCalculators publicCalculators;
    private final TemplateCatalog templates;
    private final RateLimiter rateLimiter;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DemoService(AppProperties properties, TenantService tenants, BusinessService businesses,
                       CalculatorService calculators, PublicCalculators publicCalculators, TemplateCatalog templates,
                       RateLimiter rateLimiter, JdbcTemplate jdbc, Clock clock) {
        this.properties = properties;
        this.tenants = tenants;
        this.businesses = businesses;
        this.calculators = calculators;
        this.publicCalculators = publicCalculators;
        this.templates = templates;
        this.rateLimiter = rateLimiter;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Crea lo que falte del negocio de demostración. Idempotente: no toca lo que ya existe.
     *
     * @return cantidad de cotizadores creados ahora
     */
    @Transactional
    public int seed() {
        String slug = properties.getDemo().getSlug();
        Tenant tenant = tenants.findDemo(slug).orElse(null);
        if (tenant == null) {
            if (tenants.findActiveBySlug(slug).isPresent()) {
                // Un negocio real con esa dirección: nunca se pisa (no debería pasar, los slugs llevan sufijo).
                log.warn("No se crean las demos: la dirección '{}' ya pertenece a un negocio real", slug);
                return 0;
            }
            tenant = tenants.createDemo(BUSINESS_NAME, slug);
        }
        businesses.initializeDemo(tenant.getId(), BUSINESS_NAME, BUSINESS_DESCRIPTION);
        int created = 0;
        for (DemoDefinition demo : DEMOS) {
            if (calculators.seedPublishedFromTemplate(tenant.getId(), demo.templateCode(), demo.slug())) {
                created++;
            }
        }
        return created;
    }

    /** Demos publicadas (vacío si las demos están deshabilitadas o todavía no se crearon). */
    @Transactional(readOnly = true)
    public List<Demo> list() {
        return DEMOS.stream()
                .flatMap(d -> published(d.slug()).map(pc -> new Demo(d.slug(), pc.name(), pc.description(),
                        templates.find(d.templateCode()).map(TemplateCatalog.Template::name).orElse(null),
                        "/c/" + pc.tenantSlug() + "/" + pc.calculatorSlug())).stream())
                .toList();
    }

    /**
     * Estimación en vivo para la landing: mismo motor y misma versión publicada que la página pública, en modo
     * vista previa (calcula con lo que haya). No guarda cotizaciones ni consume nada.
     */
    @Transactional(readOnly = true)
    public PriceBreakdown estimate(String calculatorSlug, Map<String, Object> answers, String ip) {
        if (!rateLimiter.tryConsume(RateLimiter.Policy.PUBLIC_DEMO_IP, ip)) {
            throw ApiException.tooManyRequests();
        }
        PublishedCalculator pc = published(calculatorSlug)
                .orElseThrow(() -> ApiException.notFound("No encontramos esta demo."));
        try {
            return PricingEngine.calculate(pc.config(), answers == null ? Map.of() : answers,
                    PricingEngine.Mode.PREVIEW, LocalDate.now(clock.withZone(pc.zone())));
        } catch (InvalidAnswersException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ANSWERS", e.issues().getFirst().message(),
                    e.issues().stream().map(i -> new ApiError.FieldError(i.question(), i.message())).toList());
        }
    }

    /** Borra las cotizaciones de prueba viejas de las demos (nunca tienen consultas asociadas). */
    @Scheduled(cron = "${app.demo.cleanup-cron:0 50 4 * * *}", zone = "America/Argentina/Buenos_Aires")
    @Transactional
    public int purgeOldQuotes() {
        Timestamp before = Timestamp.from(clock.instant().minus(properties.getDemo().getRetention()));
        int deleted = jdbc.update("""
                DELETE FROM quote_simulations s
                USING tenants t
                WHERE s.tenant_id = t.id AND t.demo AND s.created_at < ?
                  AND NOT EXISTS (SELECT 1 FROM quote_requests r WHERE r.simulation_id = s.id)
                """, before);
        if (deleted > 0) {
            log.info("Demos: se borraron {} cotizaciones de prueba", deleted);
        }
        return deleted;
    }

    private Optional<PublishedCalculator> published(String calculatorSlug) {
        if (DEMOS.stream().noneMatch(d -> d.slug().equals(calculatorSlug))) {
            return Optional.empty();
        }
        return publicCalculators.find(properties.getDemo().getSlug(), calculatorSlug)
                .filter(PublishedCalculator::demo)
                .filter(PublishedCalculator::available);
    }
}
