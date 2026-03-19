package io.eventlens.api.http;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;

/**
 * Adds request-scoped fields to MDC so logs can include correlation context.
 */
public final class RequestContextMdcFilter implements Handler {

    @Override
    public void handle(@NotNull Context ctx) {
        String requestId = ctx.attribute("requestId");
        if (requestId != null) MDC.put("requestId", requestId);

        String userId = ctx.attribute("auditUserId");
        MDC.put("userId", userId != null ? userId : "anonymous");

        String clientIp = ctx.header("X-Real-IP");
        if (clientIp == null || clientIp.isBlank()) clientIp = ctx.ip();
        MDC.put("clientIp", clientIp);

        MDC.put("method", ctx.method().name());
        MDC.put("path", ctx.path());
    }

    public static void clear() {
        MDC.clear();
    }
}

