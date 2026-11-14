package com.climind.saas.businesses;

import com.climind.saas.shared.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Referencia a un archivo del negocio en el almacenamiento externo (el binario NO está en la base). */
@Entity
@Table(name = "business_assets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusinessAsset extends AssignedIdEntity {

    public static final String KIND_LOGO = "LOGO";

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 20, updatable = false)
    private String kind;

    @Column(name = "storage_key", nullable = false, length = 200, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 50, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public BusinessAsset(UUID id, UUID tenantId, String kind, String storageKey, String contentType, int sizeBytes) {
        super(id);
        this.tenantId = tenantId;
        this.kind = kind;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
    }
}
