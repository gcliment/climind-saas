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

    @Transactional(readOnly = true)
    public Optional<Tenant> find(UUID tenantId) {
        return tenants.findById(tenantId);
    }

    /** Negocio activo por su dirección pública (los suspendidos no existen para el público). */
    @Transactional(readOnly = true)
    public Optional<Tenant> findActiveBySlug(String slug) {
        if (slug == null || slug.length() > 60) {
            return Optional.empty();
        }
        return tenants.findBySlug(slug.toLowerCase(java.util.Locale.ROOT)).filter(Tenant::isActive);
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
