package com.climind.saas.businesses;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** PK = tenant_id: {@code findById(tenantId)} está acotado al negocio por construcción. */
public interface BusinessSettingsRepository extends JpaRepository<BusinessSettings, UUID> {
}
