package com.nodeloom.sdk.batch;

import com.nodeloom.sdk.NodeLoomConfig;
import com.nodeloom.sdk.event.BatchRequest;
import com.nodeloom.sdk.event.BatchResponse;
import com.nodeloom.sdk.event.TelemetryEvent;
import com.nodeloom.sdk.queue.TelemetryQueue;
import com.nodeloom.sdk.transport.HttpTransport;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Processes telemetry events from the queue in batches and sends them
 * via {@link HttpTransport}.
 *
 * <p>Batches are flushed either when the batch reaches {@code maxBatchSize}
 * events or when the flush interval timer fires, whichever comes first.</p>
 *
 * <p>Failed requests are retried with exponential backoff up to
 * {@code maxRetries} times. Only retryable errors (429, 5xx) trigger retries;
 * client errors (4xx except 429) are not retried.</p>
 */
public final class BatchProcessor {

    private static final Logger logger = Logger.getLogger(BatchProcessor.class.getName());

    private final TelemetryQueue queue;
    private final HttpTransport transport;
    private final int maxBatchSize;
    private final int maxRetries;
    private final long baseRetryDelayMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> flushTask;

    public BatchProcessor(TelemetryQueue queue, HttpTransport transport, NodeLoomConfig config) {
        this.queue = queue;
        this.transport = transport;
        this.maxBatchSize = config.getMaxBatchSize();
        this.maxRetries = config.getMaxRetries();
        this.baseRetryDelayMs = config.getBaseRetryDelayMs();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodeloom-batch-processor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the periodic flush timer.
     *
     * @param flushIntervalMs interval between automatic flushes in milliseconds
     */
    public void start(long flushIntervalMs) {
        if (running.compareAndSet(false, true)) {
            flushTask = scheduler.scheduleAtFixedRate(
                    this::processQueue,
                    flushIntervalMs,
                    flushIntervalMs,
                    TimeUnit.MILLISECONDS
            );
            logger.fine("BatchProcessor started with flush interval " + flushIntervalMs + "ms");
        }
    }

    /**
     * Stops the flush timer and sends any remaining events.
     * Blocks until all pending events are flushed or the timeout expires.
     *
     * @param timeoutMs maximum time to wait for shutdown in milliseconds
     */
    public void shutdown(long timeoutMs) {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (flushTask != null) {
            flushTask.cancel(false);
        }

        // Flush remaining events
        processQueue();

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
                logger.warning("BatchProcessor shutdown timed out after " + timeoutMs + "ms");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Drains events from the queue and sends them in batches.
     * Called both by the periodic timer and on-demand during shutdown.
     */
    public void processQueue() {
        try {
            while (!queue.isEmpty()) {
                List<TelemetryEvent> events = queue.drain(maxBatchSize);
                if (events.isEmpty()) {
                    break;
                }
                sendWithRetry(new BatchRequest(events));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error processing telemetry queue", e);
        }
    }

    /**
     * Sends a batch request with exponential backoff retry.
     */
    void sendWithRetry(BatchRequest batch) {
        int attempt = 0;
        while (attempt <= maxRetries) {
            try {
                BatchResponse response = transport.send(batch);

                if (response.isSuccess()) {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine("Batch of " + batch.size() + " events sent successfully");
                    }
                    return;
                }

                if (!response.isRetryable()) {
                    logger.warning("Batch rejected with non-retryable status "
                            + response.getStatusCode() + ": " + response.getBody());
                    return;
                }

                logger.warning("Batch send failed with retryable status "
                        + response.getStatusCode() + " (attempt " + (attempt + 1) + "/" + (maxRetries + 1) + ")");

            } catch (Exception e) {
                logger.log(Level.WARNING, "Batch send failed with exception (attempt "
                        + (attempt + 1) + "/" + (maxRetries + 1) + ")", e);
            }

            attempt++;
            if (attempt <= maxRetries) {
                long delay = baseRetryDelayMs * (1L << (attempt - 1));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        logger.warning("Batch of " + batch.size() + " events dropped after " + (maxRetries + 1) + " attempts");
    }

    /** Returns true if the processor is currently running. */
    public boolean isRunning() {
        return running.get();
    }

    /** Returns the queue being processed. Primarily for testing. */
    TelemetryQueue getQueue() {
        return queue;
    }
}
