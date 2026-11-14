package com.climind.saas.subscriptions;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByProviderAndExternalId(TenantSubscription.Provider provider, String externalId);

    List<PaymentTransaction> findTop24ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Page<PaymentTransaction> findByStatusIn(Collection<PaymentTransaction.Status> statuses, Pageable pageable);
}
