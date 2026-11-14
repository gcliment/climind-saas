package com.climind.saas.quotes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface QuoteSimulationRepository extends JpaRepository<QuoteSimulation, UUID> {

    /** Acceso público por código (además hay que verificar el token de acceso). */
    Optional<QuoteSimulation> findByCode(String code);

    boolean existsByCode(String code);

    Optional<QuoteSimulation> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Misma cotización del mismo visitante en la ventana de deduplicación. */
    Optional<QuoteSimulation> findFirstByCalculatorVersionIdAndVisitorHashAndAnswersHashAndCreatedAtAfterOrderByCreatedAtDesc(
            UUID calculatorVersionId, String visitorHash, String answersHash, Instant after);
}
