package io.nodeloom.sdk.control;

import io.nodeloom.sdk.api.ApiClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background poller that refreshes {@link ControlRegistry} on a fixed
 * interval. Telemetry batch responses already piggy-back the control
 * payload, so this is mainly useful for sparse-traffic agents.
 */
public final class ControlPoller {

    private static final Logger logger = Logger.getLogger(ControlPoller.class.getName());

    private final ControlRegistry registry;
    private final Supplier<ApiClient> apiSupplier;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> handle;

    public ControlPoller(ControlRegistry registry, Supplier<ApiClient> apiSupplier, long intervalMs) {
        this.registry = registry;
        this.apiSupplier = apiSupplier;
        this.intervalMs = Math.max(1_000L, intervalMs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodeloom-control-poller");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (handle != null) return;
        handle = scheduler.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
        }
        scheduler.shutdown();
    }

    private void tick() {
        ApiClient api;
        try {
            api = apiSupplier.get();
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Control poller could not obtain ApiClient", e);
            return;
        }
        for (String name : registry.knownAgents()) {
            try {
                api.getAgentControl(name);
            } catch (Exception e) {
                logger.log(Level.FINE, "Control poll failed for " + name, e);
            }
        }
    }
}
