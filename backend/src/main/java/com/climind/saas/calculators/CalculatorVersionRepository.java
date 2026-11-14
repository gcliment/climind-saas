package com.climind.saas.calculators;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CalculatorVersionRepository extends JpaRepository<CalculatorVersion, UUID> {

    List<CalculatorVersion> findByCalculatorIdAndTenantIdOrderByVersionNoDesc(UUID calculatorId, UUID tenantId);

    List<CalculatorVersion> findByIdInAndTenantId(Collection<UUID> ids, UUID tenantId);

    @Query("select coalesce(max(v.versionNo), 0) from CalculatorVersion v where v.calculatorId = :calculatorId")
    int maxVersionNo(@Param("calculatorId") UUID calculatorId);
}
