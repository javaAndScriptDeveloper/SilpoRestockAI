plugins {
    id("java")
    id("jacoco")
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.2.1"
}

group = "com.silporestockai"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        // Gradle auto-provisions this JDK via the foojay resolver (settings.gradle.kts)
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springCloudVersion"] = "2025.1.2"

dependencies {
    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    // MapStruct
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")

    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    // Caffeine backs the cache abstraction above (starter-cache alone only ships a simple in-memory map)
    implementation("com.github.ben-manes.caffeine:caffeine")

    // AOP: Boot 4 dropped spring-boot-starter-aop; spring-aspects pulls AspectJ + enables @Aspect support
    implementation("org.springframework:spring-aspects")

    // Spring Cloud
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    // Resilience4j circuit breaker + retry, integrated with Feign via feign.circuitbreaker.enabled
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")

    // API Documentation (OpenAPI 3 / Swagger UI)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // Claude — meal planning, shopping-list derivation, check-in parsing (structured outputs)
    implementation("com.anthropic:anthropic-java:2.57.0")

    // MCP client for mcp.silpo.ua. Streamable HTTP needs SSE + the Mcp-Session-Id handshake,
    // which Feign cannot express — this is the one deliberate exception to the Feign convention.
    implementation("io.modelcontextprotocol.sdk:mcp")

    // Database Migration. Boot 4 moved LiquibaseAutoConfiguration out of spring-boot-autoconfigure
    // into its own module, so liquibase-core alone leaves the changelog silently unapplied.
    implementation("org.springframework.boot:spring-boot-liquibase")
    implementation("org.liquibase:liquibase-core")

    // PostgreSQL Driver
    runtimeOnly("org.postgresql:postgresql")

    // Local development: manage docker-compose.yml lifecycle automatically on bootRun
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // Test Dependencies (JUnit 5, AssertJ, Mockito, JSONassert ship with starter-test)
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 split MockMvc test support (@AutoConfigureMockMvc) into its own module
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")

    // Integration testing against a real PostgreSQL via Testcontainers
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")

    // Random, fully-populated test objects — the project convention over hand-built fixtures
    testImplementation("org.instancio:instancio-junit:5.4.1")
    // Architecture rules enforced as tests (layering, naming, no field injection)
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
        // Boot 4.1 no longer manages Testcontainers versions; pin them via the Testcontainers BOM.
        mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
        mavenBom("io.modelcontextprotocol.sdk:mcp-bom:2.0.1")
    }
}

// Populate /actuator/info with build metadata
springBoot {
    buildInfo()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Amapstruct.defaultComponentModel=spring")
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("junit.jupiter.extensions.autodetection.enabled", true)
    systemProperty("file.encoding", "UTF-8")
    finalizedBy(tasks.jacocoTestReport)
}

// Classes with no meaningful branches to cover — excluded from the coverage report and gate.
val coverageExclusions = listOf("com/silporestockai/Application.class", "com/silporestockai/config/**")

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
    classDirectories.setFrom(
        classDirectories.files.map {
            fileTree(it) { exclude(coverageExclusions) }
        },
    )
}

tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(
        classDirectories.files.map {
            fileTree(it) { exclude(coverageExclusions) }
        },
    )
    violationRules {
        rule {
            limit {
                // Bootstrap threshold: this is a near-empty template. Raise it as domain code lands.
                minimum = "0.0".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

spotless {
    java {
        target("src/*/java/**/*.java")
        // Pinned: the version Spotless 7.2.1 bundles by default crashes on JDK 25 javac internals
        // (NoSuchMethodError Log$DeferredDiagnosticHandler.getDiagnostics).
        palantirJavaFormat("2.97.0")
        importOrder()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
