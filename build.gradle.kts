plugins {
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
    // NOTE: kotlin("jvm") is NOT required here. The Kotlin Gradle DSL (build.gradle.kts)
    // does not require the Kotlin JVM plugin — that plugin is only needed when compiling
    // Kotlin source files. This is a pure Java project; the DSL itself is compiled by
    // Gradle's embedded Kotlin compiler regardless of whether the plugin is applied.
    java
}

group = "com.library"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Testcontainers BOM to align all Testcontainers module versions.
// Without this, testcontainers-postgresql and testcontainers-junit-jupiter
// can be on different versions and cause subtle compatibility issues.
dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
    }
}

dependencies {
    // --- Core Spring Boot ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // --- Lombok ---
    // Required for @RequiredArgsConstructor (constructor injection) and @Slf4j (logging).
    // compileOnly: present at compile time but not bundled in the JAR.
    // annotationProcessor: runs during compilation to generate the constructor and logger code.
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    // Lombok must also be on the test annotation processor path so @RequiredArgsConstructor
    // and @Slf4j are generated for test helper classes if any are annotated.
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // --- Database ---
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql") // Required for PG 15+ Flyway support
    runtimeOnly("org.postgresql:postgresql")

    // --- API Documentation ---
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.0")

    // --- Local development ---
    // DevTools provides automatic application restart on class file changes during local dev.
    // developmentOnly ensures this is NOT included in production builds.
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // --- Test dependencies ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Testcontainers -- versions managed by BOM above
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform() // Required to activate JUnit 5 (Jupiter) test runner
    // Increase heap for Testcontainers + Spring Boot context in CI-like environments
    jvmArgs("-Xmx512m")
}
