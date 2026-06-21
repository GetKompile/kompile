# Kompile Project Store Server

A self-hostable Spring Boot app for hosting Kompile projects: Git Smart-HTTP transport, a
Hugging Face Xet content-addressed storage protocol, a project REST API, and a built-in
**project browser + download UI**.

## What's here

| Concern        | Where                                                            |
|----------------|-----------------------------------------------------------------|
| Project API    | `web/ProjectController` — list / get / tree / blob / **archive** |
| Git transport  | `git/GitHttpController` — `/git/{ns}/{slug}.git/...`             |
| Xet CAS        | `web/XetCasController`, `web/XetTokenController` — `/xet/cas/...` |
| Browser UI     | `src/main/frontend/` (Angular 19) → bundled to `static/`        |

## The UI

A lean Angular 19 app (same stack/theme as `kompile-app-main`) served from the app root (`/`):

- **Project browser** (`components/project-browser`) — searchable card grid of hosted projects.
- **Project detail + download** (`components/project-detail`) — clone commands (Git + Kompile
  CLI) with copy buttons, a one-click **Download ZIP** of the repo at a ref, the parsed
  `kompile.project.json` manifest, and a drill-down file browser with per-file downloads.

It talks only to the existing project REST API:

```
GET /api/projects                              # list
GET /api/projects/{ns}/{slug}                  # detail + manifest
GET /api/projects/{ns}/{slug}/tree/{ref}?path= # browse a directory
GET /api/projects/{ns}/{slug}/blob/{ref}?path= # download one file
GET /api/projects/{ns}/{slug}/archive/{ref}    # download the whole tree as a ZIP
```

## Build

The UI builds by default and is bundled into the jar's static resources.

```bash
# First build (or after package.json changes): also download Node/npm into the module
mvn -pl :kompile-project-store-server install -Dui.deps

# Subsequent builds (Node already installed)
mvn -pl :kompile-project-store-server install

# Backend only, skip the Angular build
mvn -pl :kompile-project-store-server install -Dskip.ui
```

Node/npm versions come from the root `pom.xml` (`node.version`, `npm.version`). The compiled
UI is copied to both `target/classes/static` (jar) and `src/main/resources/static`
(for `spring-boot:run` / IDE runs); both are git-ignored.

### Frontend dev loop

```bash
cd src/main/frontend
npm install
npm start      # ng serve on :4200, proxying /api,/git,/xet to :8088 (see proxy.conf.json)
```

## Run

```bash
mvn -pl :kompile-project-store-server spring-boot:run -Dskip.ui
# then open http://localhost:8088/
```

Config lives under `kompile.project-store-server.*` (data dir, CAS/git base URLs, token
secret) plus standard `server.port` and `spring.datasource.*` — see `application.properties`.
