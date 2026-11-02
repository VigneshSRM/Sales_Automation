# Deployment assets

| File | Purpose |
|---|---|
| `docker-compose.dev.yml` | Development only: MariaDB 11.4 + Mailpit (SMTP sink). Never for production. |
| `env/.env.example` | Complete environment variable template; a filled copy belongs in a secret store. |

Production deployment is a WAR on **Apache Tomcat 11** — see
`docs/operations/deployment-tomcat11.md`. No container image is used for production, per the
approved architecture.

A production host installs **Java 17 and Tomcat 11 only**. All application libraries are packaged
inside the WAR (`WEB-INF/lib`); Tomcat supplies just the servlet container. Do not place application
jars in `$CATALINA_HOME/lib` — if a library is needed, add it to `backend/pom.xml` and rebuild the
WAR (`docs/operations/installation-setup.md` §0).
