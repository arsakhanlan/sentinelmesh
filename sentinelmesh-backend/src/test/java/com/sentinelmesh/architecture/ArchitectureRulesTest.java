package com.sentinelmesh.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The non-negotiable architecture rules. These run as part of {@code gradle test}
 * and fail the build if violated.
 */
@AnalyzeClasses(packages = "com.sentinelmesh", importOptions = com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
public class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule domainModelMustNotDependOnSpring =
            noClasses().that().resideInAPackage("..domain.model..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule domainModelMustNotDependOnJpa =
            noClasses().that().resideInAPackage("..domain.model..")
                    .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..");

    @ArchTest
    static final ArchRule domainPortsMustNotDependOnJpa =
            noClasses().that().resideInAPackage("..domain.port..")
                    .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..");

    @ArchTest
    static final ArchRule securityScannersImplementStage =
            // Top-level scanner classes must implement ScannerStage. Nested
            // helper records/classes (e.g. L5's SessionStats) are excluded —
            // they're implementation detail of the scanner that owns them.
            classes().that().resideInAPackage("..security.scanners..")
                    .and().areNotInterfaces()
                    .and().areTopLevelClasses()
                    .should().implement(com.sentinelmesh.security.pipeline.ScannerStage.class);

    @ArchTest
    static final ArchRule controllersOnlyInApiPackage =
            classes().that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                    .should().resideInAPackage("..api.rest..");
}
