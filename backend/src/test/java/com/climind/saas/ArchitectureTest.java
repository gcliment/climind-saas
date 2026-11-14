package com.climind.saas;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Reglas de arquitectura del monolito modular. */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.climind.saas");
    }

    @Test
    void losModulosNoTienenDependenciasCirculares() {
        slices().matching("com.climind.saas.(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    void sharedNoDependeDeModulosDeNegocio() {
        noClasses().that().resideInAPackage("com.climind.saas.shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.climind.saas.auth..", "com.climind.saas.tenants..", "com.climind.saas.businesses..",
                        "com.climind.saas.notifications..", "com.climind.saas.administration..",
                        "com.climind.saas.calculators..", "com.climind.saas.quotes..", "com.climind.saas.analytics..")
                .check(classes);
    }

    /** El motor de precios es puro: testeable sin contexto y reutilizable por cualquier módulo. */
    @Test
    void elMotorDePreciosNoDependeDeFrameworksNiDeOtrosModulos() {
        noClasses().that().resideInAPackage("com.climind.saas.pricing..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "org.hibernate..", "tools.jackson..",
                        "com.climind.saas.shared..", "com.climind.saas.calculators..", "com.climind.saas.tenants..",
                        "com.climind.saas.quotes..")
                .check(classes);
    }

    /**
     * Los controllers no acceden a repositorios: pasan por servicios, que resuelven el negocio (tenant)
     * desde la sesión y aplican la autorización por recurso.
     */
    @Test
    void losControllersNoUsanRepositoriosDirectamente() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAssignableTo(Repository.class)
                .check(classes);
    }

    /** Las entidades JPA no se exponen por la API: los controllers devuelven DTOs. */
    @Test
    void losControllersNoDevuelvenEntidades() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
                .check(classes);
    }
}
