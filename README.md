# FitPub

FitPub is a self-hosted fitness tracking platform for the Fediverse. It lets people upload workout files, review their activities with maps and metrics, and share them through ActivityPub instead of locking them into a closed social network.

The project is built for people who want to keep control of their training data while still participating in a social graph that reaches Mastodon-compatible platforms and other ActivityPub servers.

## What FitPub does

- Imports activity files from GPS devices and training apps
- Supports FIT and GPX uploads
- Publishes activities to followers over ActivityPub
- Provides public, followers-only, and private visibility modes
- Applies privacy zones to protect sensitive start and end locations
- Shows maps, metrics, timelines, and profile pages in a server-rendered web UI
- Includes analytics such as summaries, personal records, achievements, training load, and heatmaps
- Supports batch imports from ZIP archives

## Why this project exists

Most fitness platforms combine activity storage, analysis, and social distribution inside one vendor-controlled product. FitPub separates those concerns. You can run your own instance, keep your own data, and still share workouts with people on the wider Fediverse.

## Stack

FitPub is built with:

- Java 17
- Spring Boot 3
- Thymeleaf
- PostgreSQL with PostGIS
- Flyway
- ActivityPub-compatible federation

## Project layout

- `src/main/java/` - application code
- `src/main/resources/templates/` - server-rendered views
- `src/main/resources/static/` - frontend assets
- `src/main/resources/db/migration/` - Flyway database migrations
- `src/test/` - automated tests
- `CONTAINERS.md` - container deployment notes for Docker- or Podman-based setups

## Deployment

FitPub is intended to be self-hosted.

For container-based deployment, see [CONTAINERS.md](./CONTAINERS.md).

## Current scope

The repository already includes:

- local accounts and authentication
- activity upload and post-processing
- federation endpoints and delivery logic
- social features such as follows, comments, likes, notifications, and timelines
- analytics and heatmap views
- privacy-zone filtering for track data

## Status

FitPub is an actively developed public project. The feature set is already substantial, but the codebase is still evolving and interface details may change as the platform matures.

## Contributing

Issues and pull requests are welcome.

For contributor workflow, local setup, build steps, and test execution, see [CONTRIBUTING.md](./CONTRIBUTING.md).

## License

No license file is currently present in this repository. Until one is added, reuse terms are not explicitly defined.
