package com.climind.saas.auth;

import com.climind.saas.shared.persistence.AssignedIdEntity;
import com.climind.saas.shared.util.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends AssignedIdEntity {

    public enum Status { ACTIVE, DISABLED }

    @Column(nullable = false, length = 254, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    /** SUPER_ADMIN o null. */
    @Column(name = "platform_role", length = 20)
    private String platformRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    /** Aceptación de términos y política de privacidad al registrarse. */
    @Column(name = "terms_accepted_at", nullable = false, updatable = false)
    private Instant termsAcceptedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public User(String email, String passwordHash, String firstName, String lastName, Instant now) {
        super(Ids.newId());
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.firstName = firstName.strip();
        this.lastName = lastName.strip();
        this.passwordChangedAt = now;
        this.termsAcceptedAt = now;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
        }
    }

    public void promoteToSuperAdmin() {
        this.platformRole = "SUPER_ADMIN";
    }

    /**
     * Registra un intento fallido. Al llegar a {@code maxFailures} bloquea la cuenta; cada bloqueo
     * posterior dura el doble (máximo 24 h).
     *
     * @return true si este intento provocó un bloqueo
     */
    public boolean registerFailedLogin(Instant now, int maxFailures, Duration baseLock) {
        failedLoginCount++;
        if (failedLoginCount >= maxFailures && failedLoginCount % maxFailures == 0) {
            int lockNumber = failedLoginCount / maxFailures;
            long multiplier = 1L << Math.min(lockNumber - 1, 10);
            Duration lock = baseLock.multipliedBy(multiplier);
            if (lock.compareTo(Duration.ofHours(24)) > 0) {
                lock = Duration.ofHours(24);
            }
            lockedUntil = now.plus(lock);
            return true;
        }
        return false;
    }

    public void registerSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public void changePassword(String newHash, Instant now) {
        this.passwordHash = newHash;
        this.passwordChangedAt = now;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }
}
