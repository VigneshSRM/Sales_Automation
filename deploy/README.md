# Deployment assets

| File | Purpose |
|---|---|
| `docker-compose.dev.yml` | Development only: MariaDB 11.4 + Mailpit (SMTP sink). Never for production. |
| `env/.env.example` | Complete environment variable template; a filled copy belongs in a secret store. |

Production deployment is a WAR on **Apache Tomcat 11** — see
`docs/operations/deployment-tomcat11.md`. No container image is used for production, per the
approved architecture.
