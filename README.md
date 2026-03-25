## EventLens – Drop‑in Event Store Debugger

[![Build Status](https://github.com/AlphaSudo/EventDebug/actions/workflows/build.yml/badge.svg)](https://github.com/AlphaSudo/EventDebug/actions/workflows/build.yml)
[![GitHub Release](https://img.shields.io/github/v/release/AlphaSudo/EventDebug?sort=semver)](https://github.com/AlphaSudo/EventDebug/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
EventLens is a **read‑only dashboard** for event‑sourced systems. It connects to your **PostgreSQL event store** (and optionally **Kafka**) and gives you:

- **Timeline** of events for any aggregate
- **Search** across event types and IDs
- **Anomalies** based on simple rules
- **Export** of raw events for debugging / analytics
- **Live Event Stream** from Kafka (optional)

It never mutates data – it only **reads** from your database and (optionally) a Kafka topic.

---
<img width="1920" height="918" alt="Screenshot (192)" src="https://github.com/user-attachments/assets/b8bba512-e96f-45ea-b048-209e066ef77f" />

## 1. Quick Start – TL;DR

1. **Expose your events via a Postgres view** called `eventlens_events` with the required columns.
2. **Create an EventLens config** (you already have `eventlens.yaml` in this repo – use it as a template).
3. **Run the EventLens container** (typically via `docker compose`), mounting the config file.
4. **Open the UI** at `http://localhost:9090` and start exploring your events.

The rest of this README explains each step in detail.

---

## 2. Event Store Requirements

### 2.1 Required PostgreSQL view

EventLens expects a **read‑only view** (or table) with the following conceptual columns:

- **`event_id`** – globally unique ID, monotonically increasing per stream (often the primary key).
- **`aggregate_id`** – string ID of the aggregate / entity.
- **`aggregate_type`** – domain type (e.g. `ORDER`, `USER`).
- **`sequence_number`** – version within the aggregate’s stream (1, 2, 3, …).
- **`event_type`** – logical event type (e.g. `ORDER_PLACED`).
- **`payload`** – JSON body of the domain event.
- **`metadata`** – JSON with headers, correlation IDs, etc. (`{}` is fine).
- **`timestamp`** – event creation time (`timestamptz` or epoch seconds).
- **`global_position`** – total ordering across all events (often the same as `event_id`).

If your existing event table has a different shape, create a **view** that maps it to this schema. Example:

```sql
CREATE OR REPLACE VIEW eventlens_events AS
SELECT
    e.id                               AS event_id,
    e.aggregate_id::text               AS aggregate_id,
    e.aggregate_type                   AS aggregate_type,
    e.version                          AS sequence_number,
    e.event_type                       AS event_type,
    e.json_data                        AS payload,
    '{}'::jsonb                        AS metadata,
    COALESCE(
        (e.json_data::jsonb->>'createdDate')::timestamptz,
        e.created_at,
        CURRENT_TIMESTAMP
    )                                  AS timestamp,
    e.id                               AS global_position
FROM your_event_table e;
```

**Key rules:**

- Keep it **read‑only** (view only, no triggers).
- Do **not** change your existing write model – only project into this view.

---

## 3. EventLens Configuration

EventLens loads configuration from **one YAML file**. In this project you already have an example file:

- `eventlens.yaml` – sample config for local / Docker use

You can place your active config in one of these locations:

- Working directory (easiest for Docker): `./eventlens.yaml`
- User config: `~/.eventlens/config.yaml`
- System config: `/etc/eventlens/config.yaml`

Below is a minimal configuration you can adapt (Postgres only, no Kafka):

```yaml
# EventLens Configuration
server:
  port: 9090
  allowed-origins:
    - "http://localhost:9090"
  auth:
    enabled: false        # Turn on + set username/password in shared environments

datasource:
  url: jdbc:postgresql://postgres:5432/your_db_name
  username: your_user
  password: your_password
  table: eventlens_events  # View created in section 2
  columns:
    event-id: event_id
    aggregate-id: aggregate_id
    aggregate-type: aggregate_type
    sequence: sequence_number
    event-type: event_type
    payload: payload
    timestamp: timestamp
    global-position: global_position
```

### 3.1 Optional Kafka Live Event Stream

If you have Kafka and want live updates, add:

```yaml
kafka:
  bootstrap-servers: your-kafka:9092
  topic: your-events-topic
```

Recommended **Kafka message JSON shape**:

```json
{
  "event_id": 123,
  "aggregate_id": "1ffe55a0-08fa-4109-bec9-55c35dd879a4",
  "aggregate_type": "ORDER",
  "sequence_number": 3,
  "event_type": "ORDER_COMPLETED",
  "payload": {
    "aggregateId": "1ffe55a0-08fa-4109-bec9-55c35dd879a4",
    "version": 3,
    "createdDate": "2026-03-14T14:50:50.115861751Z",
    "eventType": "ORDER_COMPLETED"
  },
  "metadata": {},
  "timestamp": 1773499850.115862,
  "global_position": 123
}
```

Typical pattern:

- After writing to the Postgres event store, **publish** a Kafka message built from the same event row.
- Make the Kafka JSON match the `eventlens_events` view fields.

### 3.2 Replay and Anomalies (optional)

The config can also define **replay reducers** and **anomaly rules**. From `eventlens.yaml`:

```yaml
replay:
  default-reducer: generic     # "generic" | classpath
  reducers:
    # BankAccount: com.myapp.reducers.BankAccountReducer

anomaly:
  scan-interval-seconds: 60
  rules:
    - code: NEGATIVE_BALANCE
      condition: "balance < 0"
      severity: HIGH
    - code: LARGE_WITHDRAWAL
      condition: "amount > 10000"
      severity: MEDIUM
```

You can start with the defaults and add rules later as your domain model evolves.

---

## 4. Running EventLens with Docker Compose

The simplest way to run EventLens in a project is via **Docker Compose**.

Add a service like this to your existing `docker-compose.yml` (or create one if needed):

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: your_db_name
      POSTGRES_USER: your_user
      POSTGRES_PASSWORD: your_password
    ports:
      - "5432:5432"

  # Optional: your Kafka stack here...
  # kafka:
  #   image: bitnami/kafka:latest
  #   ...

  eventlens:
    image: alphasudo2/eventlens-app:latest
    restart: on-failure
    environment:
      EVENTLENS_CONFIG: /app/eventlens.yaml
    volumes:
      - ./eventlens.yaml:/app/eventlens.yaml:ro   # Mount your config read-only
    ports:
      - "9090:9090"
    depends_on:
      postgres:
        condition: service_healthy  # Wait until postgres is ready
      # kafka:
      #  condition: service_healthy  # Uncomment if you use Kafka Live Stream
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:9090/api/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s
```

Then start everything:

```bash
docker compose up -d
```

Open the UI in your browser:

```text
http://localhost:9090
```

---

## 5. Using EventLens in Your Project

### 5.1 Timeline / Search / Anomalies / Export

These features are backed by **Postgres** via the `eventlens_events` view and represent your **source of truth**:

- **Timeline** – inspect events for a single aggregate over time.
- **Search** – find events by type, ID, or filters.
- **Anomalies** – see events that match anomaly rules from the config.
- **Export** – download raw events for offline analysis.

To verify everything is wired correctly:

1. Go to `http://localhost:9090`.
2. Use the search or timeline view for a known `aggregate_id`.
3. You should see the same events that exist in your Postgres event store.

### 5.2 Live Event Stream (Kafka)

When `kafka` is configured and reachable:

1. Trigger a new event in your application (e.g. create an order, transfer money, etc.).
2. Open the **Live Event Stream** tab.
3. A new row should appear for each new event published to the configured Kafka topic.

You can double‑check Kafka messages manually, for example with `kafka-console-consumer`, and confirm their JSON matches the schema in section 3.1.

### 5.3 Consistency model

- Postgres (via `eventlens_events`) is the **system of record**.
- Kafka is used only for **live streaming** into the UI.
- Your application is responsible for the **dual‑write**:
  - Write to Postgres.
  - Then publish a corresponding Kafka message.

EventLens **does not** reconcile or repair differences between the database and Kafka.

---

## 6. Security and Deployment Notes

- **Authentication**  
  In the `server.auth` block you can enable basic auth:

  ```yaml
  server:
    auth:
      enabled: true
      username: admin
      password: changeme
  ```

  > ⚠️ **HTTPS required for Basic Auth in production.**  
  > Basic Auth transmits credentials as Base64-encoded HTTP headers. Without TLS, they are readable in transit.
  > Use a reverse proxy (nginx, Traefik, Caddy) with HTTPS in front of EventLens when `auth.enabled: true`.

  Use stronger credentials and secrets management in real deployments.

- **CORS / Frontend access**  
  Restrict `server.allowed-origins` in production to the domains that should reach your dashboard.

- **API request limits**  
  All list endpoints automatically cap results at **1,000 records per request** on the server side
  (timeline, search, recent events, anomaly scan). Use `limit` + `offset` pagination for larger datasets.

- **Config locations**  
  For non‑Docker environments, place the YAML config in one of:
  - `./eventlens.yaml`
  - `~/.eventlens/config.yaml`
  - `/etc/eventlens/config.yaml`

---

## 7. Integrating into a New Project – Checklist

Use this checklist when adding EventLens to any event‑sourced system:

1. **Database**
   - [ ] Identify your existing event table(s).
   - [ ] Create a Postgres view named `eventlens_events` with the required columns.
2. **Config**
   - [ ] Copy `eventlens.yaml` into your project and adjust:
     - [ ] `datasource.url`, `username`, `password`.
     - [ ] `datasource.table` (usually `eventlens_events`).
     - [ ] `columns` mappings, if your column names differ.
   - [ ] (Optional) Add `kafka.bootstrap-servers` and `kafka.topic`.
   - [ ] (Optional) Configure `anomaly` rules and `replay` reducers.
3. **Runtime**
   - [ ] Add the `eventlens` service to `docker-compose.yml` (or equivalent).
   - [ ] Mount the config file into `/app/eventlens.yaml`.
   - [ ] Expose port `9090`.
4. **Verification**
   - [ ] Start containers: `docker compose up -d`.
   - [ ] Open `http://localhost:9090`.
   - [ ] Confirm timeline/search show events from your Postgres event store.
   - [ ] (If using Kafka) Confirm Live Event Stream updates when new events occur.

Once this checklist passes, your team has a **self‑service event debugger** they can rely on for day‑to‑day diagnostics, investigations, and domain exploration.

# EventLens — Event Store Visual Debugger & Time Machine

EventLens is a **read-only, zero-intrusion visual debugger** for PostgreSQL event stores (with optional Kafka live tail). It lets you:

- inspect aggregate timelines,
- replay state at any point in time,
- run bisect-style searches,
- detect anomalies,
- and stream new events live via WebSocket + React UI.

This document focuses on a **5-minute quick-start**, configuration via `eventlens.yaml`, and production-friendly DB/index guidance.

---

## 1. 5‑Minute Quick Start

### 1.1 Prerequisites

- **JDK 21+**
- **Node.js 18+** (only needed if you are building from source)
- **Docker** (for PostgreSQL + Kafka via `docker-compose`)
- **Git** and **Gradle wrapper** (`./gradlew`) are already in the repo

### 1.2 Start PostgreSQL + Kafka (dev)

From the project root:

```bash
docker compose up -d
```

This starts:

- `postgres:16` on `localhost:5432` with:
  - database: `eventlens_dev`
  - user: `postgres`
  - password: `secret`
- `kafka` on `localhost:9092`

> **Note:** The `app` service now waits for both `postgres` and `kafka` to pass their health checks
> before starting (using `depends_on: condition: service_healthy`). This means the first start may
> take ~15–30 seconds while Kafka initialises.

If `seed.sql` is present in the repo, it is applied automatically to the `eventlens_dev` database at container start.

### 1.3 Build the Fat JAR + UI

From the project root:

```bash
./gradlew clean :eventlens-app:shadowJar
```

This will:

- build all core modules,
- run the React/Vite build (`eventlens-ui` → `eventlens-api/src/main/resources/web`),
- assemble `eventlens-app/build/libs/eventlens.jar`.

### 1.4 Run EventLens (local JVM)

From the project root:

```bash
java --enable-preview -jar eventlens-app/build/libs/eventlens.jar
```

Then open the UI in your browser:

- `http://localhost:9090`

The server reads configuration from `eventlens.yaml` (see below) using the `server.port` and `datasource` settings.

### 1.5 Run EventLens via Docker

Build the production image:

```bash
docker build -t eventlens:latest .
```

Run it, pointing to the config file:

```bash
docker run --rm \
  -p 9090:9090 \
  -v "$(pwd)/eventlens.yaml:/app/eventlens.yaml:ro" \
  --name eventlens \
  eventlens:latest
```

The UI will be available at `http://localhost:9090`.

---

## 2. Configuration Reference (`eventlens.yaml`)

EventLens is configured via a YAML file. The repository includes a sample `eventlens.yaml` at the root.

The effective search order is:

1. `EVENTLENS_CONFIG` env var (if set), e.g. `/app/eventlens.yaml`
2. `./eventlens.yaml` in the working directory
3. User-level config: `~/.eventlens/config.yaml`
4. System-level config: `/etc/eventlens/config.yaml`

### 2.1 Server & CORS

```yaml
server:
  port: 9090

  allowed-origins:
    - "http://localhost:5173"   # Vite dev server
    - "http://localhost:9090"   # Embedded server

  auth:
    enabled: false
    username: admin
    password: changeme
```

- **`port`**: HTTP server port for the API and bundled UI.
- **`allowed-origins`**: CORS whitelist for the browser UI.
  - In **development**, using `"*"` is acceptable.
  - In **production**, restrict this to your real UI origins.
- **`auth.enabled`**: If `true`, basic auth is enforced on `/api/*` routes.

### 2.2 PostgreSQL Datasource

```yaml
datasource:
  url: jdbc:postgresql://localhost:5432/eventlens_dev
  username: postgres
  password: secret
  table:
```

- **`url`**: JDBC URL for the event store database.
- **`username` / `password`**: Credentials for a **read-only** Postgres role (see section 3).
- **`table`**: Optional. If omitted, EventLens attempts to auto-detect the events table schema.

### 2.3 Kafka (Optional Live Tail)

```yaml
kafka:
  bootstrap-servers: localhost:9092
  topic: domain-events
```

- Remove or comment out the `kafka` section to disable Kafka.
- When enabled, EventLens uses `KafkaEventMapper` and `KafkaLiveTail` to stream events into the UI.

### 2.4 Replay & Reducers

```yaml
replay:
  default-reducer: generic     # "generic" | classpath
  reducers:
    # BankAccount: com.myapp.reducers.BankAccountReducer
```

- **`default-reducer`**:
  - `generic`: Use the built-in JSON-merge reducer.
  - `classpath`: Load custom reducers from the classpath (see `ClasspathReducerLoader`).
- **`reducers`**: Optional mapping from aggregate type → fully qualified reducer class.

### 2.5 Anomaly Detection

```yaml
anomaly:
  scan-interval-seconds: 60
  rules:
    - code: NEGATIVE_BALANCE
      condition: "balance < 0"
      severity: HIGH
    - code: LARGE_WITHDRAWAL
      condition: "amount > 10000"
      severity: MEDIUM
```

- **`scan-interval-seconds`**: Background anomaly scan rate.
- Each rule:
  - `code`: Stable identifier for the anomaly.
  - `condition`: Expression evaluated against replayed state (sanitized by the bisect parser).
  - `severity`: Enum such as `LOW`, `MEDIUM`, `HIGH`.

### 2.6 UI Options

```yaml
ui:
  theme: dark                  # dark | light
```

---

## 3. PostgreSQL: Read‑Only User Setup

For production, EventLens should connect using a **read-only** database user.

Assuming your primary owner role is `app_owner` and the events are in schema `public`:

```sql
-- 1) Create a dedicated read-only role
CREATE ROLE eventlens_ro LOGIN PASSWORD 'strong_password_here';

-- 2) Grant connect on the database
GRANT CONNECT ON DATABASE eventlens_dev TO eventlens_ro;

-- 3) Grant usage on relevant schemas
GRANT USAGE ON SCHEMA public TO eventlens_ro;

-- 4) Grant select on existing tables
GRANT SELECT ON ALL TABLES IN SCHEMA public TO eventlens_ro;

-- 5) Ensure future tables are also selectable
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO eventlens_ro;
```

Then update `eventlens.yaml`:

```yaml
datasource:
  url: jdbc:postgresql://your-prod-host:5432/your_prod_db
  username: eventlens_ro
  password: strong_password_here
```

---

## 4. DB Index Recommendations

EventLens primarily runs **read-only queries** against your event store. To keep replays and timelines fast, ensure:

1. **Primary event table indexes**

   For a typical event store table:

   ```sql
   CREATE TABLE domain_events (
     id           BIGSERIAL PRIMARY KEY,
     aggregate_id TEXT        NOT NULL,
     aggregate_type TEXT      NOT NULL,
     sequence     BIGINT      NOT NULL,
     occurred_at  TIMESTAMPTZ NOT NULL,
     payload      JSONB       NOT NULL
   );
   ```

   Recommended indexes:

   ```sql
   -- Lookup all events for a single aggregate, in order
   CREATE INDEX IF NOT EXISTS idx_domain_events_agg_seq
     ON domain_events (aggregate_type, aggregate_id, sequence);

   -- Time-based queries / live windows
   CREATE INDEX IF NOT EXISTS idx_domain_events_occurred_at
     ON domain_events (occurred_at);
   ```

2. **Partial / functional indexes** (optional)

   If EventLens frequently searches or filters on JSON fields (e.g. `payload->>'status'`), consider:

   ```sql
   CREATE INDEX IF NOT EXISTS idx_domain_events_status
     ON domain_events ((payload->>'status'));
   ```

3. **Analyze and vacuum**

   Ensure autovacuum and auto-analyze are functioning, or run:

   ```sql
   ANALYZE domain_events;
   ```

---

## 5. Health Check & Observability

- **Health endpoint**: `GET /api/health`
  - Verifies:
    - DB connectivity,
    - event store statistics,
    - Kafka consumer status (if enabled).
- **Logging**:
  - Controlled via `logback.xml` in `eventlens-core`.
  - Includes:
    - connection events,
    - replay durations,
    - anomaly results,
    - WebSocket lifecycle events.

In containerized environments, direct logs to stdout/stderr and collect them via your platform (Kubernetes, ECS, Docker logs, etc.).

---

## 6. Development Tips

- **Run only the UI in dev mode**:

  ```bash
  cd eventlens-ui
  npm install
  npm run dev
  ```

  Then point the dev UI at an already running API server (`server.allowed-origins` should include the Vite origin).

- **Testcontainers-based tests**:
  - Integration tests for PostgreSQL and Kafka use Testcontainers and require Docker to be running.

---

## 7. Next Steps

- Configure a **read-only** Postgres user in your environment.
- Add or adjust indexes to match your event table shape.
- Enable basic auth **behind an HTTPS reverse proxy** in `eventlens.yaml` for production deployments.
- Build and run the Docker image alongside your own PostgreSQL/Kafka infrastructure, or reuse the provided `docker-compose.yml` for local debugging.

---

## Project Info

| File | Description |
|------|-------------|
| [LICENSE](LICENSE) | MIT License |
| [CHANGELOG.md](CHANGELOG.md) | Version history and release notes |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Build, test, and PR guidelines |
| [eventlens.yaml.example](eventlens.yaml.example) | Annotated config template |

## v3 Plugin Docs

- [Plugin authoring guide](C:/Java%20Developer/EventDebug/docs/plugin-authoring.md)
- [v3 GA checklist](C:/Java%20Developer/EventDebug/docs/v3-ga-checklist.md)
- [SPI README](C:/Java%20Developer/EventDebug/eventlens-spi/README.md)

## v3 Release Smoke

Run the compact cross-phase smoke gate with:

`ash
pwsh ./scripts/v3-release-smoke.ps1
`

This verifies key v3 evidence files are present and runs 	est + check as the release gate.
