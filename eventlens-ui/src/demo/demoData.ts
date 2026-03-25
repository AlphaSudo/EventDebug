import type {
    AnomalyReport,
    BisectResult,
    EventStatistics,
    FieldChange,
    ReplayResult,
    StateTransition,
    StoredEvent,
} from '../api/types';

export const DEMO_AGGREGATE_ID = 'order-demo-001';

/** Total synthetic events in the demo aggregate (1..N by sequence). */
export const DEMO_EVENT_COUNT = 100;

/** Synthetic anomaly cards shown in the panel. */
export const DEMO_ANOMALY_COUNT = 20;

function ev(
    eventId: string,
    sequenceNumber: number,
    eventType: string,
    timestamp: string,
    globalPosition: number,
    payload: string
): StoredEvent {
    return {
        eventId,
        aggregateId: DEMO_AGGREGATE_ID,
        aggregateType: 'ORDER',
        sequenceNumber,
        eventType,
        payload,
        metadata: JSON.stringify({ source: 'demo', correlationId: `corr-demo-${sequenceNumber}` }),
        timestamp,
        globalPosition,
    };
}

function buildDemoEvents(): StoredEvent[] {
    const out: StoredEvent[] = [];
    const t0 = Date.parse('2025-01-15T08:00:00.000Z');
    const g0 = 50_000;

    for (let seq = 1; seq <= DEMO_EVENT_COUNT; seq++) {
        const ts = new Date(t0 + seq * 45_000).toISOString();
        const gp = g0 + seq;
        let eventType: string;
        let payload: Record<string, unknown>;

        if (seq === 1) {
            eventType = 'ORDER_PLACED';
            payload = {
                customerId: 'cust-77',
                channel: 'web',
                status: 'PENDING',
                totalCents: 0,
                itemCount: 0,
            };
        } else if (seq >= 2 && seq <= 48) {
            eventType = 'LINE_ITEM_ADDED';
            const lineTotalCents = 350 + ((seq * 73) % 1200);
            payload = {
                sku: `SKU-${String(10000 + seq * 17).slice(-4)}`,
                qty: (seq % 4) + 1,
                lineTotalCents,
                lineIndex: seq - 1,
            };
        } else if (seq >= 49 && seq <= 58) {
            eventType = 'PAYMENT_PROGRESS';
            payload = {
                paymentId: `pay-chunk-${seq}`,
                amountCents: 1500 + seq * 120,
                balanceCents: Math.max(0, 48_000 - seq * 700),
            };
        } else if (seq >= 59 && seq <= 72) {
            const reasons = ['inventory', 'fraud_check', 'address_verify', 'manual_review', 'carrier_delay'];
            eventType = 'FULFILLMENT_BLOCKED';
            payload = {
                reason: reasons[seq % reasons.length],
                caseId: `CASE-${seq}`,
                retryAfterMinutes: 15 + (seq % 45),
            };
        } else if (seq >= 73 && seq <= 88) {
            eventType = 'SHIPMENT_EVENT';
            payload = {
                leg: seq - 72,
                carrier: seq % 3 === 0 ? 'FAST' : seq % 3 === 1 ? 'ECONOMY' : 'OVERNIGHT',
                status: 'IN_TRANSIT',
                trackingToken: `trk-${seq}${(seq * 7919).toString(36)}`,
            };
        } else if (seq >= 89 && seq <= 99) {
            eventType = 'NOTE_APPENDED';
            payload = {
                author: `agent-${(seq % 6) + 1}`,
                noteId: `n-${seq}`,
                preview: `Ops note #${seq}: SLA watch / customer ping`,
            };
        } else {
            eventType = 'REFUND_ISSUED';
            payload = {
                refundCents: 88_000,
                balanceCents: -12_500,
                reason: 'bulk_settlement_adjustment',
            };
        }

        out.push(ev(`evt-demo-${seq}`, seq, eventType, ts, gp, JSON.stringify(payload)));
    }
    return out;
}

const DEMO_EVENTS: StoredEvent[] = buildDemoEvents();

function buildDemoAnomalies(events: StoredEvent[]): AnomalyReport[] {
    const templates: { code: string; severity: AnomalyReport['severity']; description: string }[] = [
        { code: 'NEGATIVE_BALANCE', severity: 'HIGH', description: 'Ledger balance dropped below zero after refund batch' },
        { code: 'REFUND_EXCEEDS_CAPTURE', severity: 'CRITICAL', description: 'Cumulative refunds exceed captured payments for this aggregate' },
        { code: 'DUPLICATE_PAYMENT_CHUNK', severity: 'MEDIUM', description: 'Two payment chunks share the same window and amount fingerprint' },
        { code: 'LINE_ITEM_PRICE_OUTLIER', severity: 'LOW', description: 'Line total deviates >3σ from cohort for this SKU family' },
        { code: 'FULFILLMENT_STALL', severity: 'HIGH', description: 'Order blocked in fulfillment longer than SLA for channel' },
        { code: 'CARRIER_MISMATCH', severity: 'MEDIUM', description: 'Shipment leg carrier differs from preferred routing profile' },
        { code: 'MANUAL_REVIEW_BACKLOG', severity: 'LOW', description: 'Case reopened multiple times without resolution' },
        { code: 'VELOCITY_SPIKE', severity: 'HIGH', description: 'Event rate on this aggregate exceeded rolling baseline' },
        { code: 'ADDRESS_VERIFY_LOOP', severity: 'MEDIUM', description: 'Address verification failed three times with same payload hash' },
        { code: 'INVENTORY_HOLD', severity: 'MEDIUM', description: 'Inventory hold exceeded expected release window' },
        { code: 'FRAUD_SCORE_EDGE', severity: 'LOW', description: 'Fraud score landed in manual-review gray band' },
        { code: 'DISCOUNT_STACK', severity: 'LOW', description: 'Multiple discount signals present without explicit approval event' },
        { code: 'SHIPMENT_GAP', severity: 'HIGH', description: 'Missing scan between expected hub handoffs' },
        { code: 'NOTE_SPAM', severity: 'LOW', description: 'Unusually high operator notes density in short interval' },
        { code: 'PAYMENT_PARTIAL_CLUSTER', severity: 'MEDIUM', description: 'Several partial captures without closing settlement event' },
        { code: 'SKU_QUANTITY_ANOMALY', severity: 'MEDIUM', description: 'Quantity pattern inconsistent with historical order curve' },
        { code: 'CASE_ESCALATION', severity: 'HIGH', description: 'Support case escalated without prior tier-1 closure' },
        { code: 'TRACKING_TOKEN_REUSE', severity: 'CRITICAL', description: 'Tracking token collision across two concurrent legs' },
        { code: 'SLA_BREACH_RISK', severity: 'HIGH', description: 'Projected delivery crosses committed SLA if delay persists' },
        { code: 'SETTLEMENT_BATCH_DRIFT', severity: 'CRITICAL', description: 'Settlement batch totals diverge from summed payment chunks' },
    ];

    return templates.map((t, i) => {
        const seq = Math.min(DEMO_EVENT_COUNT, 5 + i * 5);
        const e = events.find(x => x.sequenceNumber === seq) ?? events[events.length - 1];
        return {
            code: t.code,
            description: t.description,
            severity: t.severity,
            aggregateId: DEMO_AGGREGATE_ID,
            atSequence: seq,
            triggeringEventType: e.eventType,
            timestamp: e.timestamp,
            stateAtAnomaly: {
                demoIndex: i + 1,
                atSequence: seq,
                code: t.code,
            },
        };
    });
}

const DEMO_ANOMALIES: AnomalyReport[] = buildDemoAnomalies(DEMO_EVENTS);

function applyReducer(state: Record<string, unknown>, event: StoredEvent): Record<string, unknown> {
    const next: Record<string, unknown> = { ...state };
    let payload: Record<string, unknown> = {};
    try {
        payload = JSON.parse(event.payload || '{}') as Record<string, unknown>;
    } catch {
        /* ignore */
    }
    next._version = event.sequenceNumber;
    next._lastEventType = event.eventType;
    next._lastUpdated = event.timestamp;
    const type = event.eventType.toLowerCase();
    if (
        type.includes('created') ||
        type.includes('opened') ||
        type.includes('placed') ||
        type.includes('submitted')
    ) {
        Object.assign(next, payload);
    } else if (
        type.includes('deleted') ||
        type.includes('closed') ||
        type.includes('cancelled') ||
        type.includes('rejected')
    ) {
        next.status = 'DELETED';
        Object.assign(next, payload);
    } else {
        Object.assign(next, payload);
    }
    return next;
}

function computeDiff(
    before: Record<string, unknown>,
    after: Record<string, unknown>
): Record<string, FieldChange> {
    const diff: Record<string, FieldChange> = {};
    for (const key of Object.keys(after)) {
        const oldVal = before[key];
        const newVal = after[key];
        if (JSON.stringify(oldVal) !== JSON.stringify(newVal)) {
            diff[key] = { oldValue: oldVal, newValue: newVal };
        }
    }
    for (const key of Object.keys(before)) {
        if (!(key in after)) {
            diff[key] = { oldValue: before[key], newValue: undefined };
        }
    }
    return diff;
}

function buildTransitions(events: StoredEvent[]): StateTransition[] {
    const out: StateTransition[] = [];
    let current: Record<string, unknown> = {};
    for (const event of events) {
        const stateBefore = { ...current };
        current = applyReducer(current, event);
        const stateAfter = { ...current };
        out.push({
            event,
            stateBefore,
            stateAfter,
            diff: computeDiff(stateBefore, stateAfter),
        });
    }
    return out;
}

const DEMO_TRANSITIONS = buildTransitions(DEMO_EVENTS);

/** Minimal parser for demo bisect — supports numeric comparisons on top-level fields. */
function parseDemoCondition(expression: string): ((state: Record<string, unknown>) => boolean) | null {
    const parts = expression.trim().split(/\s+/);
    if (parts.length !== 3) return null;
    const [field, op, raw] = parts;
    if (field.includes('.') || !/^[a-zA-Z_][\w]*$/.test(field)) return null;
    return state => {
        const v = state[field];
        if (v == null || typeof v !== 'number') return false;
        const expected = Number(raw);
        if (Number.isNaN(expected)) return false;
        return op === '<'
            ? v < expected
            : op === '<='
              ? v <= expected
              : op === '>'
                ? v > expected
                : op === '>='
                  ? v >= expected
                  : op === '=='
                    ? v === expected
                    : op === '!='
                      ? v !== expected
                      : false;
    };
}

export function demoBisect(expression: string): BisectResult {
    const pred = parseDemoCondition(expression);
    if (!pred) {
        return {
            aggregateId: DEMO_AGGREGATE_ID,
            culpritEvent: null,
            transition: null,
            replaysPerformed: 0,
            summary: 'Demo mode: use a simple numeric condition, e.g. balanceCents < 0',
        };
    }

    if (DEMO_EVENTS.length === 0) {
        return {
            aggregateId: DEMO_AGGREGATE_ID,
            culpritEvent: null,
            transition: null,
            replaysPerformed: 0,
            summary: 'No events found',
        };
    }

    const lastSeq = DEMO_EVENTS[DEMO_EVENTS.length - 1].sequenceNumber;
    const full = demoReplayTo(DEMO_AGGREGATE_ID, lastSeq);
    let replayCount = 1;
    if (!pred(full.state)) {
        return {
            aggregateId: DEMO_AGGREGATE_ID,
            culpritEvent: null,
            transition: null,
            replaysPerformed: replayCount,
            summary: 'Condition never becomes true in entire history',
        };
    }

    let low = 0;
    let high = DEMO_EVENTS.length - 1;
    let culprit: StoredEvent | null = null;

    while (low <= high) {
        const mid = Math.floor((low + high) / 2);
        const midSeq = DEMO_EVENTS[mid].sequenceNumber;
        const replay = demoReplayTo(DEMO_AGGREGATE_ID, midSeq);
        replayCount++;
        if (pred(replay.state)) {
            culprit = DEMO_EVENTS[mid];
            high = mid - 1;
        } else {
            low = mid + 1;
        }
    }

    let transition: StateTransition | null = null;
    if (culprit != null) {
        const seq = culprit.sequenceNumber;
        transition = DEMO_TRANSITIONS.find(t => t.event.sequenceNumber === seq) ?? null;
    }

    const summary =
        culprit != null
            ? `Event #${culprit.sequenceNumber} (${culprit.eventType} at ${culprit.timestamp}) first caused the condition`
            : 'Could not isolate culprit event';

    return {
        aggregateId: DEMO_AGGREGATE_ID,
        culpritEvent: culprit,
        transition,
        replaysPerformed: replayCount,
        summary,
    };
}

function demoMatchesSearch(query: string): boolean {
    const q = query.trim().toLowerCase();
    if (q.length < 2) return false;
    return q.includes('demo') || DEMO_AGGREGATE_ID.toLowerCase().includes(q);
}

export function demoSearchAggregates(query: string): string[] {
    return demoMatchesSearch(query) ? [DEMO_AGGREGATE_ID] : [];
}

export function demoAggregateTypes(): string[] {
    return ['ORDER'];
}

export function demoRecentEvents(limit: number): StoredEvent[] {
    const n = Math.min(Math.max(limit, 1), 500);
    const sorted = [...DEMO_EVENTS].sort((a, b) => b.globalPosition - a.globalPosition);
    return sorted.slice(0, n);
}

export function demoTimeline(id: string, limit: number, offset: number): { events: StoredEvent[]; totalEvents: number } {
    if (id !== DEMO_AGGREGATE_ID) {
        return { events: [], totalEvents: 0 };
    }
    const total = DEMO_EVENTS.length;
    const off = Math.max(0, offset);
    const lim = Math.min(Math.max(limit, 1), 1000);
    if (off >= total) return { events: [], totalEvents: total };
    return {
        events: DEMO_EVENTS.slice(off, off + lim),
        totalEvents: total,
    };
}

export function demoTransitions(id: string): StateTransition[] {
    return id === DEMO_AGGREGATE_ID ? DEMO_TRANSITIONS : [];
}

export function demoReplayTo(id: string, seq: number): ReplayResult {
    const slice = DEMO_EVENTS.filter(e => e.sequenceNumber <= seq);
    const transitions = buildTransitions(slice);
    const state =
        transitions.length > 0 ? { ...transitions[transitions.length - 1].stateAfter } : {};
    return {
        aggregateId: id,
        atSequence: seq,
        state,
        transitions,
    };
}

export function demoAnomalies(limit: number): AnomalyReport[] {
    const n = Math.min(Math.max(limit, 1), 500);
    return DEMO_ANOMALIES.slice(0, n);
}

/** Initial rows for the live stream panel (newest first, like typical tail). */
export function demoLiveStreamSeed(): StoredEvent[] {
    return [...DEMO_EVENTS].sort((a, b) => b.globalPosition - a.globalPosition).slice(0, 40);
}

export function demoHealth(): { status: string; version: string; demo: boolean } {
    return { status: 'UP', version: 'demo', demo: true };
}

export function demoStatistics(bucketHours = 1, maxBuckets = 24): EventStatistics {
    const bucketMs = Math.max(bucketHours, 1) * 60 * 60 * 1000;
    const sorted = [...DEMO_EVENTS].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp));
    const bucketCounts = new Map<string, number>();
    const eventTypes = new Map<string, number>();
    const aggregateTypes = new Map<string, number>();

    for (const event of sorted) {
        const bucketStart = new Date(Math.floor(Date.parse(event.timestamp) / bucketMs) * bucketMs).toISOString();
        bucketCounts.set(bucketStart, (bucketCounts.get(bucketStart) ?? 0) + 1);
        eventTypes.set(event.eventType, (eventTypes.get(event.eventType) ?? 0) + 1);
        aggregateTypes.set(event.aggregateType, (aggregateTypes.get(event.aggregateType) ?? 0) + 1);
    }

    return {
        totalEvents: DEMO_EVENTS.length,
        distinctAggregates: 1,
        eventTypes: [...eventTypes.entries()].map(([type, count]) => ({ type, count })),
        aggregateTypes: [...aggregateTypes.entries()].map(([type, count]) => ({ type, count })),
        throughput: [...bucketCounts.entries()]
            .slice(-Math.max(maxBuckets, 1))
            .map(([bucket, count]) => ({ bucket, count })),
        available: true,
        message: null,
    };
}
