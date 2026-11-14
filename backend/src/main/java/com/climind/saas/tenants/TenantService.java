package com.climind.saas.tenants;

import com.climind.saas.shared.util.Slugs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Creación y consulta de espacios de trabajo (tenants) y membresías. */
@Service
public class TenantService {

    private final TenantRepository tenants;
    private final TenantMembershipRepository memberships;

    public TenantService(TenantRepository tenants, TenantMembershipRepository memberships) {
        this.tenants = tenants;
        this.memberships = memberships;
    }

    /** Crea el negocio y deja al usuario como OWNER. Debe ejecutarse dentro de la transacción del registro. */
    @Transactional
    public Tenant createWorkspace(UUID ownerUserId, String name) {
        Tenant tenant = tenants.save(new Tenant(name, uniqueSlug(name)));
        memberships.save(new TenantMembership(tenant.getId(), ownerUserId, TenantMembership.Role.OWNER));
        return tenant;
    }

    /** Membresía principal del usuario (MVP: un negocio por usuario; la más antigua). */
    @Transactional(readOnly = true)
    public Optional<TenantMembership> primaryMembership(UUID userId) {
        return memberships.findByUserIdOrderByCreatedAtAsc(userId).stream().findFirst();
    }

    /**
     * Mantiene el nombre del tenant sincronizado con el nombre comercial del perfil.
     * Mientras el onboarding no terminó (todavía no hay enlaces compartidos), el slug público acompaña al
     * nombre; después queda fijo para no romper enlaces ya distribuidos.
     */
    @Transactional
    public void rename(UUID tenantId, String name) {
        tenants.findById(tenantId).ifPresent(t -> {
            boolean nameChanged = !t.getName().equals(name);
            t.setName(name);
            if (nameChanged && t.getOnboardingCompletedAt() == null) {
                t.setSlug(uniqueSlug(name));
            }
        });
    }

    /**
     * Marca el onboarding como completo (fija el slug público). Idempotente.
     *
     * @return true si se marcó ahora; false si ya estaba completo
     */
    @Transactional
    public boolean markOnboardingCompleted(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        if (tenant.getOnboardingCompletedAt() != null) {
            return false;
        }
        tenant.setOnboardingCompletedAt(java.time.Instant.now());
        return true;
    }

    /** Suspensión administrativa: el negocio no accede al panel y sus cotizadores dejan de ser públicos. */
    @Transactional
    public Tenant suspend(UUID tenantId, String reason, java.time.Instant at) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        tenant.suspend(reason, at);
        return tenant;
    }

    @Transactional
    public Tenant reactivate(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        tenant.reactivate();
        return tenant;
    }

    /** Integrantes del negocio (MVP: solo el dueño). */
    @Transactional(readOnly = true)
    public java.util.List<TenantMembership> members(UUID tenantId) {
        return memberships.findByTenantIdOrderByCreatedAtAsc(tenantId);
    }

    @Transactional
    public Tenant scheduleDeletion(UUID tenantId, UUID requestedBy, java.time.Instant now, java.time.Instant scheduledFor) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        tenant.scheduleDeletion(requestedBy, now, scheduledFor);
        return tenants.saveAndFlush(tenant);
    }

    @Transactional
    public Tenant cancelDeletion(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        tenant.cancelDeletion();
        return tenants.saveAndFlush(tenant);
    }

    /** Negocios con la baja vencida que todavía no se ejecutó. */
    @Transactional(readOnly = true)
    public java.util.List<UUID> deletionsDue(java.time.Instant now) {
        return tenants.findDeletionsDue(now);
    }

    /** Cierra la baja: el registro del negocio queda anonimizado (lo referencian pagos y auditoría). */
    @Transactional
    public void markDeleted(UUID tenantId, java.time.Instant now) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        String suffix = tenantId.toString().replace("-", "").substring(0, 16);
        tenant.markDeleted("eliminado-" + suffix, now);
        tenants.saveAndFlush(tenant);
        memberships.deleteAll(memberships.findByTenantIdOrderByCreatedAtAsc(tenantId));
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> find(UUID tenantId) {
        return tenants.findById(tenantId);
    }

    /**
     * Negocio visible por su dirección pública. Los suspendidos, eliminados o con una baja en curso no existen
     * para el público.
     */
    @Transactional(readOnly = true)
    public Optional<Tenant> findActiveBySlug(String slug) {
        if (slug == null || slug.length() > 60) {
            return Optional.empty();
        }
        return tenants.findBySlug(slug.toLowerCase(java.util.Locale.ROOT)).filter(Tenant::isPubliclyVisible);
    }

    /** Negocio de las demos públicas por su dirección (solo si está marcado como demo). */
    @Transactional(readOnly = true)
    public Optional<Tenant> findDemo(String slug) {
        return tenants.findBySlug(slug).filter(Tenant::isDemo);
    }

    /** Crea el negocio de demostración (sin dueño: nadie puede ingresar a su panel). */
    @Transactional
    public Tenant createDemo(String name, String slug) {
        Tenant tenant = Tenant.demo(name, slug);
        tenant.setOnboardingCompletedAt(java.time.Instant.now());
        return tenants.save(tenant);
    }

    /**
     * Slug provisorio a partir del nombre + sufijo aleatorio. El negocio lo puede personalizar en el
     * onboarding; el sufijo evita colisiones y que se puedan enumerar negocios por nombre.
     */
    String uniqueSlug(String name) {
        String base = Slugs.slugify(name, 40);
        if (base.length() < 2) {
            base = "negocio";
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = base + "-" + Slugs.randomSuffix(5);
            if (!tenants.existsBySlug(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No se pudo generar un slug único");
    }
}
