package com.climind.saas.businesses;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Negocio", description = "Perfil del negocio autenticado")
@RestController
public class BusinessController {

    private final BusinessService service;
    private final BusinessLogoService logos;

    public BusinessController(BusinessService service, BusinessLogoService logos) {
        this.service = service;
        this.logos = logos;
    }

    @Operation(summary = "Perfil del negocio del usuario autenticado")
    @GetMapping("/api/v1/business/profile")
    public BusinessDtos.BusinessProfileResponse profile() {
        return service.getProfile();
    }

    @Operation(summary = "Actualizar el perfil del negocio")
    @PutMapping("/api/v1/business/profile")
    public BusinessDtos.BusinessProfileResponse update(@Valid @RequestBody BusinessDtos.UpdateBusinessProfileRequest request) {
        return service.updateProfile(request);
    }

    @Operation(summary = "Preferencias del negocio (avisos por email y retención de datos de clientes)")
    @GetMapping("/api/v1/business/settings")
    public BusinessDtos.BusinessSettingsResponse settings() {
        return service.getSettings();
    }

    @Operation(summary = "Actualizar preferencias del negocio")
    @PutMapping("/api/v1/business/settings")
    public BusinessDtos.BusinessSettingsResponse updateSettings(
            @Valid @RequestBody BusinessDtos.UpdateBusinessSettingsRequest request) {
        return service.updateSettings(request);
    }

    @Operation(summary = "Subir o reemplazar el logo (PNG, JPG o WebP, hasta 2 MB)")
    @PostMapping(value = "/api/v1/business/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BusinessDtos.LogoResponse uploadLogo(@RequestPart("file") MultipartFile file) {
        return new BusinessDtos.LogoResponse(BusinessProfileMapper.ASSET_URL + logos.upload(file));
    }

    @Operation(summary = "Quitar el logo")
    @DeleteMapping("/api/v1/business/logo")
    public ResponseEntity<Void> removeLogo() {
        logos.remove();
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Marcar el onboarding como terminado (el enlace público queda fijo)")
    @PostMapping("/api/v1/business/onboarding/complete")
    public BusinessDtos.BusinessProfileResponse completeOnboarding() {
        return service.completeOnboarding();
    }

    @Operation(summary = "Rubros disponibles")
    @Tag(name = "Catálogo")
    @GetMapping("/api/v1/catalog/industries")
    public List<BusinessDtos.IndustryOption> industries() {
        return service.industries();
    }
}
