package com.climind.saas.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken extends OneTimeToken {

    protected PasswordResetToken() {
    }

    public PasswordResetToken(UUID userId, String tokenHash, Instant now, Instant expiresAt) {
        super(userId, tokenHash, now, expiresAt);
    }
}
