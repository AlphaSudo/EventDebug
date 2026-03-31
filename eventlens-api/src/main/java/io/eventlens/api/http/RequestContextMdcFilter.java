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

        MDC.put("userId", SecurityContext.principal(ctx).userId());

        MDC.put("clientIp", SecurityContext.clientIp(ctx));

        MDC.put("method", ctx.method().name());
        MDC.put("path", ctx.path());
    }

    public static void clear() {
        MDC.clear();
    }
}
