package io.eventlens.api.routes;

import io.eventlens.core.exception.ConfigurationException;
import io.eventlens.core.setup.InstanceSetupService;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First-run instance setup endpoints.
 */
public final class SetupRoutes {

    private final InstanceSetupService setupService;

    public SetupRoutes(InstanceSetupService setupService) {
        this.setupService = setupService;
    }

    public void status(Context ctx) {
        var status = setupService.status();
        ctx.json(Map.of(
                "setupRequired", status.setupRequired(),
                "restartRequired", status.restartRequired(),
                "configPath", status.configPath()
        ));
    }

    public void apply(Context ctx) {
        try {
            var request = ctx.bodyAsClass(InstanceSetupService.SetupRequest.class);
            var result = setupService.apply(request);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("saved", result.saved());
            payload.put("restartRequired", result.restartRequired());
            payload.put("mode", result.mode());
            payload.put("configPath", result.configPath());
            ctx.json(payload);
        } catch (IllegalArgumentException | IllegalStateException | ConfigurationException e) {
            ctx.status(400).json(Map.of(
                    "error", "setup_invalid",
                    "message", e.getMessage()
            ));
        }
    }
}
