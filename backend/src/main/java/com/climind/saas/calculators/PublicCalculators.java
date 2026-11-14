package com.climind.saas.calculators;

import com.climind.saas.pricing.CalculatorConfig;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso público (sin sesión) a la versión publicada de un cotizador, por la dirección del negocio y del
 * cotizador. Nunca expone el borrador: los clientes ven siempre una versión inmutable.
 */
@Service
public class PublicCalculators {

    private final TenantService tenants;
    private final CalculatorRepository calculators;
    private final CalculatorVersionRepository versions;
    private final ConfigJson json;
    private final AppProperties properties;
    private final com.climind.saas.subscriptions.EntitlementService entitlements;

    public PublicCalculators(TenantService tenants, CalculatorRepository calculators,
                             CalculatorVersionRepository versions, ConfigJson json, AppProperties properties,
                             com.climind.saas.subscriptions.EntitlementService entitlements) {
        this.tenants = tenants;
        this.calculators = calculators;
        this.versions = versions;
        this.json = json;
        this.properties = properties;
        this.entitlements = entitlements;
    }

    /**
     * Cotizador publicado o pausado. {@code available=false} si está pausado (se informa al visitante con los
     * datos de contacto del negocio). Borradores, archivados y negocios suspendidos no existen para el público.
     */
    public record PublishedCalculator(
            UUID tenantId,
            String tenantSlug,
            String country,
            ZoneId zone,
            UUID calculatorId,
            String calculatorSlug,
            boolean available,
            UUID versionId,
            int versionNo,
            String name,
            String description,
            CalculatorConfig config,
            Appearance appearance,
            String publicUrl,
            boolean showBranding) {
    }

    /**
     * Contexto de una cotización ya emitida: datos de la versión usada (inmutable) y del negocio.
     *
     * @param available si el cotizador sigue publicado hoy
     */
    public record QuoteContext(String tenantSlug, String country, ZoneId zone, boolean tenantActive, boolean available,
                               String publicUrl, CalculatorConfig config, Appearance appearance, boolean showBranding) {
    }

    @Transactional(readOnly = true)
    public Optional<QuoteContext> context(UUID tenantId, UUID calculatorId, UUID versionId) {
        Optional<Tenant> tenant = tenants.find(tenantId);
        Optional<Calculator> calculator = calculators.findByIdAndTenantId(calculatorId, tenantId);
        Optional<CalculatorVersion> version = versions.findById(versionId).filter(v -> v.getTenantId().equals(tenantId));
        if (tenant.isEmpty() || calculator.isEmpty() || version.isEmpty()) {
            return Optional.empty();
        }
        Tenant t = tenant.get();
        Calculator c = calculator.get();
        com.climind.saas.subscriptions.Entitlements plan = entitlements.forTenant(tenantId);
        return Optional.of(new QuoteContext(t.getSlug(), t.getCountry(), ZoneId.of(t.getTimezone()), t.isActive(),
                c.getStatus() == Calculator.Status.PUBLICADO,
                properties.publicUrlWithoutTrailingSlash() + "/c/" + t.getSlug() + "/" + c.getSlug(),
                json.parse(version.get().getConfig()),
                branded(json.read(version.get().getAppearance(), Appearance.class), plan), !plan.customBranding()));
    }

    @Transactional(readOnly = true)
    public Optional<PublishedCalculator> find(String tenantSlug, String calculatorSlug) {
        if (calculatorSlug == null || calculatorSlug.length() > 60) {
            return Optional.empty();
        }
        Optional<Tenant> tenant = tenants.findActiveBySlug(tenantSlug);
        if (tenant.isEmpty()) {
            return Optional.empty();
        }
        Tenant t = tenant.get();
        return calculators.findByTenantIdAndSlug(t.getId(), calculatorSlug.toLowerCase(java.util.Locale.ROOT))
                .filter(c -> c.getStatus() == Calculator.Status.PUBLICADO || c.getStatus() == Calculator.Status.PAUSADO)
                .flatMap(c -> versions.findById(c.getPublishedVersionId()).map(v -> {
                    com.climind.saas.subscriptions.Entitlements plan = entitlements.forTenant(t.getId());
                    boolean available = c.getStatus() == Calculator.Status.PUBLICADO && withinPlan(t.getId(), c.getId(), plan);
                    return new PublishedCalculator(t.getId(), t.getSlug(), t.getCountry(), ZoneId.of(t.getTimezone()),
                            c.getId(), c.getSlug(), available, v.getId(), v.getVersionNo(), v.getName(),
                            v.getDescription(), json.parse(v.getConfig()),
                            branded(json.read(v.getAppearance(), Appearance.class), plan),
                            properties.publicUrlWithoutTrailingSlash() + "/c/" + t.getSlug() + "/" + c.getSlug(),
                            !plan.customBranding());
                }));
    }

    /**
     * Si el negocio tiene más cotizadores publicados que los que permite su plan (por ejemplo, después de que
     * venció un plan pago), quedan disponibles los más antiguos y el resto se muestra como no disponible. No se
     * borra ni se despublica nada: el dueño elige cuáles pausar o vuelve a un plan con más cotizadores.
     */
    private boolean withinPlan(UUID tenantId, UUID calculatorId, com.climind.saas.subscriptions.Entitlements plan) {
        return calculators.findIdsByStatusOrdered(tenantId, Calculator.Status.PUBLICADO,
                org.springframework.data.domain.PageRequest.of(0, plan.maxPublishedCalculators())).contains(calculatorId);
    }

    /** El color propio es parte de la personalización visual de los planes pagos. */
    private static Appearance branded(Appearance appearance, com.climind.saas.subscriptions.Entitlements plan) {
        return plan.customBranding() ? appearance : new Appearance(Appearance.DEFAULT_ACCENT, appearance.showLogo());
    }
}
