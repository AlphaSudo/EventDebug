package io.eventlens.api.http;

import io.eventlens.core.JsonUtil;
import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ConditionalGet {

    private ConditionalGet() {
    }

    public static void json(Context ctx, Object bodyObj) {
        byte[] body = JsonUtil.toJson(bodyObj).getBytes(StandardCharsets.UTF_8);
        respond(ctx, "application/json", body);
    }

    public static void respond(Context ctx, String contentType, byte[] body) {
        if (!"GET".equalsIgnoreCase(ctx.method().name())) {
            ctx.contentType(contentType).result(body);
            return;
        }

        String etag = "\"" + sha256Hex(body) + "\"";
        ctx.header("ETag", etag);
        ctx.header("Cache-Control", "no-cache");

        String ifNoneMatch = ctx.header("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            ctx.status(304);
            ctx.contentType(contentType);
            ctx.result("");
            return;
        }

        ctx.contentType(contentType).result(body);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fallback: should never happen on a standard JVM.
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }
}

