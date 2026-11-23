package io.github.mbanucu.a11yprobe;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The answers of asynchronous waits, keyed by the caller's request id.
 *
 * <p>A null value means the wait was reserved and has not finished: a caller
 * has to be able to tell "still running" from "no such id". The map is bounded,
 * because a harness that never reads a result must not grow the probe's memory
 * for the life of the process; the oldest id is forgotten first.
 *
 * <p>Pure Java, so the reserve/complete behaviour is tested by
 * {@code a11y-probe/test.sh}.
 */
public final class ProbeResults {

    private final int capacity;
    private final LinkedHashMap<String, String> answers = new LinkedHashMap<>();

    public ProbeResults(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /** Claims an id; false when it is already in use. */
    public synchronized boolean reserve(String id) {
        if (answers.containsKey(id)) {
            return false;
        }
        while (answers.size() >= capacity) {
            String oldest = answers.keySet().iterator().next();
            answers.remove(oldest);
        }
        answers.put(id, null);
        return true;
    }

    /** Stores the answer for a reserved id, or for one whose reservation was evicted. */
    public synchronized void complete(String id, String answer) {
        if (!answers.containsKey(id)) {
            while (answers.size() >= capacity) {
                String oldest = answers.keySet().iterator().next();
                answers.remove(oldest);
            }
        }
        answers.put(id, answer);
    }

    /** The answer, null while it is still running; check {@link #has} first. */
    public synchronized String get(String id) {
        return answers.get(id);
    }

    public synchronized boolean has(String id) {
        return answers.containsKey(id);
    }

    public synchronized int size() {
        return answers.size();
    }

    /** Every id and its answer, oldest first; for a test, not for a command. */
    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(answers);
    }
}
