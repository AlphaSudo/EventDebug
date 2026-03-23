package io.eventlens.api.export;

import com.fasterxml.jackson.core.JsonGenerator;
import io.eventlens.core.EventLensConfig.ExportConfig;
import io.eventlens.core.JsonUtil;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public final class ExportService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final EventStoreReader reader;
    private final AuditLogger auditLogger;
    private final ExportConfig cfg;
    private final Path exportDir;
    private final Semaphore concurrent;
    private final ExecutorService executor;
    private final ScheduledExecutorService janitor;
    private final ConcurrentHashMap<String, ExportJob> jobs = new ConcurrentHashMap<>();

    public ExportService(EventStoreReader reader, AuditLogger auditLogger, ExportConfig cfg) {
        this.reader = reader;
        this.auditLogger = auditLogger;
        this.cfg = cfg != null ? cfg : new ExportConfig();
        this.exportDir = Path.of(this.cfg.getDirectory());
        this.concurrent = new Semaphore(Math.max(1, this.cfg.getMaxConcurrent()));
        this.executor = Executors.newFixedThreadPool(Math.max(1, this.cfg.getMaxConcurrent()));
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "eventlens-export-janitor"));

        try {
            Files.createDirectories(exportDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create export directory: " + exportDir.toAbsolutePath(), e);
        }

        this.janitor.scheduleAtFixedRate(this::cleanupExpired, 60, 60, TimeUnit.SECONDS);
    }

    public ExportJob startAggregateExport(String aggregateId, String format, int limit, Map<String, Object> auditContext) {
        int max = cfg.getMaxEventsPerExport();
        int effectiveLimit = Math.min(Math.max(1, limit), max);

        if (!concurrent.tryAcquire()) {
            throw new IllegalStateException("Too many concurrent exports. Try again later.");
        }

        String exportId = "exp-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(60, cfg.getExpireAfterSeconds()));
        String fmt = normalizeFormat(format);

        ExportJob job = new ExportJob(exportId, fmt, now, expiresAt, effectiveLimit);
        jobs.put(exportId, job);

        executor.submit(() -> runAggregateExport(job, aggregateId, effectiveLimit, auditContext));
        return job;
    }

    public ExportJob get(String exportId) {
        ExportJob job = jobs.get(exportId);
        if (job == null) return null;
        if (Instant.now().isAfter(job.expiresAt())) {
            expire(job);
            jobs.remove(exportId);
            return null;
        }
        return job;
    }

    public Path getDownloadFile(String exportId) {
        ExportJob job = get(exportId);
        if (job == null) return null;
        if (job.status() != ExportJob.Status.COMPLETED) return null;
        Path file = job.file();
        if (file == null) return null;
        if (!Files.exists(file)) return null;
        return file;
    }

    private void runAggregateExport(ExportJob job, String aggregateId, int limit, Map<String, Object> auditContext) {
        try {
            Path out = exportDir.resolve(job.exportId() + "." + job.format());
            Files.deleteIfExists(out);

            int processed = 0;
            long afterSeq = 0L;

            if ("csv".equals(job.format())) {
                try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                    w.write("sequence,event_type,timestamp,payload\n");

                    while (processed < limit) {
                        int batchSize = Math.min(1000, limit - processed);
                        List<StoredEvent> batch = reader.getEventsAfterSequence(aggregateId, afterSeq, batchSize);
                        if (batch.isEmpty()) break;

                        for (StoredEvent e : batch) {
                            w.write(e.sequenceNumber() + ",");
                            w.write(csv(e.eventType()));
                            w.write(",");
                            w.write(csv(e.timestamp().toString()));
                            w.write(",");
                            w.write(csv(e.payload()));
                            w.write("\n");
                            processed++;
                            afterSeq = e.sequenceNumber();
                        }
                        job.updateProgress(processed);
                    }
                }
            } else {
                try (var os = Files.newOutputStream(out);
                     var w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
                     JsonGenerator g = JsonUtil.mapper().createGenerator(w)) {

                    g.writeStartObject();
                    g.writeStringField("aggregateId", aggregateId);
                    g.writeStringField("exportedAt", Instant.now().toString());
                    g.writeNumberField("limit", limit);
                    g.writeFieldName("events");
                    g.writeStartArray();

                    while (processed < limit) {
                        int batchSize = Math.min(1000, limit - processed);
                        List<StoredEvent> batch = reader.getEventsAfterSequence(aggregateId, afterSeq, batchSize);
                        if (batch.isEmpty()) break;

                        for (StoredEvent e : batch) {
                            JsonUtil.mapper().writeValue(g, e);
                            processed++;
                            afterSeq = e.sequenceNumber();
                        }
                        job.updateProgress(processed);
                        g.flush();
                    }

                    g.writeEndArray();
                    g.writeNumberField("eventCount", processed);
                    g.writeEndObject();
                }
            }

            job.complete(out, processed);
            auditLogger.log(AuditEvent.builder()
                    .action(AuditEvent.ACTION_EXPORT)
                    .resourceType(AuditEvent.RT_EXPORT)
                    .resourceId(job.exportId())
                    .userId((String) auditContext.getOrDefault("userId", "anonymous"))
                    .authMethod((String) auditContext.getOrDefault("authMethod", "anonymous"))
                    .clientIp((String) auditContext.getOrDefault("clientIp", "unknown"))
                    .requestId((String) auditContext.getOrDefault("requestId", "unknown"))
                    .userAgent((String) auditContext.getOrDefault("userAgent", "unknown"))
                    .details(Map.of(
                            "aggregateId", aggregateId,
                            "format", job.format(),
                            "eventCount", processed,
                            "expiresAt", job.expiresAt().toString()
                    ))
                    .build());
        } catch (Exception e) {
            log.error("Export {} failed", job.exportId(), e);
            job.fail(e.getMessage() != null ? e.getMessage() : "Export failed");
        } finally {
            concurrent.release();
        }
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        for (var entry : jobs.entrySet()) {
            ExportJob job = entry.getValue();
            if (job == null) continue;
            if (now.isAfter(job.expiresAt())) {
                expire(job);
                jobs.remove(entry.getKey());
            }
        }
    }

    private void expire(ExportJob job) {
        try {
            Path f = job.file();
            if (f != null) Files.deleteIfExists(f);
        } catch (Exception ignored) {
        }
    }

    private static String normalizeFormat(String format) {
        if (format == null) return "json";
        String f = format.trim().toLowerCase();
        return ("csv".equals(f)) ? "csv" : "json";
    }

    private static String csv(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() {
        executor.shutdownNow();
        janitor.shutdownNow();
    }
}

