plugins {
    java
    id("org.springframework.boot") version "3.5.15"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ru.ravil"
version = "0.0.1-SNAPSHOT"
description = "pet-project"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.liquibase:liquibase-core")
    compileOnly("org.projectlombok:lombok")
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("live-openai")
        excludeTags("memory-eval")
    }
}

val liveTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs OpenAI-backed live smoke tests only when explicitly enabled."
    useJUnitPlatform {
        includeTags("live-openai")
    }
    onlyIf {
        val propertyEnabled = providers.gradleProperty("runLiveGptTests").orNull == "true"
        val envEnabled = System.getenv("RUN_LIVE_GPT_TESTS") == "true"
        propertyEnabled || envEnabled
    }
    systemProperty("run.live.gpt.tests", "true")
    shouldRunAfter(tasks.test)
}

val memoryEval by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs AI Memory semantic evaluation scenarios and writes build/reports/memory-eval."
    outputs.upToDateWhen { false }
    useJUnitPlatform {
        includeTags("memory-eval")
    }
    systemProperty("spring.profiles.active", "eval")
    systemProperty("memory.eval.judge.enabled", providers.gradleProperty("memoryEvalJudgeEnabled").orNull
        ?: System.getenv("MEMORY_EVAL_JUDGE_ENABLED")
        ?: "false")
    systemProperty("memory.eval.judge.model", providers.gradleProperty("memoryEvalJudgeModel").orNull
        ?: System.getenv("MEMORY_EVAL_JUDGE_MODEL")
        ?: "gpt-4.1-mini")
    systemProperty("memory.eval.limit", providers.gradleProperty("memoryEvalLimit").orNull
        ?: System.getenv("MEMORY_EVAL_LIMIT")
        ?: "0")
    systemProperty("memory.eval.database.mode", providers.gradleProperty("memoryEvalDatabaseMode").orNull
        ?: System.getenv("MEMORY_EVAL_DATABASE_MODE")
        ?: "isolated")
    shouldRunAfter(tasks.test)
}
