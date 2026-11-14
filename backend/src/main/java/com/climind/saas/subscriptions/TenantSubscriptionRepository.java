package com.climind.saas.subscriptions;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Las consultas privadas van acotadas por tenantId (de CurrentTenant); las de proveedor, por id externo. */
public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, UUID> {

    List<TenantSubscription> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<TenantSubscription> findByTenantIdAndStatusIn(UUID tenantId, Collection<TenantSubscription.Status> statuses);

    Optional<TenantSubscription> findByProviderAndExternalId(TenantSubscription.Provider provider, String externalId);

    List<TenantSubscription> findByStatusIn(Collection<TenantSubscription.Status> statuses);

    List<TenantSubscription> findByStatusAndGraceUntilBefore(TenantSubscription.Status status, Instant before);

    List<TenantSubscription> findByStatusAndCreatedAtBefore(TenantSubscription.Status status, Instant before);

    Page<TenantSubscription> findByStatusIn(Collection<TenantSubscription.Status> statuses, Pageable pageable);
}
