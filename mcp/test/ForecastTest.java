// A forecast's builder and an evaluation's sheet.
//
//   javac -d /tmp/fc sdk/java/mcp/src/bentrix/mcp/Forecast.java sdk/java/mcp/test/ForecastTest.java && java -cp /tmp/fc ForecastTest
//
// Mutations (2026-11-08), each red: spec writing the timeframe whatever it is (the element form named an interval);
// declined read as a failure (a decline was EvalFailed).
import bentrix.mcp.Forecast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ForecastTest {
    static int failed = 0;

    static void check(boolean c, String what) {
        if (!c) {
            System.out.println("FAIL: " + what);
            failed++;
        }
    }

    static Map<String, Object> moved(String ask, String state, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ask", ask);
        m.put("state", state);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static final class Sheet implements Forecast.Caller {
        final Deque<List<Object>> pages = new ArrayDeque<>();
        final List<String> asked = new ArrayList<>();

        @SafeVarargs
        Sheet(List<Object>... ps) {
            for (List<Object> p : ps) pages.add(p);
        }

        public Object call(String name, Map<String, ?> args) {
            if (!name.equals("wait_for") || !Forecast.EVAL_EVENT.equals(args.get("uri"))) throw new IllegalStateException("not the eval event");
            String after = (String) args.get("after");
            asked.add(after);
            List<Object> items = pages.isEmpty() ? List.of() : pages.poll();
            return Map.of("items", items, "next", String.valueOf(Integer.parseInt(after) + items.size()));
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] a) {
        Forecast f = Forecast.ofElements("bricks", "EURUSD", "renko", 1700000000000L, Map.of("renko_box", 0.0005));
        f.step(new Forecast.Step(1.1005).expected(1700000600000L));
        Map<String, Object> form = (Map<String, Object>) f.spec().get("form");
        check(!form.containsKey("timeframe") && "renko".equals(form.get("chart_type")) && form.get("elements") != null, "the element form named an interval");
        Map<String, Object> step = (Map<String, Object>) ((List<Object>) f.spec().get("steps")).get(0);
        check(Long.valueOf(1700000600000L).equals(step.get("expected_ms")), "a step lost its expected_ms");
        Map<String, Object> tf = (Map<String, Object>) Forecast.ofTime("m", "EURUSD", "H1", 0).step(new Forecast.Step(1)).spec().get("form");
        check("H1".equals(tf.get("timeframe")), "the time form named no interval");

        check(Forecast.evalStarted(new Sheet(), Map.of("eval", 7.0), 0, 1) == 7, "a run at once");
        Map<String, Object> ask = Map.of("ask", "bentrix://ask/2", "watch", "bentrix://forecasts/eval?after=0");
        Sheet s = new Sheet(List.of(moved("bentrix://ask/1", "declined"), moved("bentrix://ask/2", "asked")), List.of(moved("bentrix://ask/2", "made_laboratory")),
            List.of(moved("bentrix://ask/2", "started", "eval", 11.0)));
        check(Forecast.evalStarted(s, ask, 0, 3) == 11, "the run the person started");
        check(s.asked.equals(List.of("0", "2", "3")), "the cursor " + s.asked);
        try {
            Forecast.evalStarted(new Sheet(List.of(moved("bentrix://ask/2", "declined"))), ask, 0, 3);
            check(false, "a decline answered a run");
        } catch (Forecast.DeclinedByThePerson e) {
        } catch (RuntimeException e) {
            check(false, "a decline was EvalFailed");
        }
        try {
            Forecast.evalStarted(new Sheet(List.of(moved("bentrix://ask/2", "failed", "why", "the laboratory was not made"))), ask, 0, 3);
            check(false, "a failure answered a run");
        } catch (Forecast.EvalFailed e) {
            check(e.why.equals("the laboratory was not made"), "a failure lost its why");
        }
        try {
            Forecast.evalStarted(new Sheet(), ask, 0, 2);
            check(false, "a watch with no answer did not end");
        } catch (IllegalStateException e) {
        }
        System.out.println(failed == 0 ? "PASS" : "FAILED " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
