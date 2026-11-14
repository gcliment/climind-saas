package com.climind.saas.businesses;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Datos del negocio que se muestran a sus clientes (cotizador público, cotización, PDF) y preferencias que
 * usan otros módulos. Solo lo que el negocio cargó como información de contacto pública.
 */
@Service
public class PublicBusinesses {

    private final BusinessProfileRepository profiles;
    private final BusinessSettingsRepository settings;
    private final BusinessLogoService logos;
    private final JdbcTemplate jdbc;

    public PublicBusinesses(BusinessProfileRepository profiles, BusinessSettingsRepository settings,
                            BusinessLogoService logos, JdbcTemplate jdbc) {
        this.profiles = profiles;
        this.settings = settings;
        this.logos = logos;
        this.jdbc = jdbc;
    }

    public record PublicBusiness(String name, String description, UUID logoAssetId, String logoUrl, String whatsapp,
                                 String phone, String contactEmail, String website) {
    }

    @Transactional(readOnly = true)
    public Optional<PublicBusiness> find(UUID tenantId) {
        return profiles.findById(tenantId).map(p -> new PublicBusiness(p.getBusinessName(), p.getDescription(),
                p.getLogoAssetId(),
                p.getLogoAssetId() == null ? null : BusinessProfileMapper.ASSET_URL + p.getLogoAssetId(),
                p.getWhatsapp(), p.getPhone(), p.getContactEmail(), p.getWebsite()));
    }

    /** Logo del negocio para incrustar en documentos (PDF). */
    public Optional<BusinessLogoService.StoredFile> logo(UUID logoAssetId) {
        return logoAssetId == null ? Optional.empty() : logos.publicLogo(logoAssetId);
    }

    /**
     * A quién avisar de una consulta nueva: el email de contacto del negocio o, si no cargó uno, el del dueño.
     * Vacío si el negocio desactivó el aviso.
     */
    @Transactional(readOnly = true)
    public Optional<String> newLeadRecipient(UUID tenantId) {
        boolean enabled = settings.findById(tenantId).map(BusinessSettings::isNotifyNewLeadEmail).orElse(true);
        if (!enabled) {
            return Optional.empty();
        }
        Optional<String> contact = profiles.findById(tenantId).map(BusinessProfile::getContactEmail)
                .filter(e -> !e.isBlank());
        if (contact.isPresent()) {
            return contact;
        }
        return jdbc.query("""
                        SELECT u.email FROM tenant_memberships m JOIN users u ON u.id = m.user_id
                        WHERE m.tenant_id = ? AND m.role = 'OWNER' ORDER BY m.created_at LIMIT 1
                        """, (rs, i) -> rs.getString(1), tenantId).stream().findFirst();
    }

    /** Meses que se conservan los datos de contacto de las consultas antes de anonimizarlos. */
    @Transactional(readOnly = true)
    public int leadRetentionMonths(UUID tenantId) {
        return settings.findById(tenantId).map(BusinessSettings::getLeadRetentionMonths).orElse(24);
    }
}
