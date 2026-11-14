package com.climind.saas.businesses;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Datos públicos y de contacto del negocio. Una fila por tenant (la PK es el tenant). */
@Entity
@Table(name = "business_profiles")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusinessProfile {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID tenantId;

    @Column(name = "business_name", nullable = false, length = 120)
    private String businessName;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private Industry industry;

    @Column(length = 500)
    private String description;

    @Column(length = 30)
    private String phone;

    /** Solo dígitos, con código de país (formato wa.me), p. ej. 5491123456789. */
    @Column(length = 15)
    private String whatsapp;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @Column(length = 200)
    private String website;

    @Column(name = "logo_asset_id")
    private UUID logoAssetId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public BusinessProfile(UUID tenantId, String businessName) {
        this.tenantId = tenantId;
        this.businessName = businessName;
    }
}
