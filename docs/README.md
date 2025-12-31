# Couchbase FHIR CE

Open source FHIR server and admin UI with Couchbase and Spring Boot with HAPI

## Overview

Couchbase FHIR CE is a comprehensive FHIR (Fast Healthcare Interoperability Resources) server implementation built on Couchbase with a modern React-based admin interface. This project provides a complete solution for healthcare data management and FHIR compliance.

## Project Structure

```
couchbase-fhir-ce/
├── backend/          # Spring Boot FHIR Server
├── frontend/         # React Admin UI
├── config.yaml       # Application configuration
├── README.md         # This file
# Couchbase FHIR CE

Open source FHIR server and admin UI with Couchbase and Spring Boot with HAPI

## Overview

Couchbase FHIR CE is a comprehensive FHIR (Fast Healthcare Interoperability) server implementation built on Couchbase with a modern React-based admin interface. This project provides a complete solution for healthcare data management and FHIR compliance.

## Project Structure

```
couchbase-fhir-ce/
├── backend/          # Spring Boot FHIR Server
├── frontend/         # React Admin UI
├── config.yaml       # Application configuration (user-editable)
├── docker-compose.*  # generated / templates
├── scripts/          # helpers (generate, keycloak helpers, etc.)
└── docs/             # documentation (this folder)
```

## Quick Start

### Prerequisites

- Java 17
- Node.js 18+
- Couchbase Server 7.0+ or Couchbase Capella account

### Backend Setup (development)

```bash
cd backend
mvn spring-boot:run
```

### Frontend Setup (development)

```bash
cd frontend
npm install
npm run dev
```

### Generate runtime compose files

This repository now generates `docker-compose.yml` and `haproxy.cfg` from your `config.yaml` using the generator helper. This is the recommended way to produce a local runtime config that matches your `config.yaml` settings.

```bash
# From project root
./scripts/generate.py ./config.yaml
# This writes/backs-up: docker-compose.yml and haproxy.cfg
```

After generation you can start services:

```bash
docker compose up -d
```

See `scripts/generate.py` for details about what gets generated and which settings are honored.

## Documentation

For detailed information about:

- **Project Architecture**: See `PROJECT_GUIDE.md`
- **Backend Architecture**: See `backend/ARCHITECTURE.md`
- **Development Guidelines**: See `PROJECT_GUIDE.md`

## Key Features

- **FHIR R4 Compliance**: Full FHIR R4 resource support
- **Couchbase Integration**: Native Couchbase data storage
- **Admin UI**: Modern React-based management interface
- **SMART on FHIR**: OAuth2 / OpenID Connect support for SMART apps (see `docs/SMART_AND_KEYCLOAK.md`)
- **Multi-tenant Support**: Tenant-based FHIR resource isolation
- **Audit Logging**: Comprehensive audit trail
- **Health Monitoring**: System health and metrics dashboard

## SMART on FHIR and Keycloak

SMART on FHIR support is included. You can use a third-party OIDC provider or optionally enable a Keycloak add-on we provide helper scripts for. The detailed guide lives at [docs/SMART_AND_KEYCLOAK.md](SMART_AND_KEYCLOAK.md) and covers:

- Which `config.yaml` settings control Keycloak/SMART behavior
- How to enable Keycloak and write `.env` using `scripts/enable-keycloak.sh`
- How to seed Keycloak with clients, scopes and a test user using `scripts/keycloak/seed_keycloak.sh`
- How to generate `docker-compose.yml` and `haproxy.cfg` using `scripts/generate.py`

Note: by default the project uses the embedded Spring Authorization Service for OAuth/OIDC. Keycloak is an opt-in alternative for users who prefer to run a dedicated OIDC server.

If you want to enable Keycloak quickly, edit `config.yaml` (or `config.yaml.template`) and set the `keycloak` section, then run:

```bash
./scripts/enable-keycloak.sh ./config.yaml
./scripts/generate.py ./config.yaml
docker compose up -d
```

This will create/update `.env`, attempt to add Keycloak services to docker-compose files and write a realm import JSON under `scripts/keycloak/realm.json`.

## Docker Deployment

See [Docker-Deployment.md](./Docker-Deployment.md) for additional deployment options and production recommendations.

### Compose Files Explained

There are TWO compose files with different purposes:

| File                      | Purpose                                                                 | Builds from source?       | Used by installer?                                 |
| ------------------------- | ----------------------------------------------------------------------- | ------------------------- | -------------------------------------------------- |
| `docker-compose.yml`     | Local development / runtime compose generated from `config.yaml`       | Yes (may contain `build`) | No                                                 |
| `docker-compose.user.yml` | Distribution template consumed by `install.sh` (pulls pre-built images) | No (uses `image:` tags)   | Yes (downloaded and saved as `docker-compose.yml`) |

When a user runs the one‑liner installer:

```bash
curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/install.sh | bash -s -- ./config.yaml
```

The script fetches `docker-compose.user.yml` from GitHub and writes it locally as `docker-compose.yml`. Integrity is verified using SHA256 hashes embedded in `install.sh`.

#### Keeping Them in Sync

If you change runtime environment variables, ports, volumes or service names and want those changes reflected in the user distribution, copy the relevant parts into `docker-compose.user.yml` and then:

```bash
./scripts/update-checksums.sh
```

Use `DRY_RUN=1` to preview or `SKIP_HAPROXY=1` if only the compose file changed.

#### Customizing Runtime User

`docker-compose.user.yml` allows overriding the container user via environment variables before running the installer:

```bash
export FHIR_RUN_UID=$(id -u)
export FHIR_RUN_GID=$(id -g)
```

This helps avoid permission issues for bind-mounted log directories.

### Updating Installer Hashes

The script `scripts/update-checksums.sh` recalculates SHA256 hashes for the distribution artifacts and updates `install.sh` accordingly.

## Log Rotation & S3 Uploads

Log Rotation is enabled by default. Note: S3 upload functionality is currently disabled for the Beta release. To learn more read [LOG_ROTATION_AND_S3_UPLOAD.md](./LOG_ROTATION_AND_S3_UPLOAD.md)
