package io.eventlens.cli;

import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.engine.*;
import io.eventlens.pg.PgEventStoreReader;
import picocli.CommandLine.*;

@Command(name = "diff", description = "Compare the final states of two aggregates")
public class DiffCommand implements Runnable {

    @Parameters(index = "0", description = "First aggregate ID")
    String aggregateIdA;

    @Parameters(index = "1", description = "Second aggregate ID")
    String aggregateIdB;

    @Mixin
    DatabaseOptions db;

    @Override
    public void run() {
        var reader = new PgEventStoreReader(db.toPgConfig());
        var engine = new ReplayEngine(reader, new ReducerRegistry());
        var diffEngine = new DiffEngine(engine);

        var diff = diffEngine.diff(aggregateIdA, aggregateIdB);
        if (diff.isEmpty()) {
            System.out.println("No differences found between '" + aggregateIdA + "' and '" + aggregateIdB + "'");
        } else {
            System.out.printf("Diff: '%s' vs '%s'%n%n", aggregateIdA, aggregateIdB);
            diff.forEach((field, change) -> System.out.printf("  %-20s  %s → %s%n", field, change.oldValue(),
                    change.newValue()));
        }
    }
}
