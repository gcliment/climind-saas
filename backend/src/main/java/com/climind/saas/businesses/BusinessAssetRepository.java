package com.climind.saas.businesses;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BusinessAssetRepository extends JpaRepository<BusinessAsset, UUID> {

    Optional<BusinessAsset> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Lectura pública de logos (se muestran en cotizadores públicos). Solo el tipo LOGO. */
    Optional<BusinessAsset> findByIdAndKind(UUID id, String kind);
}
