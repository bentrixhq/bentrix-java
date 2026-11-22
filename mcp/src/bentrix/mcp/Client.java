// The hand-written half of BENTRIX's Java MCP client: the connection and everything that does not change
// when a capability is added (Bentrix.java is the rest, from the catalogue). It speaks MCP revision
// 2026-07-28 over Streamable HTTP with java.net.http: every request is its own POST carrying the protocol
// version, the client's identity and its (empty) capabilities in _meta - the same wire as the Python
// client's _runtime.py and the JavaScript client's client.js, field for field. JSON is bentrix.Json, the
// engine binding's own small reader and writer (no dependency).
package bentrix.mcp;

import bentrix.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One BENTRIX endpoint. Extended by the generated Bentrix. */
public class Client implements AutoCloseable {
    public static final String PROTOCOL_VERSION = "2026-07-28";
    public static final String CLIENT_NAME = "bentrix-java-sdk";
    public static final String CLIENT_VERSION = "0.1.0";
    /**
     * <b>Busy, try again</b>: the JSON-RPC code Bentrix answers when it refused a request BEFORE doing
     * anything with it — one program's 20 calls a second spent, too many connections or streams, a task it
     * could not read in time. Nothing ran, so the same request is sent again after the wait the answer
     * names (retryAfterMs, a second when none), at most {@link #BUSY_RETRIES} times and each wait at most
     * {@link #BUSY_WAIT_MAX_MS}; then the refusal is thrown. An order is never sent twice by this.
     */
    public static final long BUSY = -40001;
    public static final int BUSY_RETRIES = 10;
    public static final long BUSY_WAIT_MAX_MS = 5000;

    /** A refusal or a failure, in BENTRIX's own words. */
    public static class McpException extends RuntimeException {
        public McpException(String message) {
            super(message);
        }
    }

    /** A JSON-RPC error: the request itself was refused. */
    public static final class RpcException extends McpException {
        public final long code;
        /** The wait a busy answer names, in milliseconds; 0 when it names none. */
        public final long retryAfterMs;

        public RpcException(long code, String message) {
            this(code, message, 0);
        }

        public RpcException(long code, String message, long retryAfterMs) {
            super("[" + code + "] " + message);
            this.code = code;
            this.retryAfterMs = retryAfterMs;
        }
    }

    /** A tool ran and answered isError: the product's own sentence, verbatim. */
    public static final class ToolException extends McpException {
        public final String tool;

        public ToolException(String tool, String message) {
            super(tool + ": " + message);
            this.tool = tool;
        }
    }

    public static final class NotRunningException extends McpException {
        public NotRunningException(String message) {
            super(message);
        }
    }

    public static final class PairingRefusedException extends McpException {
        public PairingRefusedException(String message) {
            super(message);
        }
    }

    public static final class IncompatibleCatalogueException extends McpException {
        public IncompatibleCatalogueException(String message) {
            super(message);
        }
    }

    public static final class JobFailedException extends McpException {
        public JobFailedException(String message) {
            super(message);
        }
    }

    /** A call that asks the server to wait is given that long and this much more (found 2026-11-02:
     * wait_for beyond the client's own timeout ended in the client before the server could answer). */
    public static final Duration LONG_POLL_MARGIN = Duration.ofSeconds(15);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String token;
    private final AtomicLong id = new AtomicLong();
    public final String url;
    public final String catalogueVersion;

    protected Client(String url, String token, String catalogueVersion) {
        this.url = url;
        this.token = token;
        this.catalogueVersion = catalogueVersion;
    }

    /** Leave out what is null, so the server's own default applies. */
    static Map<String, Object> plain(Map<String, ?> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (args != null) {
            args.forEach((k, v) -> {
                if (v != null) {
                    out.put(k, v);
                }
            });
        }
        return out;
    }

    /** A header value, or the standard Base64 sentinel when plain ASCII cannot carry it. */
    static String headerValue(String v) {
        boolean safe = v.equals(v.strip()) && !(v.startsWith("=?base64?") && v.endsWith("?=")) && v.chars().allMatch(ch -> ch >= 0x20 && ch <= 0x7e);
        return safe ? v : "=?base64?" + Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String scalar(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        return String.valueOf(v);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final Pattern TEMPLATE = Pattern.compile("\\{([?&]?)([^}]+)\\}");

    /** The RFC 6570 subset the catalogue uses: {name} and {?a,b}. A null optional is left out. */
    static String expandTemplate(String template, Map<String, ?> vars) {
        Map<String, Object> values = plain(vars);
        Matcher m = TEMPLATE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String op = m.group(1);
            String names = m.group(2);
            String rep;
            if (!op.isEmpty()) {
                List<String> pairs = new ArrayList<>();
                for (String n : names.split(",")) {
                    if (values.containsKey(n)) {
                        pairs.add(n + "=" + encode(scalar(values.get(n))));
                    }
                }
                rep = pairs.isEmpty() ? "" : op + String.join("&", pairs);
            } else {
                if (!values.containsKey(names)) {
                    throw new McpException("the URI template " + template + " needs " + names);
                }
                rep = encode(scalar(values.get(names)));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private Map<String, Object> rpc(String method, Map<String, Object> params, String name) {
        return rpc(method, params, name, TIMEOUT);
    }

    /** One request, sent again while Bentrix answers busy ({@link #BUSY}): it ran nothing. */
    private Map<String, Object> rpc(String method, Map<String, Object> params, String name, Duration timeout) {
        for (int attempt = 0; ; attempt++) {
            try {
                return rpcOnce(method, params, name, timeout);
            } catch (RpcException e) {
                if (e.code != BUSY || attempt >= BUSY_RETRIES) {
                    throw e;
                }
                try {
                    Thread.sleep(Math.min(e.retryAfterMs > 0 ? e.retryAfterMs : 1000, BUSY_WAIT_MAX_MS));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private Map<String, Object> rpcOnce(String method, Map<String, Object> params, String name, Duration timeout) {
        long rid = id.incrementAndGet();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION);
        meta.put("io.modelcontextprotocol/clientInfo", Map.of("name", CLIENT_NAME, "version", CLIENT_VERSION));
        meta.put("io.modelcontextprotocol/clientCapabilities", new LinkedHashMap<>());
        params.put("_meta", meta);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", rid);
        body.put("method", method);
        body.put("params", params);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .header("Mcp-Method", method)
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        if (name != null) {
            req.header("Mcp-Name", headerValue(name));
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new McpException(method + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(method + ": interrupted");
        }
        Map<String, Object> message;
        if (resp.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            message = readSse(resp.body(), rid);
        } else {
            try {
                message = obj(Json.parse(resp.body()));
            } catch (RuntimeException e) {
                throw new RpcException(resp.statusCode(), "HTTP " + resp.statusCode() + " with no JSON-RPC body");
            }
        }
        if (message.get("error") != null) {
            Map<String, Object> err = obj(message.get("error"));
            long code = err.get("code") instanceof Double d ? d.longValue() : resp.statusCode();
            long after = err.get("data") instanceof Map<?, ?> data && data.get("retryAfterMs") instanceof Number n ? n.longValue() : 0;
            throw new RpcException(code, String.valueOf(err.getOrDefault("message", "")), after);
        }
        return obj(message.get("result"));
    }

    /** The final response on a request-scoped event stream; notifications before it are ignored. */
    private static Map<String, Object> readSse(String text, long rid) {
        List<String> data = new ArrayList<>();
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        lines.add("");
        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    try {
                        Map<String, Object> msg = obj(Json.parse(String.join("\n", data)));
                        if (msg.get("id") instanceof Double d && d.longValue() == rid && (msg.containsKey("result") || msg.containsKey("error"))) {
                            return msg;
                        }
                    } catch (RuntimeException ignored) {
                        // not ours
                    }
                }
                data.clear();
            } else if (line.startsWith("data:")) {
                data.add(line.substring(5).stripLeading());
            }
        }
        throw new McpException("the stream ended without a response");
    }

    /** A tool by its wire name; null arguments are left out. */
    @SuppressWarnings("unchecked")
    public Object call(String name, Map<String, ?> args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        Map<String, Object> arguments = plain(args);
        params.put("arguments", arguments);
        Duration timeout = TIMEOUT;
        if (arguments.get("timeout_ms") instanceof Number ms) {
            Duration wait = Duration.ofMillis(ms.longValue()).plus(LONG_POLL_MARGIN);
            timeout = wait.compareTo(TIMEOUT) > 0 ? wait : TIMEOUT;
        }
        Map<String, Object> res = rpc("tools/call", params, name, timeout);
        if ("input_required".equals(res.get("resultType"))) {
            throw new McpException(name + " needs input this client cannot give; answer it in Bentrix's window");
        }
        StringBuilder text = new StringBuilder();
        if (res.get("content") instanceof List<?> parts) {
            for (Object p : parts) {
                Map<String, Object> part = obj(p);
                if ("text".equals(part.get("type")) && part.get("text") instanceof String s) {
                    text.append(s);
                }
            }
        }
        if (Boolean.TRUE.equals(res.get("isError"))) {
            throw new ToolException(name, text.length() > 0 ? text.toString() : "the tool failed");
        }
        if (res.get("structuredContent") != null) {
            return res.get("structuredContent");
        }
        try {
            return text.length() > 0 ? Json.parse(text.toString()) : new LinkedHashMap<String, Object>();
        } catch (RuntimeException e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    /** A long operation: a Job whether the server answered at once or with a handle. */
    public Job callJob(String name, Map<String, ?> args) {
        Object raw = call(name, args);
        if (raw instanceof Map<?, ?> m && m.size() == 1 && m.get("job") instanceof String handle) {
            return new Job(this, handle, null);
        }
        return new Job(this, null, raw);
    }

    private Object readUri(String uri) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uri", uri);
        Map<String, Object> res = rpc("resources/read", params, uri);
        if (!(res.get("contents") instanceof List<?> contents) || contents.isEmpty() || !(obj(contents.get(0)).get("text") instanceof String text)) {
            throw new McpException(uri + " answered no text content");
        }
        return Json.parse(text);
    }

    public Object readResource(String template, Map<String, ?> vars) {
        return readUri(expandTemplate(template, vars));
    }

    /** Every page of a cursor-paged resource, lazily: next is the URI of the following page. */
    public Iterable<Object> readPages(String template, Map<String, ?> vars) {
        String first = expandTemplate(template, vars);
        return () -> new Iterator<>() {
            private String uri = first;

            @Override
            public boolean hasNext() {
                return uri != null && !uri.isEmpty();
            }

            @Override
            public Object next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Object page = readUri(uri);
                uri = obj(page).get("next") instanceof String n ? n : null;
                return page;
            }
        };
    }

    /** Items of a stream, forever, lazily: wait_for long-polls, a page is {items, next}. */
    public Iterable<Object> watch(String template, Map<String, ?> vars, String after, int timeoutMs) {
        String uri = expandTemplate(template, vars);
        return () -> new Iterator<>() {
            private String cursor = after;
            private final List<Object> pending = new ArrayList<>();

            @Override
            public boolean hasNext() {
                while (pending.isEmpty()) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("uri", uri);
                    args.put("timeout_ms", timeoutMs <= 0 ? 30000 : timeoutMs);
                    args.put("after", cursor);
                    Map<String, Object> page = obj(call("wait_for", args));
                    if (page.get("items") instanceof List<?> items) {
                        pending.addAll(items);
                    }
                    if (page.get("next") instanceof String n) {
                        cursor = n;
                    }
                }
                return true;
            }

            @Override
            public Object next() {
                hasNext();
                return pending.remove(0);
            }
        };
    }

    private static int major(String v) {
        String[] p = v.split("\\.");
        if (p.length != 3 || !v.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IncompatibleCatalogueException(v + " is not a version major.minor.patch");
        }
        return Integer.parseInt(p[0]);
    }

    /** Refuse a server on another major line of the catalogue. */
    public String verifyCatalogue() {
        String server = String.valueOf(obj(call("agent_whoami", null)).get("catalogue_version"));
        if (major(server) != major(catalogueVersion)) {
            throw new IncompatibleCatalogueException("this SDK was generated from catalogue " + catalogueVersion + "; the server's is " + server);
        }
        return server;
    }

    @Override
    public void close() {
        http.close();
    }
}
