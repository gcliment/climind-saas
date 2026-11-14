package com.climind.saas.quotes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/** Las búsquedas con filtros usan especificaciones que SIEMPRE incluyen el tenant (ver LeadService). */
public interface QuoteRequestRepository extends JpaRepository<QuoteRequest, UUID>, JpaSpecificationExecutor<QuoteRequest> {

    Optional<QuoteRequest> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<QuoteRequest> findBySimulationId(UUID simulationId);
}
