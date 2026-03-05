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
docker-compose up -d
```

This starts:

- `postgres:16` on `localhost:5432` with:
  - database: `eventlens_dev`
  - user: `postgres`
  - password: `secret`
- `kafka` on `localhost:9092` (with a `zookeeper` sidecar)

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
- Enable basic auth and tighten CORS in `eventlens.yaml` for production deployments.
- Build and run the Docker image alongside your own PostgreSQL/Kafka infrastructure, or reuse the provided `docker-compose.yml` for local debugging.

