// Model outputs of every kind and the person's meaning: every kind builds a document; a value outside its
// declared range is refused in words before anything is sent; three examples are three meanings; a put
// never sends an arrival.
//
//   javac -d /tmp/oc sdk/java/mcp/src/bentrix/mcp/Forecast.java sdk/java/mcp/src/bentrix/mcp/Output.java sdk/java/mcp/test/OutputTest.java && java -cp /tmp/oc OutputTest
//
// Mutation (2026-11-11), red: inRange not comparing - a 1.3 for a 0..1 output was sent.
import bentrix.mcp.Forecast;
import bentrix.mcp.Output;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OutputTest {
    static int failed = 0;

    static void check(boolean c, String what) {
        if (!c) {
            System.out.println("FAIL: " + what);
            failed++;
        }
    }

    static final class Rec implements Forecast.Caller {
        final List<String> names = new ArrayList<>();
        final List<Map<String, ?>> args = new ArrayList<>();

        public Object call(String name, Map<String, ?> a) {
            names.add(name);
            args.add(a);
            return Map.of("ok", true);
        }
    }

    static boolean refused(Runnable r, String needle) {
        try {
            r.run();
            return false;
        } catch (Output.Refused e) {
            return needle == null || e.getMessage().contains(needle);
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] a) {
        double[] unit = {0, 1};
        List<Output> built = List.of(
                Output.scalar("m1", "o1", "EURUSD", 1, unit, 0.7),
                Output.classes("m1", "o1", "EURUSD", 1, List.of("up", "down"), true, Map.of("up", 0.6, "down", 0.4)),
                Output.quantiles("m1", "o1", "EURUSD", 1, List.of(0.1, 0.9), Map.of("0.1", 1.0, "0.9", 1.2)),
                Output.rank("m1", "o1", "AAPL", 1, 4871, 0.62),
                Output.position("m1", "o1", "EURUSD", 1, "fraction", -0.5),
                Output.weights("m1", "o1", "EURUSD", 1, true, Map.of("EURUSD", 0.6, "GBPUSD", 0.4)),
                Output.event("m1", "o1", "EURUSD", 1, 0.8, 3),
                Output.levels("m1", "o1", "EURUSD", 1, Map.of("stop", 1.09, "target", 1.12)),
                Output.action("m1", "o1", "EURUSD", 1, "buy", 0.5),
                Output.text("m1", "o1", "EURUSD", 1, "the trend held"),
                Output.series("m1", "o1", "EURUSD", 1, List.of("a"), Map.of("a", 1.0)));
        Set<String> kinds = new HashSet<>(Set.of("path"));
        for (Output o : built) {
            o.check();
            check(o.kind.equals(o.spec().get("kind")), o.kind + " wrote another kind");
            kinds.add(o.kind);
        }
        check(Output.KINDS.size() == 12 && kinds.size() == 12, "the builders cover " + kinds.size() + " of " + Output.KINDS.size() + " kinds");

        Rec r = new Rec();
        check(refused(() -> Output.scalar("m1", "o1", "EURUSD", 1, unit, 1.3).put(r, "bench"), "0 to 1; 1.3 is outside it"), "a 1.3 for a 0..1 output was sent");
        check(r.names.isEmpty(), "something was sent");
        check(refused(() -> Output.rank("m1", "o1", "AAPL", 1, 10, 1.5).check(), null), "a rank of 1.5 was taken");

        Rec m = new Rec();
        for (Output.Meaning mm : List.of(Output.Meaning.of("buy", "sell", 0.6, 0.4), Output.Meaning.of("buy", "nothing", 0.6, null), Output.Meaning.of("sell", "nothing", 0.6, null)))
            Output.setMeaning(m, "bench", "m1", "o1", mm, unit);
        check("output_meaning_set".equals(m.names.get(2)) && "sell".equals(((Map<String, Object>) m.args.get(2).get("meaning")).get("high_means")), "the meaning was not sent as set");
        check(refused(() -> Output.setMeaning(m, "bench", "m1", "o1", Output.Meaning.of("profit", null, null, null), unit), null), "profit was taken as a meaning's word");
        check(refused(() -> Output.setMeaning(m, "bench", "m1", "o1", Output.Meaning.of("buy", null, 1.5, null), unit), null), "a threshold outside the range was taken");

        Rec p = new Rec();
        Output.scalar("m1", "dir", "EURUSD", 1, unit, 0.7).steps(4).put(p, "bench");
        Output.putOutputs(p, "bench", List.of(Output.event("m1", "e", "EURUSD", 1, 0.9, 0)), true);
        Map<String, Object> spec = (Map<String, Object>) p.args.get(0).get("output");
        check("output_put".equals(p.names.get(0)) && Map.of("steps", 4).equals(spec.get("horizon")), "output_put sent " + p.args.get(0));
        check(!spec.containsKey("put_ms"), "an arrival was sent");
        check("output_put_many".equals(p.names.get(1)) && Boolean.TRUE.equals(p.args.get(1).get("all_or_nothing")), "output_put_many sent " + p.args.get(1));

        System.out.println(failed == 0 ? "OutputTest: all passed" : "OutputTest: " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
