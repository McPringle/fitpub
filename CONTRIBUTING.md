# Contributing to FitPub

Thanks for contributing to FitPub.

This document covers the practical workflow for working on the codebase: setting up a local environment, running the application, executing tests, and preparing changes for review.

## Before you start

- Read the [README.md](./README.md) for project context
- Check whether an issue already exists for the change you want to make
- Keep changes focused; avoid mixing unrelated refactors with feature work or bug fixes

## Development environment

FitPub currently targets:

- Java 17
- Maven Wrapper (`./mvnw`)
- PostgreSQL with PostGIS
- Docker or Podman for local services and Testcontainers-based tests

The repository includes an `.sdkmanrc` file with Java 17 if you use SDKMAN.

## Local setup

### 1. Clone the repository

```bash
git clone <repository-url>
cd fitpub
```

### 2. Start PostgreSQL with PostGIS

The development profile expects a local PostgreSQL instance on port `5432`.

You can use either Docker or Podman. Example with Docker:

```bash
docker run -d \
  --name fitpub-postgres \
  -p 5432:5432 \
  -e POSTGRES_DB=fitpub \
  -e POSTGRES_USER=fitpub \
  -e POSTGRES_PASSWORD=change_me_in_production \
  postgis/postgis:16-3.4
```

If you prefer a Compose-based setup, see [CONTAINERS.md](./CONTAINERS.md). The same container layout can also be run with Podman-compatible compose tooling.

### 3. Optional environment configuration

The application reads configuration from environment variables, but the development profile already provides sensible defaults for the usual local setup.

In most cases, you can start the application without setting anything beyond the local PostgreSQL/PostGIS instance.

If you want to override defaults or configure registration behavior, copy the example file:

```bash
cp .env.example .env
```

Then add or adjust values in `.env` as needed.

`.env.example` documents the available overrides together with the defaults used for local development.

For local work, the application defaults to the `dev` profile. For production deployments, set:

```bash
SPRING_PROFILES_ACTIVE=prod
```

## Running the application

Start the app with the development profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

By default, the application runs at `http://localhost:8080`.

The development profile uses:

- Flyway for schema migrations
- verbose SQL and application logging
- a development JWT secret unless you override it

## Building

Create the application artifact with:

```bash
./mvnw clean package
```

## Testing

Run the full test suite with:

```bash
./mvnw test
```

Important notes:

- Tests use Testcontainers and require a working local container runtime such as Docker or Podman
- The test configuration provisions PostgreSQL/PostGIS automatically
- Some tests exercise file parsing and integration flows, so the suite is heavier than a pure unit-test run

To run a single test class or method:

```bash
./mvnw test -Dtest=ActivityImageServiceTest
./mvnw test -Dtest=ActivityImageServiceTest#testGenerateActivityImage_Manual
```

## Database and migrations

- Database schema changes should go through Flyway migrations in `src/main/resources/db/migration/`
- Do not rely on Hibernate schema generation for persistent changes
- Keep migrations forward-only and review them carefully for compatibility with existing data

## Code style

There is no fully codified style toolchain checked into the repository at the moment, so contributors should follow the existing codebase closely.

In practice, that means:

- keep naming and package structure consistent with surrounding code
- prefer small, focused controller, service, and repository changes
- add tests for behavior changes and regressions
- avoid incidental formatting churn in unrelated files

## Pull requests

When opening a pull request:

- explain the problem being solved
- describe the behavior change clearly
- mention any schema, API, or federation impact
- include screenshots for UI changes when useful
- call out follow-up work explicitly instead of bundling it into the same PR

Before submitting, make sure:

- the application builds successfully
- relevant tests pass locally
- new migrations have been validated
- documentation is updated when behavior or setup changed

## Commit guidance

There is no strict commit convention enforced by the repository, but clear history helps review.

Prefer commits that:

- have a single purpose
- use descriptive messages
- avoid mixing cleanup with functional changes

## Security and privacy

FitPub handles personal activity data, location data, and federation-facing endpoints.

Please treat the following areas carefully:

- privacy-zone behavior
- activity visibility rules
- authentication and JWT handling
- ActivityPub request validation and outbound federation logic
- file upload and parsing paths

If you find a security issue, prefer responsible disclosure over opening a public issue with exploit details.
