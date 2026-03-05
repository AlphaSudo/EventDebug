package io.eventlens.cli;

import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.engine.*;
import io.eventlens.pg.PgEventStoreReader;
import picocli.CommandLine.*;

@Command(name = "bisect", description = "Binary search for the event that caused a condition")
public class BisectCommand implements Runnable {

    @Parameters(index = "0", description = "Aggregate ID")
    String aggregateId;

    @Option(names = "--where", required = true, description = "Condition expression, e.g. 'balance < 0'")
    String condition;

    @Mixin
    DatabaseOptions db;

    @Override
    public void run() {
        var reader = new PgEventStoreReader(db.toPgConfig());
        var engine = new ReplayEngine(reader, new ReducerRegistry());
        var bisect = new BisectEngine(engine, reader);

        var predicate = BisectEngine.parseCondition(condition);
        var result = bisect.bisect(aggregateId, predicate);

        System.out.println("→ " + result.summary());
        if (result.culpritEvent() != null) {
            System.out.printf("  Event:   %s (seq #%d)%n",
                    result.culpritEvent().eventType(),
                    result.culpritEvent().sequenceNumber());
            System.out.printf("  Time:    %s%n", result.culpritEvent().timestamp());
            System.out.printf("  Replays: %d%n", result.replaysPerformed());
            if (result.transition() != null) {
                System.out.println("  Changes: " + result.transition().diff());
            }
        }
    }
}
