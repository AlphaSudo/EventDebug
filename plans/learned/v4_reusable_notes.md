# v4 Reusable Notes (Session Learnings)

## Purpose
Capture reusable implementation patterns, risk controls, and test gates from the v4 execution work so future UI-heavy releases can reuse proven patterns instead of rediscovering them.

## Reusable Rule Set
- Preserve current source-aware behavior while adding richer panels; new UI state must never silently drop the selected source.
- Keep new capabilities additive: extend existing routes/contracts where possible instead of replacing working v3 surfaces.
- Prefer local computation for UI comparison workflows when replay data is already present; avoid unnecessary server round-trips.
- When introducing background work, keep degradation graceful and visible rather than surfacing generic errors.

## Keep Updating During This Session
- Add pitfalls encountered while implementing each epic.
- Add reusable fixes that can apply across panels, keyboard flows, or worker/background processing.
- Add a final done-criteria checklist once the browser gate is stable.

## Initial Implementation Notes
- Reused the existing v3 source-aware shell instead of introducing a client router; `window.location.hash` remained sufficient for timeline, stats, and plugin views.
- Structural compare mode works best as a client-side worker over replay snapshots that are already loaded; this avoids adding a second comparison API just to support ad hoc event-to-event inspection.
- The stats SPI stayed backward compatible by using a default method that returns an unavailable payload, which let Postgres and MySQL opt in without breaking other plugins.
- Bounded queued WebSocket delivery is lower risk than changing the client wire format; batching can happen server-side while each message still stays a normal event payload.

## What We Learned So Far
- Virtualization should not be treated as permission to flatten existing interaction models; in this codebase the grouped-run timeline behavior is product logic, not just presentation.
- The safer refactor path for the timeline was: keep the segment/group model, then virtualize rendered rows derived from that model.
- Replay sharing is clearer when panel intent is encoded explicitly; `seq` alone was not enough, so v4 now persists both `seq` and `panel` in the URL.
- Browser-test infrastructure is easier to maintain when the specs live inside the package that owns the Playwright dependency; moving the tests under `eventlens-ui/tests/e2e` fixed module resolution cleanly.
- Frontend unit coverage for worker-backed features is easiest when the expensive logic is extracted into pure utilities first and the worker becomes a transport wrapper.
- Backpressure verification should assert on retained latest events, not on exact flush timing; the resilient assertion is that oldest burst messages are dropped while the newest sequence survives.
- Vitest and Playwright need separate discovery boundaries once both live under the same package; the stable fix was a dedicated `vitest.config.ts` that includes `src/**/*.test.ts` and excludes `tests/e2e/**`.
- Verification steps can generate real runtime artifacts like async export files and Playwright traces; those should be cleaned before commit so the checkpoint contains implementation, not test residue.
- When a datasource disappears from `/api/v1/datasources`, that usually means it never registered from config; a failed datasource should still appear in the list with a degraded or failed status, so complete absence points to config shape or loading, not UI filtering.
- For fat-jar plugin systems, class presence is not enough. The real runtime check is the merged `META-INF/services/*` entries inside the final artifact. In this case the MySQL plugin class and JDBC driver class existed, but the service descriptors were incomplete, which made runtime behavior look like a driver removal.
- Shadow service-file transforms are easy to misconfigure in Kotlin DSL. An explicit app-level `META-INF/services/io.eventlens.spi.EventSourcePlugin` and `META-INF/services/java.sql.Driver` file was safer than relying on inferred merge behavior for this project.
- The fastest Docker diagnosis pattern here was: inspect the live config inside the running container, inspect `/api/v1/datasources`, and then inspect container startup logs. That sequence cleanly separated packaging issues, commented-out config, YAML syntax errors, and actual datasource initialization failures.
- For YAML-backed multi-datasource configs, a datasource can move from "missing" to "crash loop" simply by uncommenting it with broken indentation. Once a log points to a line like `type: mysql` with `mapping values are not allowed here`, treat it as a pure YAML structure issue before debugging the datasource itself.

## Verification Notes
- `./gradlew.bat check` now executes backend verification, frontend build, and frontend unit tests through the normal Gradle path.
- Browser coverage is wired into GitHub Actions through `:eventlens-ui:npmTestE2e` after a dedicated Playwright Chromium install step.
- Local Playwright execution on this machine failed because the Chromium browser binary is not installed yet; this is an environment prerequisite, not an application failure.
- CSV export verification should cover both the synchronous aggregate route and the asynchronous export job route because both remain user-visible v4 surfaces.

## Remaining Gaps
- The codebase still has only one integrated v4 commit instead of the original per-epic commit discipline.
- Local browser verification still depends on installing Playwright Chromium on the workstation that runs the tests.
