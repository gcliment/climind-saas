package com.climind.saas.businesses;

import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.security.CurrentUser;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.tenants.CurrentTenant;
import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Perfil y configuración del negocio del usuario autenticado. */
@Service
public class BusinessService {

    private final BusinessProfileRepository profiles;
    private final BusinessSettingsRepository settings;
    private final CurrentTenant currentTenant;
    private final TenantService tenantService;
    private final BusinessProfileMapper mapper;
    private final AuditLogService audit;

    public BusinessService(BusinessProfileRepository profiles, BusinessSettingsRepository settings,
                           CurrentTenant currentTenant, TenantService tenantService, BusinessProfileMapper mapper,
                           AuditLogService audit) {
        this.profiles = profiles;
        this.settings = settings;
        this.currentTenant = currentTenant;
        this.tenantService = tenantService;
        this.mapper = mapper;
        this.audit = audit;
    }

    /** Crea perfil y configuración por defecto para un negocio nuevo (se llama desde el registro). */
    @Transactional
    public void initialize(UUID tenantId, String businessName, String contactEmail) {
        BusinessProfile profile = new BusinessProfile(tenantId, businessName);
        profile.setContactEmail(contactEmail);
        profiles.save(profile);
        settings.save(new BusinessSettings(tenantId));
    }

    /** Perfil del negocio ficticio de las demos públicas (sin datos de contacto: nadie responde consultas). */
    @Transactional
    public void initializeDemo(UUID tenantId, String businessName, String description) {
        if (profiles.existsById(tenantId)) {
            return;
        }
        BusinessProfile profile = new BusinessProfile(tenantId, businessName);
        profile.setDescription(description);
        profiles.save(profile);
        settings.save(new BusinessSettings(tenantId));
    }

    @Transactional(readOnly = true)
    public BusinessDtos.BusinessProfileResponse getProfile() {
        UUID tenantId = currentTenant.requireId();
        return toResponse(load(tenantId), tenantId);
    }

    @Transactional
    public BusinessDtos.BusinessProfileResponse updateProfile(BusinessDtos.UpdateBusinessProfileRequest request) {
        UUID tenantId = currentTenant.requireId();
        BusinessProfile profile = load(tenantId);
        profile.setBusinessName(request.businessName().strip());
        profile.setIndustry(parseIndustry(request.industry()));
        profile.setDescription(blankToNull(request.description()));
        profile.setPhone(blankToNull(request.phone()));
        profile.setWhatsapp(normalizeWhatsapp(request.whatsapp()));
        profile.setContactEmail(blankToNull(request.contactEmail()) == null ? null
                : request.contactEmail().strip().toLowerCase(java.util.Locale.ROOT));
        profile.setWebsite(normalizeWebsite(request.website()));
        tenantService.rename(tenantId, profile.getBusinessName());
        audit.record(AuditAction.BUSINESS_PROFILE_UPDATED, CurrentUser.require().userId(), tenantId,
                "business_profile", tenantId.toString(), Map.of());
        return toResponse(profile, tenantId);
    }

    public List<BusinessDtos.IndustryOption> industries() {
        return Arrays.stream(Industry.values())
                .map(i -> new BusinessDtos.IndustryOption(i.name(), i.label()))
                .toList();
    }

    @Transactional(readOnly = true)
    public BusinessDtos.BusinessSettingsResponse getSettings() {
        BusinessSettings current = loadSettings(currentTenant.requireId());
        return new BusinessDtos.BusinessSettingsResponse(current.isNotifyNewLeadEmail(), current.getLeadRetentionMonths());
    }

    @Transactional
    public BusinessDtos.BusinessSettingsResponse updateSettings(BusinessDtos.UpdateBusinessSettingsRequest request) {
        UUID tenantId = currentTenant.requireId();
        BusinessSettings current = loadSettings(tenantId);
        current.setNotifyNewLeadEmail(request.notifyNewLeadEmail());
        current.setLeadRetentionMonths(request.leadRetentionMonths());
        audit.record(AuditAction.BUSINESS_SETTINGS_UPDATED, CurrentUser.require().userId(), tenantId,
                "business_settings", tenantId.toString(), Map.of("notifyNewLeadEmail", request.notifyNewLeadEmail(),
                        "leadRetentionMonths", request.leadRetentionMonths()));
        return new BusinessDtos.BusinessSettingsResponse(current.isNotifyNewLeadEmail(), current.getLeadRetentionMonths());
    }

    private BusinessSettings loadSettings(UUID tenantId) {
        return settings.findById(tenantId).orElseGet(() -> settings.save(new BusinessSettings(tenantId)));
    }

    private BusinessProfile load(UUID tenantId) {
        return profiles.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("No encontramos el perfil de tu negocio."));
    }

    /** Fin del onboarding (publicó o eligió terminar): desde acá el enlace público del negocio queda fijo. */
    @Transactional
    public BusinessDtos.BusinessProfileResponse completeOnboarding() {
        UUID tenantId = currentTenant.requireId();
        if (tenantService.markOnboardingCompleted(tenantId)) {
            audit.record(AuditAction.ONBOARDING_COMPLETED, CurrentUser.require().userId(), tenantId);
        }
        return toResponse(load(tenantId), tenantId);
    }

    private BusinessDtos.BusinessProfileResponse toResponse(BusinessProfile profile, UUID tenantId) {
        Tenant tenant = tenantService.find(tenantId).orElse(null);
        return mapper.toResponse(profile, tenant == null ? null : tenant.getSlug(),
                tenant != null && tenant.getOnboardingCompletedAt() != null);
    }

    static Industry parseIndustry(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return Industry.valueOf(code.strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.field("industry", "Elegí un rubro de la lista.");
        }
    }

    /** "+54 9 11 2345-6789" → "5491123456789" (formato wa.me). */
    static String normalizeWhatsapp(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 10 || digits.length() > 15) {
            throw ApiException.field("whatsapp",
                    "Ingresá el WhatsApp con código de país y área, por ejemplo +54 9 11 2345-6789.");
        }
        return digits;
    }

    static String normalizeWebsite(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        if (!value.matches("(?i)^https?://.*")) {
            value = "https://" + value;
        }
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (uri.getHost() == null || !uri.getHost().contains(".")) {
                throw new IllegalArgumentException("host");
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.field("website", "Ingresá una dirección web válida, por ejemplo minegocio.com.ar.");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
