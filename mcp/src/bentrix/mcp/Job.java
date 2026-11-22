package bentrix.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A long operation: waitFor() answers its result once it completes (the person may act first). */
public final class Job {
    private final Client c;
    private final Object result;
    public final String handle;

    Job(Client c, String handle, Object result) {
        this.c = c;
        this.handle = handle;
        this.result = result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> status() {
        if (handle == null) {
            return Map.of("status", "completed");
        }
        return (Map<String, Object>) c.call("job_get", Map.of("job", handle));
    }

    public void cancel() {
        if (handle != null) {
            c.call("job_cancel", Map.of("job", handle));
        }
    }

    /** Poll until the job completes or fails; a null timeout waits as long as it takes. */
    public Object waitFor(Duration timeout, Duration poll) {
        if (handle == null) {
            return result;
        }
        Instant deadline = timeout == null ? null : Instant.now().plus(timeout);
        for (;;) {
            Map<String, Object> st = status();
            Object s = st.get("status");
            if ("completed".equals(s)) {
                Object r = st.get("result");
                return r != null ? r : new LinkedHashMap<String, Object>();
            }
            if ("failed".equals(s) || "cancelled".equals(s)) {
                throw new Client.JobFailedException("job " + handle + " " + s + ": " + st.getOrDefault("message", ""));
            }
            if (deadline != null && Instant.now().isAfter(deadline)) {
                throw new Client.McpException("job " + handle + " is still " + s);
            }
            try {
                Thread.sleep(poll == null ? 500 : poll.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Client.McpException("job " + handle + ": interrupted");
            }
        }
    }
}
