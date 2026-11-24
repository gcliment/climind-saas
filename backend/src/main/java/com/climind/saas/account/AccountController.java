package com.climind.saas.account;

import com.climind.saas.auth.AccountSettingsService;
import com.climind.saas.shared.security.AppPrincipal;
import com.climind.saas.shared.security.CurrentUser;
import com.climind.saas.tenants.CurrentTenant;
import com.climind.saas.account.AccountOverviewService.AccountView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Cuenta del usuario que ingresó: datos personales, contraseña, emails de producto y, para el dueño del negocio,
 * la exportación de todos los datos y la baja de la cuenta.
 */
@Tag(name = "Cuenta", description = "Datos de la cuenta, exportación y baja")
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountSettingsService settings;
    private final AccountExportService exports;
    private final AccountDeletionService deletions;
    private final CurrentTenant currentTenant;
    private final AccountOverviewService overview;

    public AccountController(AccountSettingsService settings, AccountExportService exports,
                             AccountDeletionService deletions, CurrentTenant currentTenant,
                             AccountOverviewService overview) {
        this.settings = settings;
        this.exports = exports;
        this.deletions = deletions;
        this.currentTenant = currentTenant;
        this.overview = overview;
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "Ingresá tu nombre.")
            @Size(max = 80, message = "El nombre admite hasta 80 caracteres.")
            String firstName,
            /** Opcional, como en el registro. */
            @Size(max = 80, message = "El apellido admite hasta 80 caracteres.")
            String lastName) {
    }

    public record EmailPreferencesRequest(@NotNull Boolean productEmails) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Ingresá tu contraseña actual.") @Size(max = 200) String currentPassword,
            @NotBlank(message = "Ingresá la contraseña nueva.") @Size(max = 200) String password,
            @NotBlank(message = "Repetí la contraseña nueva.") @Size(max = 200) String passwordConfirmation) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }

    public record DeletionRequest(
            @NotBlank(message = "Ingresá tu contraseña.") @Size(max = 200) String password,
            @NotBlank(message = "Escribí ELIMINAR para confirmar la baja.") @Size(max = 20) String confirmation) {

        @Override
        public String toString() {
            return "DeletionRequest[***]";
        }
    }

    @Operation(summary = "Datos de la cuenta")
    @GetMapping
    public AccountView get() {
        return view(CurrentUser.require());
    }

    @Operation(summary = "Actualizar nombre y apellido")
    @PutMapping("/profile")
    public AccountView updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        AppPrincipal principal = CurrentUser.require();
        settings.rename(principal.userId(), request.firstName(), request.lastName());
        return view(principal);
    }

    @Operation(summary = "Aceptar o rechazar emails de producto (consejos y resumen mensual)")
    @PutMapping("/email-preferences")
    public AccountView emailPreferences(@Valid @RequestBody EmailPreferencesRequest request) {
        AppPrincipal principal = CurrentUser.require();
        settings.setProductEmails(principal.userId(), request.productEmails());
        return view(principal);
    }

    @Operation(summary = "Cambiar la contraseña (cierra las demás sesiones)")
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest http) {
        var session = http.getSession(false);
        settings.changePassword(CurrentUser.require().userId(), request.currentPassword(), request.password(),
                request.passwordConfirmation(), session == null ? null : session.getId());
    }

    @Operation(summary = "Descargar todos los datos del negocio (ZIP con JSON)")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        UUID tenantId = currentTenant.requireId();
        AccountExportService.Export export = exports.export(tenantId, CurrentUser.require().userId());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.fileName()).build().toString())
                .body(export.content());
    }

    @Operation(summary = "Pedir la baja de la cuenta (se ejecuta después del plazo para arrepentirse)")
    @PostMapping("/deletion")
    public AccountDeletionService.DeletionStatus scheduleDeletion(@Valid @RequestBody DeletionRequest request) {
        UUID tenantId = currentTenant.requireId();
        return deletions.schedule(tenantId, CurrentUser.require().userId(), request.password(),
                request.confirmation());
    }

    @Operation(summary = "Cancelar la baja programada")
    @DeleteMapping("/deletion")
    public AccountView cancelDeletion() {
        AppPrincipal principal = CurrentUser.require();
        deletions.cancel(currentTenant.requireId(), principal.userId());
        return view(principal);
    }

    private AccountView view(AppPrincipal principal) {
        return overview.view(principal);
    }
}
