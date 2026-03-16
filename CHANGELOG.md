# Changelog

All notable changes to EventLens are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [1.0.0] — 2026-03-16

### Added
- **Core engine** — `ReplayEngine`, `BisectEngine`, `DiffEngine`, `AnomalyDetector`, `ExportEngine`
- **PostgreSQL reader** — `PgEventStoreReader` with HikariCP connection pool (read-only)
- **Auto-schema detection** — `PgSchemaDetector` supports 12+ common event store table layouts
- **Custom column mapping** — `datasource.columns` config block for non-standard schemas
- **Kafka live tail** — real-time event streaming via `KafkaLiveTail` with virtual threads
- **PostgreSQL polling fallback** — automatic degradation to DB polling when Kafka is unavailable
- **REST API** — Javalin HTTP server with endpoints for timeline, replay, bisect, anomaly, export
- **WebSocket live tail** — browser clients receive events in real-time; 500-connection cap
- **React UI** — embedded Vite/TypeScript frontend served as static files from the fat JAR
- **CLI** — `serve`, `replay`, `bisect`, `diff`, `export` commands via PicoCLI
- **Export formats** — JSON, Markdown, CSV, JUnit fixture
- **Basic auth** — optional HTTP Basic Auth protecting `/api/*` and `/ws/*`
- **Configurable CORS** — restrict allowed origins via `server.allowed-origins`
- **Docker support** — multi-stage Dockerfile and Docker Compose with PostgreSQL + Kafka
- **Kubernetes support** — `k8s-eventlens.yaml` with ConfigMap, Deployment (with probes + resource limits), and NodePort Service
- **Custom reducers** — plug in domain-specific `AggregateReducer` via JAR / ServiceLoader

### Security
- `PgEventStoreReader` enforces `readOnly=true` on the HikariCP pool — never writes to the event store
- All SQL uses `PreparedStatement` with quoted identifiers to prevent injection
- `eventlens.yaml` is gitignored; credentials are never committed
- Bisect condition expressions are whitelist-validated before evaluation

### Performance
- Caffeine LRU cache on `ReplayEngine` (1,000 entries, 1-min TTL)
- O(log n) binary search bisect algorithm
- Anomaly scan capped at `max-aggregates-per-scan` (default: 20) to prevent O(n²) blowup
- All API `limit` parameters capped at 1,000 to prevent unbounded DB queries
