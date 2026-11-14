package com.climind.saas.tenants;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    List<TenantMembership> findByUserIdOrderByCreatedAtAsc(UUID userId);

    List<TenantMembership> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);
}
