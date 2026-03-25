package io.eventlens.test;

import java.util.List;

public final class CanonicalEventSet {

    public static final String PRIMARY_AGGREGATE_ID = "ACC-001";
    public static final String SECONDARY_AGGREGATE_ID = "ACC-002";
    public static final String TERTIARY_AGGREGATE_ID = "ORD-001";
    public static final String SEARCH_TERM = "ACC";

    private CanonicalEventSet() {
    }

    public static List<SeedEvent> defaultEvents() {
        return List.of(
                new SeedEvent(PRIMARY_AGGREGATE_ID, "BankAccount", 1, "AccountCreated", "{\"balance\":0}", "{\"source\":\"contract\"}"),
                new SeedEvent(PRIMARY_AGGREGATE_ID, "BankAccount", 2, "MoneyDeposited", "{\"amount\":100}", "{\"source\":\"contract\"}"),
                new SeedEvent(PRIMARY_AGGREGATE_ID, "BankAccount", 3, "MoneyWithdrawn", "{\"amount\":40}", "{\"source\":\"contract\"}"),
                new SeedEvent(SECONDARY_AGGREGATE_ID, "BankAccount", 1, "AccountCreated", "{\"balance\":50}", "{\"source\":\"contract\"}"),
                new SeedEvent(TERTIARY_AGGREGATE_ID, "Order", 1, "OrderCreated", "{\"total\":99}", "{\"source\":\"contract\"}")
        );
    }

    public record SeedEvent(
            String aggregateId,
            String aggregateType,
            long sequenceNumber,
            String eventType,
            String payload,
            String metadata) {
    }
}
