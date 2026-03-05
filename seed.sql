-- EventLens Dev Seed Script
-- Creates a sample event_store table and populates it with bank account events
-- for immediate local development without needing an existing event source.

CREATE TABLE IF NOT EXISTS event_store (
    event_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id     VARCHAR(255) NOT NULL,
    aggregate_type   VARCHAR(255) NOT NULL,
    sequence_number  BIGINT       NOT NULL,
    event_type       VARCHAR(255) NOT NULL,
    payload          JSONB        NOT NULL,
    metadata         JSONB        NOT NULL DEFAULT '{}',
    timestamp        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    global_position  BIGSERIAL,
    UNIQUE (aggregate_id, sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_events_aggregate  ON event_store (aggregate_id, sequence_number);
CREATE INDEX IF NOT EXISTS idx_events_global     ON event_store (global_position);
CREATE INDEX IF NOT EXISTS idx_events_type       ON event_store (event_type);
CREATE INDEX IF NOT EXISTS idx_events_timestamp  ON event_store (timestamp);

-- Grant read-only access for eventlens (create user if it doesn't already exist)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'eventlens_reader') THEN
        CREATE ROLE eventlens_reader LOGIN PASSWORD 'readonly';
    END IF;
END
$$;
GRANT SELECT ON event_store TO eventlens_reader;

-- Seed: BankAccount ACC-001
INSERT INTO event_store (aggregate_id, aggregate_type, sequence_number, event_type, payload, metadata) VALUES
('ACC-001', 'BankAccount', 1, 'AccountCreated',   '{"accountHolder":"Alice Smith","balance":0}',   '{"correlationId":"tx-1","userId":"admin"}'),
('ACC-001', 'BankAccount', 2, 'MoneyDeposited',   '{"amount":1000}',                               '{"correlationId":"tx-2","userId":"alice"}'),
('ACC-001', 'BankAccount', 3, 'MoneyDeposited',   '{"amount":500}',                                '{"correlationId":"tx-3","userId":"alice"}'),
('ACC-001', 'BankAccount', 4, 'MoneyWithdrawn',   '{"amount":200}',                                '{"correlationId":"tx-4","userId":"alice"}'),
('ACC-001', 'BankAccount', 5, 'MoneyTransferred', '{"amount":100,"toAccount":"ACC-002"}',          '{"correlationId":"tx-5","userId":"alice"}'),
('ACC-001', 'BankAccount', 6, 'MoneyDeposited',   '{"amount":50}',                                 '{"correlationId":"tx-6","userId":"system"}');

-- Seed: BankAccount ACC-002 (goes negative — triggers anomaly)
INSERT INTO event_store (aggregate_id, aggregate_type, sequence_number, event_type, payload, metadata) VALUES
('ACC-002', 'BankAccount', 1, 'AccountCreated',   '{"accountHolder":"Bob Jones","balance":0}',     '{"correlationId":"tx-7","userId":"admin"}'),
('ACC-002', 'BankAccount', 2, 'MoneyDeposited',   '{"amount":200}',                                '{"correlationId":"tx-8","userId":"bob"}'),
('ACC-002', 'BankAccount', 3, 'MoneyWithdrawn',   '{"amount":300}',                                '{"correlationId":"tx-9","userId":"bob"}');

-- Seed: Order aggregate
INSERT INTO event_store (aggregate_id, aggregate_type, sequence_number, event_type, payload, metadata) VALUES
('ORD-001', 'Order', 1, 'OrderCreated',  '{"customerId":"CUST-1","items":[{"sku":"SKU-A","qty":2}],"total":49.99}', '{"correlationId":"ord-1"}'),
('ORD-001', 'Order', 2, 'OrderPaid',     '{"paymentRef":"PAY-XYZ","amount":49.99}',                                '{"correlationId":"ord-2"}'),
('ORD-001', 'Order', 3, 'OrderShipped',  '{"carrier":"DHL","trackingId":"1Z999AA10123456784"}',                    '{"correlationId":"ord-3"}'),
('ORD-001', 'Order', 4, 'OrderDelivered','{"deliveredAt":"2026-03-01T10:00:00Z"}',                                 '{"correlationId":"ord-4"}');
