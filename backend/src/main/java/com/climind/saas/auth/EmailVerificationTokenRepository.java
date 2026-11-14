package com.climind.saas.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /** Invalida los tokens pendientes del usuario (solo vale el último emitido). */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update EmailVerificationToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int invalidateAll(@Param("userId") UUID userId, @Param("now") Instant now);
}
