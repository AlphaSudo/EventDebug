package io.eventlens.cli;

import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.engine.*;
import io.eventlens.pg.PgEventStoreReader;
import picocli.CommandLine.*;

import java.io.*;
import java.nio.file.*;

@Command(name = "export", description = "Export aggregate event history to a file or stdout")
public class ExportCommand implements Runnable {

    @Parameters(index = "0", description = "Aggregate ID")
    String aggregateId;

    @Option(names = "--format", defaultValue = "json", description = "Export format: json | markdown | csv | junit")
    String format;

    @Option(names = "--output", description = "Output file path (stdout if omitted)")
    String outputPath;

    @Mixin
    DatabaseOptions db;

    @Override
    public void run() {
        var reader = new PgEventStoreReader(db.toPgConfig());
        var replayEngine = new ReplayEngine(reader, new ReducerRegistry());
        var engine = new ExportEngine(reader, replayEngine);

        ExportEngine.Format exportFormat = switch (format.toLowerCase()) {
            case "markdown" -> ExportEngine.Format.MARKDOWN;
            case "csv" -> ExportEngine.Format.CSV;
            case "junit" -> ExportEngine.Format.JUNIT_FIXTURE;
            default -> ExportEngine.Format.JSON;
        };

        String content = engine.export(aggregateId, exportFormat);

        if (outputPath != null) {
            try {
                Files.writeString(Path.of(outputPath), content);
                System.out.println("Exported to: " + outputPath);
            } catch (IOException e) {
                System.err.println("Failed to write: " + e.getMessage());
            }
        } else {
            System.out.println(content);
        }
    }
}
