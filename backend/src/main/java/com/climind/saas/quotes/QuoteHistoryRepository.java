package com.climind.saas.quotes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuoteHistoryRepository extends JpaRepository<QuoteHistoryEntry, UUID> {

    List<QuoteHistoryEntry> findByRequestIdAndTenantIdOrderByCreatedAtAscIdAsc(UUID requestId, UUID tenantId);
}
