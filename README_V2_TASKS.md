## EventLens v2 (“Bedrock”) — Implementation Checklist

This file is an **execution checklist** derived from `versions/v2.txt`.
It is meant to be updated as work lands in the repo.

### Status Legend

- [ ] Not started
- [~] In progress
- [x] Done

---

## EPIC 1: Security Hardening

- [x] **1.1 Environment Variable Interpolation in YAML**
- [x] **1.2 Config Validation at Startup**
- [x] **1.3 Security Headers Filter**
- [x] **1.4 Rate Limiting**
- [x] **1.5 CORS Hardening**
- [x] **1.6 Input Validation & SQL Injection Prevention**
- [-] **1.7 OIDC / OAuth2 Support (MOVED to v5 — not in v2)**
- [x] **1.8 Audit Logging (Log-Based)**
- [x] **1.9 Basic PII Masking**

## EPIC 2: Performance & Scalability

- [x] **2.1 Cursor (Keyset) Pagination**
- [x] **2.2 HikariCP Connection Pool Tuning**
- [x] **2.3 Query Timeout Enforcement**
- [x] **2.4 Response Compression**
- [x] **2.5 ETag / Conditional GET**
- [x] **2.6 Async Export**

## EPIC 3: Reliability & Resilience

- [x] **3.1 Circuit Breaker (Resilience4j)**
- [x] **3.2 Graceful Shutdown**
- [x] **3.3 Health Endpoints (Liveness + Readiness)**

## EPIC 4: Observability

- [x] **4.1 Prometheus Metrics Endpoint (Micrometer; trimmed histograms/labels to avoid large footprint)**
- [x] **4.2 Structured JSON Logging**

## EPIC 5: API Versioning & OpenAPI

- [x] **5.1 API Versioning**
- [x] **5.2 OpenAPI 3.1 Specification**

## EPIC 6: Frontend Improvements

- [ ] **6.1 Bookmarkable URLs with State**
- [ ] **6.2 JSON Syntax Highlighting with Folding**
- [ ] **6.3 Loading States and Error Boundaries**

## EPIC 7: Deployment & Portability

- [ ] **7.1 Multi-Arch Docker Image**
- [ ] **7.2 Basic Helm Chart**

## EPIC 8: Testing Infrastructure

- [ ] **8.1 OpenAPI Contract Tests**
- [ ] **8.2 Load Test Suite (k6)**
- [ ] **8.3 Dependency Vulnerability Scanning**

