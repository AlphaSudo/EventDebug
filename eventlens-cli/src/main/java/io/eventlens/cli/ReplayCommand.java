package io.eventlens.cli;

import io.eventlens.core.JsonUtil;
import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.engine.ReplayEngine;
import io.eventlens.pg.PgEventStoreReader;
import picocli.CommandLine.*;

@Command(name = "replay", description = "Replay events for an aggregate to a specific point")
public class ReplayCommand implements Runnable {

    @Parameters(index = "0", description = "Aggregate ID")
    String aggregateId;

    @Option(names = "--to-event", description = "Replay up to this sequence number")
    Long toEvent;

    @Mixin
    DatabaseOptions db;

    @Override
    public void run() {
        var reader = new PgEventStoreReader(db.toPgConfig());
        var engine = new ReplayEngine(reader, new ReducerRegistry());

        if (toEvent != null) {
            var result = engine.replayTo(aggregateId, toEvent);
            System.out.println("State of '" + aggregateId + "' at event #" + toEvent + ":");
            System.out.println(JsonUtil.prettyPrint(result.state()));
        } else {
            var transitions = engine.replayFull(aggregateId);
            System.out.println("Full replay for '" + aggregateId + "' (" + transitions.size() + " events):");
            for (var t : transitions) {
                System.out.printf("#%d  %-30s  changes: %s%n",
                        t.event().sequenceNumber(),
                        t.event().eventType(),
                        t.diff().isEmpty() ? "—" : t.diff());
            }
        }
    }
}
