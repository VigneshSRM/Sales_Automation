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
    @DisplayName("domain code does not depend on the web or servlet tiers")
    void domainIsIndependent() {
        // The module packages are named in full on purpose. A first version of this rule used
        // "..sales..", which matches every class in the application because the base package is
        // itself com.infeara.sales — so SalesApplication, GlobalExceptionHandler and the servlet
        // initializer were all reported as domain violations (142 of them) and the rule proved
        // nothing. Shared infrastructure (common.web, common.error, config) is deliberately outside
        // the list: it is the adapter layer that is allowed to know about HTTP.
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.infeara.sales.crm..",
                        "com.infeara.sales.sales..",
                        "com.infeara.sales.engagement..",
                        "com.infeara.sales.analytics..",
                        "com.infeara.sales.workspace..",
                        "com.infeara.sales.automation..")
                .and().haveSimpleNameNotEndingWith("Controller")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.infeara.sales.web..", "org.springframework.web..", "jakarta.servlet..");
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
