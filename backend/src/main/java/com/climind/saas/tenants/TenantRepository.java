package com.climind.saas.tenants;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repositorio de la tabla raíz de tenants. No es un recurso "dentro" de un tenant: el acceso desde
 * controllers privados pasa siempre por {@link CurrentTenant}, que resuelve el id desde la sesión.
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    boolean existsBySlug(String slug);

    java.util.Optional<Tenant> findBySlug(String slug);
}
