## EventLens v2 Functional Test Scripts

This document describes manual functional tests for each implemented Epic in `README_V2_TASKS.md`.

Assumptions:

- API is running at `http://localhost:9090`
- Basic auth is disabled (default) unless otherwise stated
- The event store contains some sample data (via `seed.sql` or your own fixtures)

---

### EPIC 1: Security Hardening

#### 1.1 Environment Variable Interpolation in YAML

- **Goal**: Values like `${EVENTLENS_DB_PASSWORD}` in `eventlens.yaml` are resolved from the environment.
- **Steps**:
  1. Set an env var and start the app:
     ```bash
     set EVENTLENS_DB_PASSWORD=secret123   # Windows PowerShell: $env:EVENTLENS_DB_PASSWORD="secret123"
     java -jar eventlens-app/build/libs/eventlens.jar
     ```
  2. In `eventlens.yaml`, configure:
     ```yaml
     datasource:
       password: ${EVENTLENS_DB_PASSWORD}
     ```
  3. Verify the app starts and connects to Postgres (no startup error).
- **Expected**: With the correct env var, the app boots and connects; with an incorrect password, startup fails with a clear configuration/connection error.

#### 1.2 Config Validation at Startup

- **Goal**: Invalid configuration stops startup with a clear message.
- **Steps**:
  1. Break a required field in `eventlens.yaml`, e.g.:
     ```yaml
     datasource:
       url: "not-a-postgres-url"
     ```
  2. Start the app:
     ```bash
     java -jar eventlens-app/build/libs/eventlens.jar
     ```
- **Expected**: Process exits with a “configuration validation failed” style error that cites `datasource.url`.

#### 1.3 Security Headers Filter

- **Goal**: Security headers are present on API responses.
- **Steps**:
  ```bash
  curl -i http://localhost:9090/api/health
  ```
- **Expected headers**:
  - `X-Frame-Options: DENY`
  - `X-Content-Type-Options: nosniff`
  - `X-XSS-Protection: 1; mode=block`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - `Content-Security-Policy: default-src 'self'; ...`

#### 1.4 Rate Limiting

- **Goal**: Excess requests return `429` with rate limit headers.
- **Prep** in `eventlens.yaml`:
  ```yaml
  server:
    security:
      rate-limit:
        enabled: true
        requests-per-minute: 1
        burst: 1
  ```
- **Steps**:
  ```bash
  curl -i "http://localhost:9090/api/health"
  curl -i "http://localhost:9090/api/health"
  ```
- **Expected**:
  - 1st call: `200`, `X-RateLimit-Limit` and `X-RateLimit-Remaining` set.
  - 2nd call: `429`, `Retry-After` present, `X-RateLimit-Remaining: 0`.

#### 1.5 CORS Hardening

- **Goal**: Only configured origins can call the API.
- **Steps**:
  - Allowed origin:
    ```bash
    curl -i -H "Origin: http://localhost:5173" http://localhost:9090/api/health
    ```
    Expect `200` and `Access-Control-Allow-Origin: http://localhost:5173`.
  - Blocked origin:
    ```bash
    curl -i -H "Origin: http://evil.example" http://localhost:9090/api/health
    ```
    Expect `403` and body “Origin not allowed”.

#### 1.6 Input Validation & SQL Injection Prevention

- **Goal**: Invalid IDs are rejected; no SQL internals leak.
- **Steps**:
  ```bash
  curl -i "http://localhost:9090/api/aggregates/INVALID!!/timeline"
  ```
- **Expected**: `400` with JSON:
  ```json
  { "error": "validation_error", "field": "aggregate_id", ... }
  ```
  No raw SQL or table names in the response.

#### 1.8 Audit Logging (Log-Based)

- **Goal**: Sensitive actions produce structured audit log entries.
- **Steps**:
  1. Ensure audit logging is enabled (`audit.enabled: true`).
  2. Perform actions, e.g.:
     ```bash
     curl "http://localhost:9090/api/aggregates/search?q=test"
     ```
  3. Inspect `logs/audit.log`.
- **Expected**: JSON lines with `action` (e.g. `SEARCH`, `LOGIN`), `clientIp`, `requestId`, `userAgent`, and `resourceType`.

#### 1.9 Basic PII Masking

- **Goal**: API responses mask common PII.
- **Steps**:
  1. Insert events whose payloads include email/phone/credit-card numbers.
  2. Fetch timeline:
     ```bash
     curl "http://localhost:9090/api/aggregates/<id>/timeline"
     ```
- **Expected**: Sensitive values are replaced with masks (e.g. `***@***.***`, `****-****-****-****`), not raw values.

---

### EPIC 2: Performance & Scalability

#### 2.1 Cursor (Keyset) Pagination

- **Goal**: `/timeline` supports cursor-based pagination with `pagination` block.
- **Steps**:
  ```bash
  curl "http://localhost:9090/api/aggregates/<id>/timeline?limit=10"
  ```
- **Expected**:
  - JSON includes:
    ```json
    {
      "pagination": {
        "limit": 10,
        "hasMore": true,
        "nextCursor": "..."
      }
    }
    ```
- **Next page**:
  ```bash
  curl "http://localhost:9090/api/aggregates/<id>/timeline?limit=10&cursor=<nextCursor>"
  ```
  Should return the next window of events, not duplicates.

#### 2.2 HikariCP Connection Pool Tuning

- **Goal**: Pool sizing configured via `datasource.pool.*`.
- **Steps**:
  1. Configure:
     ```yaml
     datasource:
       pool:
         maximum-pool-size: 5
         minimum-idle: 1
     ```
  2. Start app and apply some concurrent load (e.g. multiple `curl` loops).
  3. Inspect `/api/v1/metrics` for `hikaricp_connections_*`.
- **Expected**: Metrics reflect configured max/min; no pool exhaustion or misconfiguration errors.

#### 2.3 Query Timeout Enforcement

- **Goal**: Long-running queries time out with structured `query_timeout` error.
- **Steps**:
  1. Set a low `datasource.query-timeout-seconds` (e.g. `1`).
  2. Trigger an expensive query (e.g. timeline across a huge dataset or custom slow view).
  3. Observe HTTP response.
- **Expected**: `504` with JSON:
  ```json
  {
    "error": "query_timeout",
    "message": "Query exceeded 1s timeout. ...",
    "timeoutSeconds": 1
  }
  ```

#### 2.4 Response Compression

- **Goal**: gzip compression for large JSON when client requests it.
- **Steps**:
  ```bash
  curl -I -H "Accept-Encoding: gzip" "http://localhost:9090/api/aggregates/<id>/timeline"
  ```
- **Expected**: `Content-Encoding: gzip` present, `Content-Type: application/json`.

#### 2.5 ETag / Conditional GET

- **Goal**: Avoid resending identical JSON payloads.
- **Steps**:
  1. Initial request:
     ```bash
     curl -i "http://localhost:9090/api/aggregates/<id>/timeline" | tee /tmp/timeline.txt
     ```
  2. Extract `ETag` from headers.
  3. Second request:
     ```bash
     curl -i -H "If-None-Match: <etag>" "http://localhost:9090/api/aggregates/<id>/timeline"
     ```
- **Expected**: Second call returns `304 Not Modified` with empty body.

#### 2.6 Async Export

- **Goal**: Large exports execute asynchronously and are downloadable when ready.
- **Steps**:
  1. Start export (v1):
     ```bash
     curl -X POST http://localhost:9090/api/v1/events/export \
       -H "Content-Type: application/json" \
       -d '{"aggregateId":"<id>","format":"json","limit":50000}'
     ```
  2. Poll status:
     ```bash
     curl http://localhost:9090/api/v1/events/export/<exportId>
     ```
  3. Download:
     ```bash
     curl -OJ http://localhost:9090/api/v1/events/export/<exportId>/download
     ```
- **Expected**: Status transitions from `RUNNING` to `COMPLETED`; download returns a file; audit log contains an `EXPORT` event.

---

### EPIC 3: Reliability & Resilience

#### 3.1 Circuit Breaker

- **Goal**: Repeated DB failures open a circuit and fail fast.
- **Steps**:
  1. Start app.
  2. Stop Postgres (`docker stop eventlens-pg`).
  3. Hit a DB endpoint repeatedly:
     ```bash
     for i in {1..10}; do \
       curl -s -o /dev/null -w "%{http_code}\n" \
         "http://localhost:9090/api/aggregates/search?q=x"; \
     done
     ```
- **Expected**: After a few attempts, responses quickly return `500` with a message indicating the circuit is open (from `ResilientEventStoreReader`).

#### 3.2 Graceful Shutdown

- **Goal**: SIGTERM drains requests and closes resources cleanly.
- **Steps**:
  1. Run app in foreground.
  2. Send SIGTERM or CTRL+C.
- **Expected logs**:
  - “Shutdown signal received. Draining in-flight requests…”
  - “Closing …” for resources.
  - “Shutdown complete.”

#### 3.3 Health Endpoints (Liveness + Readiness)

- **Goal**: Split liveness and readiness checks.
- **Steps**:
  ```bash
  curl http://localhost:9090/api/v1/health/live
  curl http://localhost:9090/api/v1/health/ready
  ```
  Then stop Postgres and call `ready` again.
- **Expected**:
  - `/live`: always `200`, `status: "UP"`.
  - `/ready`: `200` when all dependencies healthy; `503` and `status: "DOWN"` when Postgres is down.

---

### EPIC 4: Observability

#### 4.1 Prometheus Metrics Endpoint

- **Goal**: Metrics exposed for Prometheus scraping.
- **Steps**:
  ```bash
  curl http://localhost:9090/api/v1/metrics | head
  ```
- **Expected**:
  - Lines like:
    - `# HELP eventlens_http_requests_total ...`
    - `eventlens_http_requests_total{method="GET",path="/api/v1/aggregates/search",status="200"} ...`
  - JVM metrics: `jvm_memory_used_bytes`, `process_uptime_seconds`, etc.

#### 4.2 Structured JSON Logging

- **Goal**: Logs are JSON with MDC context.
- **Steps**:
  1. Ensure `LOG_FORMAT` env var is not set (defaults to `json`).
  2. Start app and hit some endpoints.
  3. Inspect console or `logs/eventlens.log`.
- **Expected**: Each log line is JSON with fields like:
  - `@timestamp`, `@version`, `message`, `logger_name`, `thread_name`
  - `requestId`, `userId`, `clientIp`, `method`, `path` in MDC.

---

### EPIC 5: API Versioning & OpenAPI

#### 5.1 API Versioning

- **Goal**: v1 routes under `/api/v1` and legacy `/api` routes are deprecated but functional.
- **Steps**:
  - New/v1 route:
    ```bash
    curl -i "http://localhost:9090/api/v1/aggregates/search?q=test"
    ```
    Expect `200` and **no** `Deprecation` header.
  - Legacy route:
    ```bash
    curl -i "http://localhost:9090/api/aggregates/search?q=test"
    ```
    Expect:
    - `200` response
    - Headers:
      - `Deprecation: true`
      - `Sunset: 2026-01-01`
      - `Link: </api/v1/aggregates/search>; rel="successor-version"`

#### 5.2 OpenAPI 3.1 Specification

- **Goal**: Serve a static OpenAPI 3.1 spec.
- **Steps**:
  ```bash
  curl http://localhost:9090/api/v1/openapi.json | jq '.openapi, .paths | keys'
  ```
- **Expected**:
  - `openapi` is `"3.1.0"`.
  - `paths` include:
    - `"/aggregates/search"`
    - `"/aggregates/{aggregateId}/timeline"`
    - `"/events/recent"`

---

### EPIC 6: Frontend Improvements

#### 6.1 Bookmarkable URLs with State

- **Goal**: Aggregate and event selection encoded in the URL.
- **Steps** (in browser):
  1. Open the UI, search for an aggregate, select it.
  2. Click an event in the timeline.
  3. Confirm the URL query string includes `?aggregateId=...&seq=...`.
  4. Refresh the page or paste the URL into a new tab.
- **Expected**: The UI restores the same aggregate and event selection after refresh.

#### 6.2 JSON Syntax Highlighting with Folding

- **Goal**: State JSON is readable and collapsible.
- **Steps**:
  1. With a selected aggregate and event, open `StateViewer`.
  2. For large states, confirm an **Expand/Collapse** control appears above `BEFORE`/`AFTER` JSON.
  3. Toggle it.
- **Expected**: Long JSON is truncated when collapsed and fully visible when expanded; text is monospaced and easy to read.

#### 6.3 Loading States and Error Boundaries

- **Goal**: The UI always shows clear loading/error states instead of blank screens.
- **Steps**:
  - Timeline:
    - Select an aggregate and observe a skeleton loader while data fetches.
  - StateViewer:
    - With replay in progress, confirm a skeleton placeholder appears until transitions load.
  - AnomalyPanel:
    - On initial load, see a skeleton then either “No anomalies detected” or a list of anomalies.
  - LiveStream:
    - Start the UI with the backend temporarily down; observe connection status (“connecting”, “disconnected”) and a toast saying “Live stream disconnected. Retrying…”.
  - Global ErrorBoundary:
    - (Optional) Induce a controlled React error and confirm the “Something went wrong” UI appears, not a blank page.

---

### EPIC 7: Deployment & Portability

#### 7.1 Multi-Arch Docker Image

- **Goal**: Build a multi-arch image and get a healthy container.
- **Steps**:
  1. Build multi-arch image with Buildx:
     ```bash
     docker buildx build --platform linux/amd64,linux/arm64 -t eventlens:multiarch .
     ```
  2. Run locally:
     ```bash
     docker run --rm -p 9090:9090 eventlens:multiarch
     ```
  3. Inspect health:
     ```bash
     docker inspect --format='{{json .State.Health}}' <container_id> | jq
     ```
- **Expected**:
  - Build succeeds for both amd64 and arm64.
  - Container health status reports `"Status": "healthy"` based on `/api/v1/health/live`.


## v3 Smoke Script

Use [scripts/v3-release-smoke.ps1](C:/Java%20Developer/EventDebug/scripts/v3-release-smoke.ps1) for a compact cross-phase release smoke run.
It is intentionally smaller than a script-per-bullet approach and relies on the automated test suite for the heavy lifting.
