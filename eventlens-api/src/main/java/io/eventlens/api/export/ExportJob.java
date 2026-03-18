package io.eventlens.api.export;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ExportJob {

    public enum Status { RUNNING, COMPLETED, FAILED }

    private final String exportId;
    private final String format;
    private final Instant startedAt;
    private final Instant expiresAt;

    private final AtomicReference<Status> status = new AtomicReference<>(Status.RUNNING);
    private final AtomicInteger processedEvents = new AtomicInteger(0);
    private final AtomicInteger totalEstimate = new AtomicInteger(0);
    private final AtomicReference<Path> file = new AtomicReference<>(null);
    private final AtomicReference<String> error = new AtomicReference<>(null);

    public ExportJob(String exportId, String format, Instant startedAt, Instant expiresAt, int totalEstimate) {
        this.exportId = exportId;
        this.format = format;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
        this.totalEstimate.set(totalEstimate);
    }

    public String exportId() { return exportId; }
    public String format() { return format; }
    public Instant startedAt() { return startedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Status status() { return status.get(); }
    public int processedEvents() { return processedEvents.get(); }
    public int totalEstimate() { return totalEstimate.get(); }
    public Path file() { return file.get(); }
    public String error() { return error.get(); }

    public double progress() {
        int total = totalEstimate.get();
        if (total <= 0) return 0.0;
        return Math.min(1.0, processedEvents.get() / (double) total);
    }

    void updateProgress(int processed) {
        processedEvents.set(processed);
    }

    void complete(Path filePath, int processed) {
        file.set(filePath);
        processedEvents.set(processed);
        status.set(Status.COMPLETED);
    }

    void fail(String message) {
        error.set(message);
        status.set(Status.FAILED);
    }
}

