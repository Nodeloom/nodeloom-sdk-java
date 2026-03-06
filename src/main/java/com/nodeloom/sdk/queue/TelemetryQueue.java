package com.nodeloom.sdk.queue;

import com.nodeloom.sdk.event.TelemetryEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * A bounded, thread-safe queue for telemetry events.
 *
 * <p>Uses a {@link LinkedBlockingQueue} internally. When the queue is full,
 * new events are silently dropped and counted. This ensures that telemetry
 * collection never blocks or crashes the host application.</p>
 */
public final class TelemetryQueue {

    private static final Logger logger = Logger.getLogger(TelemetryQueue.class.getName());

    private final LinkedBlockingQueue<TelemetryEvent> queue;
    private final AtomicLong droppedCount = new AtomicLong(0);

    /**
     * Creates a new queue with the specified capacity.
     *
     * @param maxSize maximum number of events the queue will hold
     */
    public TelemetryQueue(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive, got: " + maxSize);
        }
        this.queue = new LinkedBlockingQueue<>(maxSize);
    }

    /**
     * Offers an event to the queue. If the queue is full, the event is
     * silently dropped and the drop counter is incremented.
     *
     * @param event the event to enqueue
     * @return true if the event was accepted, false if it was dropped
     */
    public boolean offer(TelemetryEvent event) {
        if (event == null) {
            return false;
        }
        boolean accepted = queue.offer(event);
        if (!accepted) {
            long dropped = droppedCount.incrementAndGet();
            if (dropped == 1 || dropped % 1000 == 0) {
                logger.warning("NodeLoom telemetry queue is full. Total events dropped: " + dropped);
            }
        }
        return accepted;
    }

    /**
     * Drains up to {@code maxElements} events from the queue into a new list.
     *
     * @param maxElements maximum number of events to drain
     * @return a list of drained events (may be empty, never null)
     */
    public List<TelemetryEvent> drain(int maxElements) {
        List<TelemetryEvent> batch = new ArrayList<>(Math.min(maxElements, queue.size()));
        queue.drainTo(batch, maxElements);
        return batch;
    }

    /** Returns the current number of events in the queue. */
    public int size() {
        return queue.size();
    }

    /** Returns true if the queue has no events. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Returns the total number of events dropped due to the queue being full. */
    public long getDroppedCount() {
        return droppedCount.get();
    }
}
