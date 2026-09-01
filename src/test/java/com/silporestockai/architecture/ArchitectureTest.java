package com.silporestockai.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Architecture rules enforced as tests.
 *
 * <p>Every package these rules name now has classes in it, so {@code archRule.failOnEmptyShould} is back at its
 * default: a rule that stops matching anything is a rule that stopped being enforced, and that should fail loudly
 * rather than pass quietly.
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

    // Synthetic classes are excluded from every naming rule: a switch over an enum makes javac emit an
    // anonymous `Outer$1` holding the switch map, and no naming convention can apply to a class nobody wrote.
    @ArchTest
    static final ArchRule controllersAreNamedProperly = classes()
            .that()
            .resideInAPackage("..controller..")
            .and()
            .doNotHaveModifier(JavaModifier.SYNTHETIC)
            .should()
            .haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule servicesAreNamedProperly = classes()
            .that()
            .resideInAPackage("..service..")
            .and()
            .doNotHaveModifier(JavaModifier.SYNTHETIC)
            .should()
            .haveSimpleNameEndingWith("Service");

    @ArchTest
    static final ArchRule repositoriesAreNamedProperly = classes()
            .that()
            .resideInAPackage("..repository..")
            .and()
            .doNotHaveModifier(JavaModifier.SYNTHETIC)
            .should()
            .haveSimpleNameEndingWith("Repository");

    /**
     * The Telegram SDK is an implementation detail of the two packages that own the channel. Domain services depend on
     * {@code TelegramOutboundService} and the records in {@code model}, never on {@code Update}, {@code Message} or any
     * other SDK type.
     */
    @ArchTest
    static final ArchRule telegramSdkStaysBehindTheTelegramPackages = noClasses()
            .that()
            .resideOutsideOfPackages("..controller.telegram..", "..service.telegram..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.telegram..")
            .as("Telegram SDK types must not leak outside controller.telegram and service.telegram");

    /**
     * The Anthropic SDK is an implementation detail of one class, the same way the Telegram SDK is. A service that
     * imported {@code MessageCreateParams} would be a service that cannot be pointed at a different model.
     */
    @ArchTest
    static final ArchRule anthropicSdkStaysBehindTheClaudeClient = noClasses()
            .that()
            .resideOutsideOfPackage("..client.claude..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.anthropic..")
            .as("Anthropic SDK types must not leak outside client.claude");

    /** Same rule for the MCP SDK: the transport, the session handshake and the refresh dance live in one package. */
    @ArchTest
    static final ArchRule mcpSdkStaysBehindTheMcpClient = noClasses()
            .that()
            .resideOutsideOfPackage("..client.mcp..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.modelcontextprotocol..")
            .as("MCP SDK types must not leak outside client.mcp");

    /**
     * Stored Silpo tokens stay server-side.
     *
     * <p>The controllers are the only classes that can put something on the wire towards a browser or a chat, so
     * keeping the token entity, its repository and the cipher out of reach of that layer is what makes "tokens are
     * never rendered into anything a client sees" a property of the build rather than a habit.
     */
    @ArchTest
    static final ArchRule oauthTokensNeverReachTheWeb = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("OAuthToken")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("OAuthTokenRepository")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleName("TokenCipher")
            .as("stored OAuth tokens must stay out of the web layer");

    /**
     * Domain records carry no framework.
     *
     * <p>They cross a JSON column, a Claude schema and a Telegram message in the same shape, and each of those would
     * otherwise pull its own annotations into a package whose whole job is being plain data.
     */
    @ArchTest
    static final ArchRule domainModelsStayFrameworkFree = noClasses()
            .that()
            .resideInAPackage("..model..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.telegram..")
            .as("model records must not depend on Spring, JPA or the Telegram SDK");

    /** Scheduled work is an agent stage, and every agent stage is in {@code job} where it can be found. */
    @ArchTest
    static final ArchRule scheduledWorkLivesInTheJobPackage = methods()
            .that()
            .areAnnotatedWith(Scheduled.class)
            .should()
            .beDeclaredInClassesThat()
            .resideInAPackage("..job..")
            .as("@Scheduled belongs in the job package");

    @ArchTest
    static final ArchRule noStandardStreams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule jobsAreNamedProperly = classes()
            .that()
            .resideInAPackage("..job..")
            .and()
            .doNotHaveModifier(JavaModifier.SYNTHETIC)
            .should()
            .haveSimpleNameEndingWith("Scheduler");
}
