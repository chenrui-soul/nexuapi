# NEXUS API

NEXUS API is an API aggregation platform with a Spring Boot backend, a React/Next.js administration console, and Docker deployment configuration.

## Repository layout

- `nexusCenter/backend` - Java 21 Spring Boot API server
- `nexusCenter/frontend` - Next.js administration console
- `nexusCenter/compose.prod.yaml` - production service composition
- `nexusCenter/nginx` - reverse proxy configuration
- `nexusCenter/docs` - project documentation

## Local development

See [`nexusCenter/README.md`](nexusCenter/README.md) and the backend/frontend README files for environment setup. Copy the relevant `.env.example` file before starting services. Production credentials and database exports are intentionally excluded from version control.
