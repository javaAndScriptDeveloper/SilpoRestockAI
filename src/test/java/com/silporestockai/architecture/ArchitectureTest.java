package com.silporestockai.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

/**
 * Architecture rules enforced as tests. They currently match a near-empty codebase (see {@code archunit.properties},
 * which permits empty matches) and start enforcing as soon as the corresponding packages gain classes.
 */
@AnalyzeClasses(
        packages = "com.silporestockai",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected = Architectures.layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            // Optional layers so the empty starter scaffold passes; they enforce once packages gain classes.
            .optionalLayer("Controller")
            .definedBy("..controller..")
            .optionalLayer("Service")
            .definedBy("..service..")
            .optionalLayer("Repository")
            .definedBy("..repository..")
            // Scheduled agent stages (check-in prompts, reorder triggers) drive services the same way a
            // controller does; without their own layer they would violate the Service access rule.
            .optionalLayer("Job")
            .definedBy("..job..")
            .whereLayer("Controller")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Job")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Service")
            .mayOnlyBeAccessedByLayers("Controller", "Job")
            .whereLayer("Repository")
            .mayOnlyBeAccessedByLayers("Service");

    @ArchTest
    static final ArchRule noFieldInjection = fields().should()
            .notBeAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .as("prefer constructor injection over field injection");

    @ArchTest
    static final ArchRule controllersAreNamedProperly =
            classes().that().resideInAPackage("..controller..").should().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule servicesAreNamedProperly =
            classes().that().resideInAPackage("..service..").should().haveSimpleNameEndingWith("Service");

    @ArchTest
    static final ArchRule repositoriesAreNamedProperly =
            classes().that().resideInAPackage("..repository..").should().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule jobsAreNamedProperly =
            classes().that().resideInAPackage("..job..").should().haveSimpleNameEndingWith("Scheduler");
}
