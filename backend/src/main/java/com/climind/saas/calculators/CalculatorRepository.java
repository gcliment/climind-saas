package com.climind.saas.calculators;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Toda consulta está acotada por {@code tenantId} (que viene de CurrentTenant, nunca del cliente). */
public interface CalculatorRepository extends JpaRepository<Calculator, UUID> {

    Optional<Calculator> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Calculator> findByTenantIdAndStatusInOrderByUpdatedAtDesc(UUID tenantId, Collection<Calculator.Status> statuses);

    boolean existsByTenantIdAndSlug(UUID tenantId, String slug);

    boolean existsByTenantIdAndSlugAndIdNot(UUID tenantId, String slug, UUID id);

    long countByTenantIdAndStatus(UUID tenantId, Calculator.Status status);

    Optional<Calculator> findByTenantIdAndSlug(UUID tenantId, String slug);

    /** Publicados en orden de antigüedad: si el plan permite menos, quedan visibles los más antiguos. */
    @org.springframework.data.jpa.repository.Query("select c.id from Calculator c where c.tenantId = :tenantId and c.status = :status order by c.publishedAt asc, c.id asc")
    List<UUID> findIdsByStatusOrdered(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                                      @org.springframework.data.repository.query.Param("status") Calculator.Status status,
                                      org.springframework.data.domain.Pageable pageable);
}
