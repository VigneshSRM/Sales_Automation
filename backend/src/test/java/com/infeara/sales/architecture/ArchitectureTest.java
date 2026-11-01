package com.infeara.sales.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Layering rules from the architecture decisions (ADR-001/003). These fail the build when a
 * shortcut would bypass the service layer — for example a controller touching a repository, or a
 * domain class depending on the web tier.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.infeara.sales");
    }

    @Test
    @DisplayName("controllers never talk to repositories directly")
    void controllersOnlyUseServices() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
        rule.check(classes);
    }

    @Test
    @DisplayName("domain code does not depend on the web or security tiers")
    void domainIsIndependent() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..crm..", "..sales..", "..engagement..")
                .and().haveSimpleNameNotEndingWith("Controller")
                .should().dependOnClassesThat().resideInAnyPackage("..web..", "org.springframework.web..");
        rule.check(classes);
    }

    @Test
    @DisplayName("entities live in the module packages, not in a shared 'entity' package")
    void entitiesAreOwnedByTheirModule() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideOutsideOfPackages("..entity..", "java..");
        rule.check(classes);
    }

    @Test
    @DisplayName("constructor injection only — no field injection in beans")
    void noFieldInjection() {
        // Fields declared in *Service classes must not carry @Autowired: constructor injection only.
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Service")
                .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired");
        rule.check(classes);
    }
}
