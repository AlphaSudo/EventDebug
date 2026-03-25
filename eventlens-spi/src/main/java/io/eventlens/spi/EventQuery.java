package io.eventlens.spi;

import java.time.Instant;
import java.util.Objects;

public record EventQuery(
        QueryType type,
        String aggregateId,
        String aggregateType,
        String eventType,
        Instant from,
        Instant to,
        int limit,
        String cursor,
        Fields fields
) {
    public enum QueryType {
        TIMELINE,
        SEARCH
    }

    public enum Fields {
        ALL,
        METADATA
    }

    public EventQuery {
        Objects.requireNonNull(type, "type");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must be <= to");
        }
        if (fields == null) {
            fields = Fields.ALL;
        }
    }

    public static Builder builder(QueryType type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final QueryType type;
        private String aggregateId;
        private String aggregateType;
        private String eventType;
        private Instant from;
        private Instant to;
        private int limit = 100;
        private String cursor;
        private Fields fields = Fields.ALL;

        private Builder(QueryType type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder aggregateId(String aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        public Builder aggregateType(String aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder from(Instant from) {
            this.from = from;
            return this;
        }

        public Builder to(Instant to) {
            this.to = to;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder cursor(String cursor) {
            this.cursor = cursor;
            return this;
        }

        public Builder fields(Fields fields) {
            this.fields = fields;
            return this;
        }

        public EventQuery build() {
            return new EventQuery(
                    type,
                    aggregateId,
                    aggregateType,
                    eventType,
                    from,
                    to,
                    limit,
                    cursor,
                    fields
            );
        }
    }
}
