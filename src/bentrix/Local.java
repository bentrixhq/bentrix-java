// **BENTRIX for Java**: the engine in your own process through its C ABI. A Local answers the agents'
// tools by name - read, laboratory and paper, never a real account - with every answer JSON as the
// catalogue describes it (Map, List, Double, String, Boolean).
package bentrix;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Local implements AutoCloseable {
    private MemorySegment session;

    /** Open {@code workspace} (yours when null) and its recordings (yours when null); a window or a
     * --serve already on the workspace refuses it by name. */
    public Local(String workspace, String store) {
        try (Arena a = Arena.ofConfined()) {
            session = (MemorySegment) Native.OPEN.invoke(Native.cstr(a, workspace), Native.cstr(a, store));
        } catch (Throwable t) {
            throw t instanceof BentrixException b ? b : new BentrixException(t.toString());
        }
        if (session.equals(MemorySegment.NULL)) {
            throw Native.failure();
        }
    }

    private MemorySegment open() {
        if (session == null) {
            throw new BentrixException("this session is closed");
        }
        return session;
    }

    /** One tool by name, its arguments a map (null: none); its answer. */
    public Object call(String tool, Map<String, ?> args) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment got = (MemorySegment) Native.CALL.invoke(open(), a.allocateFrom(tool), args == null ? MemorySegment.NULL : a.allocateFrom(Json.write(args)));
            return Json.parse(Native.take(got));
        } catch (BentrixException e) {
            throw e;
        } catch (Throwable t) {
            throw new BentrixException(t.toString());
        }
    }

    /** One resource by its catalogue name, its template's variables. */
    public Object read(String resource, Map<String, String> vars) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment got = (MemorySegment) Native.READ.invoke(open(), a.allocateFrom(resource), vars == null ? MemorySegment.NULL : a.allocateFrom(Json.write(vars)));
            return Json.parse(Native.take(got));
        } catch (BentrixException e) {
            throw e;
        } catch (Throwable t) {
            throw new BentrixException(t.toString());
        }
    }

    /** Arm a workbench's engine (its venue over its recording). */
    public void arm(String bench) {
        try (Arena a = Arena.ofConfined()) {
            if ((int) Native.ARM.invoke(open(), a.allocateFrom(bench), 20_000L) != 0) {
                throw Native.failure();
            }
        } catch (BentrixException e) {
            throw e;
        } catch (Throwable t) {
            throw new BentrixException(t.toString());
        }
    }

    /** Advance a workbench to {@code toMs} on its clock, as Play would; where it stands. */
    public long advance(String bench, long toMs) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment reached = a.allocate(Long.BYTES);
            if ((int) Native.ADVANCE.invoke(open(), a.allocateFrom(bench), toMs, reached) != 0) {
                throw Native.failure();
            }
            return reached.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0);
        } catch (BentrixException e) {
            throw e;
        } catch (Throwable t) {
            throw new BentrixException(t.toString());
        }
    }

    /** Frames until nothing is waiting. */
    public void settle() {
        try {
            if ((int) Native.SETTLE.invoke(open(), 1000L) != 0) {
                throw Native.failure();
            }
        } catch (BentrixException e) {
            throw e;
        } catch (Throwable t) {
            throw new BentrixException(t.toString());
        }
    }

    /** A workbench's open positions and pending orders. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> book(String bench) {
        String[] p = bench.replaceFirst("^bentrix://", "").split("/");
        if (p.length < 4) {
            throw new BentrixException("not a workbench: " + bench);
        }
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("home", p[0]);
        vars.put("owner", p[1]);
        vars.put("bench", p[3]);
        return (Map<String, Object>) read("trade_book", vars);
    }

    /** The workspace persisted and let go. */
    @Override
    public void close() {
        if (session != null) {
            try {
                Native.CLOSE.invoke(session);
            } catch (Throwable ignored) {
                // closing is best effort; the process lets the workspace go when it ends
            }
            session = null;
        }
    }

    /** The build this library was made from. */
    public static String version() {
        try {
            return Native.string((MemorySegment) Native.VERSION.invoke());
        } catch (Throwable t) {
            throw new BentrixException(t.toString());
        }
    }

    /** An interval's length in ms (M1, H4, D1); 0 for one with no fixed length. */
    public static long intervalMs(String label) {
        try (Arena a = Arena.ofConfined()) {
            return (long) Native.INTERVAL_MS.invoke(a.allocateFrom(label));
        } catch (Throwable t) {
            throw new BentrixException(t.toString());
        }
    }

    /** The CI tape in {@code directory}: {workspace, store}. */
    public static String[] makeTape(String directory) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ws = a.allocate(java.lang.foreign.ValueLayout.ADDRESS);
            MemorySegment store = a.allocate(java.lang.foreign.ValueLayout.ADDRESS);
            if ((int) Native.MAKE_TAPE.invoke(a.allocateFrom(directory), ws, store) != 0) {
                throw Native.failure();
            }
            return new String[] {
                Native.take(ws.get(java.lang.foreign.ValueLayout.ADDRESS, 0)),
                Native.take(store.get(java.lang.foreign.ValueLayout.ADDRESS, 0)),
            };
        } catch (BentrixException e) {
            throw e;
        } catch (Throwable t) {
            throw new BentrixException(t.toString());
        }
    }
}
