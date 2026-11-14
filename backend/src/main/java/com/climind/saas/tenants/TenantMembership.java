package com.climind.saas.tenants;

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

import java.time.Instant;
import java.util.UUID;

/** Pertenencia de un usuario a un negocio con un rol. */
@Entity
@Table(name = "tenant_memberships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantMembership extends AssignedIdEntity {

    public enum Role { OWNER, MEMBER }

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TenantMembership(UUID tenantId, UUID userId, Role role) {
        super(Ids.newId());
        this.tenantId = tenantId;
        this.userId = userId;
        this.role = role;
    }
}
