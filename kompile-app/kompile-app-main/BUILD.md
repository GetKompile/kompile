# Build Instructions for kompile-app-main

This module contains the main Spring Boot application with an Angular frontend.

## Build Profiles

The POM uses Maven profiles inspired by the campaign-builder project for flexible UI builds.

### Default Build (with UI)

By default, the UI is **built automatically** when you run:

```bash
mvn clean package
```

or

```bash
mvn spring-boot:run
```

This will:
1. Install node and npm (if not already installed)
2. Run `npm install` to install frontend dependencies
3. Run `npm run build` to build the Angular application
4. Copy the built files to `target/classes/static/` and `src/main/resources/static/`
5. Compile the Java backend
6. Package everything together

### Skip UI Build (Backend Only)

For faster backend-only builds during development, skip the UI build with:

```bash
mvn clean package -Dskip.ui
```

or

```bash
mvn spring-boot:run -Dskip.ui
```

This is useful when:
- You haven't changed the frontend
- You want faster build times during backend development
- The frontend is already built in `src/main/resources/static/`

### Install/Update Node Dependencies

When you first clone the project or when `package.json` dependencies change:

```bash
mvn clean install -Dui.deps
```

This installs node and npm locally in the `target/` directory.

## Build Time Comparison

- **With UI**: ~10-15 seconds (depends on npm cache)
- **Without UI** (`-Dskip.ui`): ~2-3 seconds

## Frontend Development

For active frontend development, you can run the Angular dev server separately:

```bash
cd src/main/frontend
npm start
```

Then run the backend with:

```bash
mvn spring-boot:run -Dskip.ui
```

The Angular dev server (usually on port 4200) will proxy API requests to your backend.

## Profiles Summary

| Profile | Activated By | Purpose |
|---------|--------------|---------|
| `Build the UI` | Default (active unless `-Dskip.ui`) | Builds the Angular frontend and copies to static resources |
| `Install node and npm` | `-Dui.deps` | Installs node/npm in the project (first-time setup) |

## Port Configuration Fix

The frontend now dynamically detects the backend port at runtime. The API URL is constructed based on `window.location`:

- If running on port 8080: API calls go to `http://localhost:8080/api`
- If running on port 9090: API calls go to `http://localhost:9090/api`

To run on a custom port:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=9090"
```

Or via application.properties:

```properties
server.port=9090
```

**Important**: After changing the port or rebuilding the frontend, you must restart the Spring Boot application for the new static files to be served.
