g# SMART on FHIR and Keycloak — Quick Guide

This document explains how to enable SMART on FHIR support for Couchbase FHIR CE, and how to use the optional Keycloak add-on to provide an OIDC provider for development and testing.

Contents:
- Quick overview
- Config settings (`config.yaml`)
- Generate runtime compose files (`scripts/generate.py`)
- Enable Keycloak helper (`scripts/enable-keycloak.sh`)
- Seed Keycloak (`scripts/keycloak/seed_keycloak.sh`)
- Using an external OIDC provider

## Quick overview

- The backend supports OAuth2 / OpenID Connect for SMART on FHIR. The FHIR server validates incoming access tokens using a JWKS endpoint (JWKS URI).
- You can either point the server at an external OIDC provider (Auth0, Cognito, enterprise IdP) or run Keycloak locally using the included helpers.

Note: by default the project uses the embedded Spring Authorization Service for OAuth/OIDC. The Keycloak helpers and configuration are optional — enable them only if you want to run Keycloak as your OIDC provider.

## Relevant `config.yaml` settings

Edit `config.yaml` (or `config.yaml.template`) to set your application base URL and Keycloak settings. Important fields:

- `app.baseUrl` — public URL for your FHIR server (required by OAuth redirect config)
- `deploy.tls.enabled` — whether TLS/HTTPS is enabled for HAProxy (affects ports and cert mounting)

Keycloak-specific section (optional):

```yaml
keycloak:
  enabled: true            # enable Keycloak integration helpers (not required if using external OIDC)
  realm: "fhir-realm"     # realm name to create/import
  adminUser: "admin"      # Keycloak admin username (used by helper scripts)
  adminPassword: "admin"  # Keycloak admin password
  clientId: "fhir-server" # OAuth client id for the FHIR server
  clientSecret: ""        # (optional) client secret; if empty a secret will be generated
  url: "http://localhost:8080/auth" # Optional; set if Keycloak runs at non-default URL
```

Notes:
- `keycloak.enabled` toggles whether the helper scripts attempt to insert Keycloak into compose files and write `.env` entries. It does not force you to use Keycloak — you can leave this section out and use any OIDC provider.
- The generator (`scripts/generate.py`) uses `app.baseUrl` and `deploy.tls` to produce `docker-compose.yml` and `haproxy.cfg` that match your runtime choices.

## Generate docker-compose and HAProxy config

Before starting containers, generate the runtime compose file and haproxy config from your `config.yaml`:

```bash
./scripts/generate.py ./config.yaml
# Output: docker-compose.yml and haproxy.cfg (existing files are backed up)
```

After generation, start the stack:

```bash
docker compose up -d
```

If you enabled Keycloak in `config.yaml` and used `scripts/enable-keycloak.sh` (see below), `docker compose up -d keycloak` will launch Keycloak as an additional service.

## Enable Keycloak (helper)

The repo includes `scripts/enable-keycloak.sh` which:

- Parses `config.yaml` and writes Keycloak-related environment variables into `.env`
- Inserts a Keycloak service into local docker-compose files (best-effort)
- Derives and writes `KEYCLOAK_JWKS_URI` into `.env`
- Writes a small `scripts/keycloak/realm.json` helpful for import/seeding

Usage:

```bash
./scripts/enable-keycloak.sh ./config.yaml
```

This will:
- Create or update `.env` with `KEYCLOAK_*` vars
- Attempt to append a Keycloak service to existing `docker-compose*.yml` files (and create backups)
- Write `scripts/keycloak/realm.json` containing a basic client registration

After running the enable helper, regenerate runtime files and start the stack:

```bash
./scripts/generate.py ./config.yaml
docker compose up -d
```

### What the backend expects

The backend reads the JWKS URI (Keycloak's `.../protocol/openid-connect/certs`) to validate tokens. The enable helper writes `KEYCLOAK_JWKS_URI` into `.env` so the backend can pick it up (via `application.yml` patching helper included in the script).

If you run Keycloak on a different host/port, ensure `KEYCLOAK_URL` and `KEYCLOAK_REALM` are set in `.env` and that `KEYCLOAK_JWKS_URI` points to the provider's JWKS endpoint.

## Seed Keycloak (clients, scopes, test user)

Once Keycloak is running, use the seeding helper to create a client, scopes and a test user:

```bash
# Ensure .env is present (created by enable-keycloak.sh) then:
./scripts/keycloak/seed_keycloak.sh .env
```

This script will:
- Wait for Keycloak to be reachable
- Create the realm (if missing)
- Create/update the client (clientId is taken from `KEYCLOAK_CLIENT_ID`)
- Ensure a client secret exists (prints it if generated)
- Optionally create a test user `smart.user@example.com` / `password123`
- Create a set of SMART/FHIR-related client-scopes commonly used by the frontend

After seeding, update `.env` with any generated `KEYCLOAK_CLIENT_SECRET` (the script prints it when created).

## Using an external OIDC provider (no Keycloak)

If you prefer to use a cloud or enterprise IdP, you don't need to enable the Keycloak helpers. Instead:

1. Configure your IdP with a confidential client for the FHIR server. Set allowed redirect URIs (e.g. `http://localhost:8080/authorized`) and generate a client secret.
2. In your backend/hosting environment, set the equivalent of `FHIR_OIDC_JWKS_URI` (or `KEYCLOAK_JWKS_URI` if using the same env variable convention) to point to the provider's JWKS endpoint.
3. Provide the client id and secret to the frontend and backend as appropriate (via `.env` or `application.yml`).

The minimum the server needs to validate tokens is the provider's JWKS URI and the expected issuer (the backend uses configured OIDC settings to validate token issuer/audience).

## Common troubleshooting tips

- If the backend rejects tokens, verify `KEYCLOAK_JWKS_URI` is reachable from the container and contains keys.
- If the frontend fails OAuth redirect, ensure `app.baseUrl` is correct and the IdP client redirect URIs include `http(s)://<your-host>/authorized`.
- Check `docker compose logs keycloak` and `docker compose logs fhir-server` for errors.

## Example minimal workflow (local dev)

1. Edit `config.yaml` and set `app.baseUrl: "http://localhost:8080/fhir"` and `keycloak` block with `enabled: true` and admin credentials.
2. Run the enable helper:

```bash
./scripts/enable-keycloak.sh ./config.yaml
```

3. Generate runtime compose files:

```bash
./scripts/generate.py ./config.yaml
```

4. Start Keycloak and services:

```bash
docker compose up -d keycloak fhir-server fhir-admin haproxy
```

5. Seed Keycloak:

```bash
./scripts/keycloak/seed_keycloak.sh .env
```

6. Open the admin UI and test SMART flows with the client created by the seed script.

---

If you need help adapting this guide to a specific cloud IdP or enterprise setup, open an issue or ask for assistance in the project chat/history.
