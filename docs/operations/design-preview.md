# Design preview (no JDK, no MariaDB, no Tomcat)

Why this document exists: the packaged UI is a Next.js static export that boots by calling
`GET /api/v1/auth/me`. Serving that export from a plain file server (or opening the WAR's
`static/` folder directly) produces a page that returns HTTP 200 but immediately renders

> Something went wrong — The workspace could not be loaded. The API may be unavailable.

That message comes from `frontend/components/app-shell.tsx` (`ErrorState`). It is the correct
behaviour of a client that cannot reach its API, but it is useless for reviewing the screens.
`scripts/preview-api.py` closes that gap for reviewers; it is **not** the product and is not
packaged into the WAR.

## What runs

```
python3 scripts/preview-api.py --port 3000 --ui backend/src/main/resources/static
# then
python3 scripts/verify-preview.py --base http://127.0.0.1:3000
```

`preview-api.py` is a stdlib `ThreadingHTTPServer` that serves, on one port:

| Surface | Behaviour |
| --- | --- |
| `/*.html`, `/_next/**` | the real static export of the frontend, byte-for-byte |
| `GET /api/v1/**` | fixture envelopes in the exact `{success,data,meta.requestId}` shape the UI expects |
| `POST/PATCH/DELETE /api/v1/**` | `{"success":true,"data":{"status":"PREVIEW_ONLY"}}` — nothing is persisted |
| every HTML page | a dismissible *DESIGN PREVIEW* banner is injected |
| every response | header `X-Infeara-Preview: fixtures` |

The fixture session grants the capabilities behind all eight navigation groups, so every screen
renders with its real controls instead of an empty permission state.

## What it deliberately does not do

- No email is sent, no mailbox is read, no AI call is made, no job runs. Anything that would be a
  real integration answers with the same explicit error codes the product uses
  (`AI_PROVIDER_UNAVAILABLE`, `UNSUPPORTED_OPERATION`) rather than inventing a success.
- Nothing here is evidence of correctness. Product behaviour is proven by the Tomcat 11 CI run in
  `.github/workflows/build-and-host-war.yml` (WAR verify, Flyway V1–V10, Spring context, 97
  OpenAPI paths, authenticated MFA smoke) — see `docs/operations/deployment-tomcat11.md` and the
  evidence comment on issue #1.

## Verification of the preview itself

`scripts/verify-preview.py` re-imports the fixture route table from `preview-api.py` and asserts:

1. all 23 exported pages return 200, carry `X-Infeara-Preview: fixtures`, and contain the banner;
2. all 45 fixture routes return 200 with `success: true`;
3. a write route returns `PREVIEW_ONLY`.

Current result: **69/69 checks pass.**

## Two failure modes worth remembering

- **Stale docroot.** An earlier preview served a `mktemp` directory from an orphaned
  `python3 -m http.server`. The directory was not part of the workspace snapshot, so the preview
  died while the process lived on. Previews now unpack under `dist/.preview` (gitignored, inside the
  workspace) — see `scripts/war-preview.sh`.
- **HTTP 200 is not "working".** The page and the API are separate requests. Any preview check must
  exercise the API call the page boots with, which is exactly what `verify-preview.py` does.
