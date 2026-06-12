# AGENTS.md

## Project Overview

This is a Java 21 Spring Boot backend project.

Current stack:

- Spring Boot 3.5.15
- Gradle Kotlin DSL
- Spring Web
- Spring Data JPA
- Liquibase
- PostgreSQL
- Lombok
- JUnit 5
- Testcontainers with PostgreSQL

The codebase is currently a minimal generated application skeleton. There are no domain entities, repositories, services, controllers, or Liquibase changelogs yet.

## Repository Layout

- `build.gradle.kts` - Gradle build configuration and dependencies.
- `settings.gradle.kts` - Gradle project name.
- `src/main/java/ru/ravil/petproject/PetProjectApplication.java` - application entry point.
- `src/main/resources/application.properties` - application configuration.
- `src/test/java/ru/ravil/petproject/PetProjectApplicationTests.java` - Spring context test.
- `src/test/java/ru/ravil/petproject/TestcontainersConfiguration.java` - PostgreSQL Testcontainers configuration.
- `src/test/java/ru/ravil/petproject/TestPetProjectApplication.java` - development-time Testcontainers launcher.

## Common Commands

Use the Gradle wrapper from the repository root.

```powershell
.\gradlew.bat test
```

```powershell
.\gradlew.bat bootRun
```

On this machine, `JAVA_HOME` may need to point to an installed Java 21 JDK:

```powershell
$env:JAVA_HOME='C:\Users\Ravil\.jdks\ms-21.0.11'
```

## Testing Notes

The default context test imports `TestcontainersConfiguration`, so running tests requires a valid Docker environment.

If Docker is unavailable, `contextLoads()` fails before application code is exercised with:

```text
Could not find a valid Docker environment
```

## Development Guidelines

- Keep package names under `ru.ravil.petproject`.
- Prefer small vertical slices: migration, entity, repository, service, controller, and focused tests.
- Add Liquibase changelogs before relying on JPA entities in PostgreSQL.
- Avoid using floating Docker image tags such as `postgres:latest`; prefer a pinned PostgreSQL version.
- Keep configuration environment-specific. Do not commit local secrets, database passwords, or machine-specific paths.

