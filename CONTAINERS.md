# Container Deployment Guide

This guide explains how to run FitPub with containers using either Docker or Podman.

The repository ships a `docker-compose.yml` file. Despite the name, it can also be used with Podman-compatible compose tooling.

## Supported runtimes

- Docker Engine with the `docker compose` plugin
- Podman with `podman compose` or another compatible compose frontend

Examples in this guide use both command styles where that adds clarity:

```bash
docker compose ...
podman compose ...
```

If your environment uses a different compose wrapper, adapt the command prefix accordingly.

## Quick start

### 1. Clone the repository

```bash
git clone <repository-url>
cd fitpub
```

### 2. Create an environment file

```bash
cp .env.example .env
```

### 3. Set production values

At minimum, review and set the following values in `.env`:

```bash
SPRING_PROFILES_ACTIVE=prod
POSTGRES_DB=fitpub
POSTGRES_USER=fitpub
POSTGRES_PASSWORD=change-this
APP_PORT=8080
FITPUB_DOMAIN=your-domain.com
FITPUB_BASE_URL=https://your-domain.com
JWT_SECRET=replace-with-a-long-random-secret
```

Recommended command for generating secrets for values such as `JWT_SECRET` and `POSTGRES_PASSWORD`:

```bash
openssl rand -base64 64
```

### 4. Start the stack

```bash
docker compose up -d --build
podman compose up -d --build
```

### 5. Verify the deployment

FitPub should become available at:

- Application: `http://localhost:8080` or your configured public URL
- Health check endpoint: `http://localhost:8080/actuator/health`

## Environment variables used by the container stack

The compose file expects these variables:

| Variable                 | Purpose                                     | Example / default         |
|--------------------------|---------------------------------------------|---------------------------|
| `SPRING_PROFILES_ACTIVE` | Spring profile for the app container        | `prod`                    |
| `POSTGRES_DB`            | PostgreSQL database name                    | `fitpub`                  |
| `POSTGRES_USER`          | PostgreSQL user                             | `fitpub`                  |
| `POSTGRES_PASSWORD`      | PostgreSQL password                         | set explicitly            |
| `POSTGRES_PORT`          | Host port for PostgreSQL                    | `5432`                    |
| `APP_PORT`               | Host port for FitPub                        | `8080`                    |
| `FITPUB_DOMAIN`          | Public domain used by the app               | `your-domain.com`         |
| `FITPUB_BASE_URL`        | Public base URL                             | `https://your-domain.com` |
| `JWT_SECRET`             | JWT signing secret                          | set explicitly            |
| `JWT_EXPIRATION_MS`      | JWT token lifetime                          | `86400000`                |
| `REGISTRATION_PASSWORD`  | Optional invite-style registration password | empty                     |
| `JPA_SHOW_SQL`           | Enable SQL logging                          | `false`                   |
| `JPA_FORMAT_SQL`         | Format SQL logs                             | `false`                   |
| `LOG_LEVEL_ROOT`         | Root log level                              | `INFO`                    |
| `LOG_LEVEL_APP`          | App log level                               | `INFO`                    |
| `LOG_LEVEL_SPRING`       | Spring log level                            | `INFO`                    |
| `LOG_LEVEL_HIBERNATE`    | Hibernate log level                         | `WARN`                    |
| `LOG_LEVEL_FLYWAY`       | Flyway log level                            | `INFO`                    |

`.env.example` documents the application-level environment variables and defaults. The compose file adds a smaller set of container-specific variables around database wiring, ports, and log levels.

## Services

### `postgres`

- Image: `postgis/postgis:16-3.4`
- Exposes container port `5432`
- Uses the named volume `postgres_data`
- Runs a `pg_isready` health check

### `app`

- Built from the repository `Dockerfile`
- Exposes container port `8080`
- Uses the named volumes `app_uploads` and `app_logs`
- Waits for the database health check before starting
- Publishes a health check on `/actuator/health`

## Common operations

### Show logs

```bash
docker compose logs -f
docker compose logs -f app
docker compose logs -f postgres
```

```bash
podman compose logs -f
podman compose logs -f app
podman compose logs -f postgres
```

### Restart services

```bash
docker compose restart
docker compose restart app
```

```bash
podman compose restart
podman compose restart app
```

### Stop and remove the stack

```bash
docker compose stop
docker compose down
docker compose down -v
```

```bash
podman compose stop
podman compose down
podman compose down -v
```

`down -v` removes persistent volumes and deletes database data.

### Run commands inside containers

```bash
docker compose exec app bash
docker compose exec postgres psql -U fitpub -d fitpub
```

```bash
podman compose exec app bash
podman compose exec postgres psql -U fitpub -d fitpub
```

### Rebuild the application image

```bash
docker compose up -d --build app
docker compose build --no-cache app
```

```bash
podman compose up -d --build app
podman compose build --no-cache app
```

## Volumes and backups

The compose stack creates these named volumes:

- `postgres_data`
- `app_uploads`
- `app_logs`

Examples with Docker:

```bash
docker volume ls | grep fitpub
docker volume inspect fitpub_postgres_data
docker run --rm -v fitpub_postgres_data:/data -v "$(pwd)":/backup \
  alpine tar czf /backup/postgres-backup-YYYYMMDD.tar.gz -C /data .
```

Examples with Podman:

```bash
podman volume ls
podman volume inspect fitpub_postgres_data
podman run --rm -v fitpub_postgres_data:/data -v "$(pwd)":/backup \
  docker.io/library/alpine tar czf /backup/postgres-backup-YYYYMMDD.tar.gz -C /data .
```

Actual volume names can vary with the compose project name. Inspect the stack locally if your names differ.

## Health checks and troubleshooting

### Application health

```bash
curl http://localhost:8080/actuator/health
```

### Database readiness

```bash
docker compose exec postgres pg_isready -U fitpub
podman compose exec postgres pg_isready -U fitpub
```

### Check rendered compose configuration

```bash
docker compose config
podman compose config
```

### Migration issues

```bash
docker compose exec postgres psql -U fitpub -d fitpub -c \
  "SELECT * FROM flyway_schema_history ORDER BY installed_rank;"
```

```bash
podman compose exec postgres psql -U fitpub -d fitpub -c \
  "SELECT * FROM flyway_schema_history ORDER BY installed_rank;"
```

### Reset the stack

```bash
docker compose down -v
docker compose up -d --build
```

```bash
podman compose down -v
podman compose up -d --build
```

This removes all persisted data.

## Production notes

- Set `SPRING_PROFILES_ACTIVE=prod`
- Use strong, unique values for `POSTGRES_PASSWORD` and `JWT_SECRET`
- Put FitPub behind HTTPS via a reverse proxy such as nginx, Traefik, or Caddy
- Back up the database volume regularly
- Review exposed ports and firewall rules
- Keep the container runtime and base images updated

## Related files

- [README.md](./README.md)
- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [docker-compose.yml](./docker-compose.yml)
- [Dockerfile](./Dockerfile)
