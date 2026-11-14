package com.climind.saas.businesses;

import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.security.CurrentUser;
import com.climind.saas.shared.util.Ids;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.storage.FileStorage;
import com.climind.saas.storage.ImageType;
import com.climind.saas.tenants.CurrentTenant;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Logotipo del negocio: validación del archivo, almacenamiento y lectura pública. */
@Service
public class BusinessLogoService {

    private final BusinessAssetRepository assets;
    private final BusinessProfileRepository profiles;
    private final FileStorage storage;
    private final CurrentTenant currentTenant;
    private final AuditLogService audit;
    private final int maxBytes;

    public BusinessLogoService(BusinessAssetRepository assets, BusinessProfileRepository profiles, FileStorage storage,
                               CurrentTenant currentTenant, AuditLogService audit, AppProperties properties) {
        this.assets = assets;
        this.profiles = profiles;
        this.storage = storage;
        this.currentTenant = currentTenant;
        this.audit = audit;
        this.maxBytes = properties.getStorage().getMaxLogoBytes();
    }

    public record StoredFile(Resource resource, String contentType) {
    }

    @Transactional
    public UUID upload(MultipartFile file) {
        UUID tenantId = currentTenant.requireId();
        if (file == null || file.isEmpty()) {
            throw ApiException.field("file", "Elegí una imagen.");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                    "El logo puede pesar hasta " + (maxBytes / 1024 / 1024) + " MB.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("UPLOAD_FAILED", "No pudimos leer el archivo. Probá de nuevo.");
        }
        ImageType type = ImageType.detect(content).orElseThrow(() -> ApiException.field("file",
                "El logo tiene que ser una imagen PNG, JPG o WebP."));

        UUID assetId = Ids.newId();
        String key = "tenants/" + tenantId + "/logo-" + assetId + "." + type.extension();
        storage.put(key, content);
        assets.save(new BusinessAsset(assetId, tenantId, BusinessAsset.KIND_LOGO, key, type.contentType(),
                content.length));

        BusinessProfile profile = profiles.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("No encontramos el perfil de tu negocio."));
        UUID previous = profile.getLogoAssetId();
        profile.setLogoAssetId(assetId);
        // Si la transacción falla, el archivo nuevo no queda huérfano.
        afterCompletion(committed -> {
            if (!committed) {
                storage.delete(key);
            }
        });
        if (previous != null) {
            removeAsset(tenantId, previous);
        }
        audit.record(AuditAction.BUSINESS_LOGO_UPDATED, CurrentUser.require().userId(), tenantId, "business_asset",
                assetId.toString(), Map.of("contentType", type.contentType(), "bytes", content.length));
        return assetId;
    }

    @Transactional
    public void remove() {
        UUID tenantId = currentTenant.requireId();
        profiles.findById(tenantId).ifPresent(profile -> {
            UUID previous = profile.getLogoAssetId();
            profile.setLogoAssetId(null);
            if (previous != null) {
                removeAsset(tenantId, previous);
                audit.record(AuditAction.BUSINESS_LOGO_UPDATED, CurrentUser.require().userId(), tenantId,
                        "business_asset", previous.toString(), Map.of("removed", true));
            }
        });
    }

    /** Lectura pública de un logo por id (UUID no adivinable). */
    @Transactional(readOnly = true)
    public Optional<StoredFile> publicLogo(UUID assetId) {
        return assets.findByIdAndKind(assetId, BusinessAsset.KIND_LOGO)
                .flatMap(a -> storage.get(a.getStorageKey()).map(r -> new StoredFile(r, a.getContentType())));
    }

    private void removeAsset(UUID tenantId, UUID assetId) {
        assets.findByIdAndTenantId(assetId, tenantId).ifPresent(asset -> {
            String key = asset.getStorageKey();
            assets.delete(asset);
            afterCompletion(committed -> {
                if (committed) {
                    storage.delete(key);
                }
            });
        });
    }

    private static void afterCompletion(java.util.function.Consumer<Boolean> action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                action.accept(status == STATUS_COMMITTED);
            }
        });
    }
}
