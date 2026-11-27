// The Java Bench's walk against a running Bentrix (bentrix --serve over a practice tape): the common tasks - a
// refusal read as an answer, a step, a buy with a stop and a target, a modify, a part closed, closeAll, the account
// and the deals - through Bench.java, the layer generated from the Bench table. What it saw is printed as JSON for
// sdk/python/tests/test_bench.py, which holds it to the engine's own walk over the same tape.
//
//   javac -d out sdk/java/src/bentrix/Json.java sdk/java/src/bentrix/BentrixException.java \
//         sdk/java/mcp/src/bentrix/mcp/*.java sdk/java/mcp/bench/McpBench.java
//   LOCALAPPDATA=<the serve's> java -cp out McpBench                                  (a running Bentrix)
//   BENTRIX_PRACTICE=<a practice folder> java --enable-native-access=ALL-UNNAMED -cp out McpBench   (the engine)
import bentrix.Json;
import bentrix.mcp.Bench;
import bentrix.mcp.Bentrix;
import bentrix.mcp.Client;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpBench {
    private static void check(boolean held, String what) {
        if (!held) {
            throw new Client.McpException("FAILED: " + what);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    private static double num(Object v) {
        return ((Number) v).doubleValue();
    }

    public static void main(String[] argv) {
        // One walk, either door: the engine in this process over a practice folder, or the running Bentrix.
        String practice = System.getenv("BENTRIX_PRACTICE");
        if (practice != null && !practice.isEmpty()) {
            try (bentrix.Local local = new bentrix.Local(practice + "/ws", practice + "/store")) {
                walk(Bench.open(local::call));
            }
            return;
        }
        try (Bentrix b = Bentrix.connect("java-bench")) {
            Bench bench = Bench.open(b);
            // A workbench resource by its address: the same book as by its three parts.
            String[] p = bench.uri.substring("bentrix://".length()).split("/");
            String byParts = Json.write(b.trade.book(new Bentrix.TradeApi.BookArgs(p[0], p[1], p[3])));
            if (!Json.write(b.trade.book(new Bentrix.TradeApi.BookArgs(bench.uri))).equals(byParts)) {
                throw new IllegalStateException("FAILED: the book by its address");
            }
            walk(bench);
        }
    }

    private static void walk(Bench bench) {
        {
            List<String> markets = bench.markets();
            check(markets.equals(List.of("XAUUSD")), "the markets: " + markets);
            Bench.Answer refused = bench.buy(0.001);
            check(!refused.ok() && "volume_out_of_range".equals(refused.kind), "the refusal: " + refused.raw);
            check(refused.reason != null && refused.remedy.contains("volume_min") && refused.docs.endsWith("go/concept.volume/"), "the refusal's words: " + refused.raw);
            Map<String, Object> first = bench.step(new Bench.StepOptions().interval("H1"));
            check(first != null && num(first.get("time")) > 0 && num(first.get("close")) > 0, "the first step: " + first);
            Object group = bench.group("walk");
            check(group instanceof Number g && g.longValue() == 1, "a group's number: " + group);
            List<Object> closed = bench.bars(new Bench.BarsOptions().interval("H1").limit(5));
            check(closed.size() == 1 && num(((List<?>) closed.get(0)).get(0)) == num(first.get("time")), "the closed candles: " + closed);
            Map<String, Object> q = bench.quote();
            check(q != null && num(q.get("ask")) > num(q.get("bid")), "the quote: " + q);
            double sl = Math.rint(num(q.get("ask")) - 30.0);
            double tp = Math.rint(num(q.get("ask")) + 30.0);
            check(bench.buy(0.1, new Bench.BuyOptions().sl(sl).tp(tp)).ok(), "the buy");
            bench.step(new Bench.StepOptions().interval("H1"));
            List<Object> positions = bench.positions();
            check(positions.size() == 1, "the positions: " + positions);
            Map<String, Object> p = map(positions.get(0));
            check("buy".equals(p.get("side")) && num(p.get("volume")) == 0.1 && num(p.get("sl")) == sl && num(p.get("tp")) == tp, "the position: " + p);
            check(bench.modify(p, new Bench.ModifyOptions().sl(sl + 5.0)).ok(), "the modify");
            Map<String, Object> moved = map(bench.positions().get(0));
            check(num(moved.get("sl")) == sl + 5.0 && num(moved.get("tp")) == tp, "only what was named changes: " + moved);
            check(bench.close(moved, new Bench.CloseOptions().lots(0.04)).ok(), "the part closed");
            bench.step(new Bench.StepOptions().interval("H1"));
            Map<String, Object> rest = map(bench.positions().get(0));
            check(num(rest.get("volume")) == 0.06, "the rest: " + rest);
            check(bench.closeAll().ok(), "closeAll");
            bench.step(new Bench.StepOptions().interval("H1"));
            check(bench.positions().isEmpty(), "a position stayed");
            Bench.Answer nothing = bench.closeAll();
            check(nothing.ok() && nothing.raw.get("closed") != null && num(nothing.raw.get("closed")) == 0, "closeAll with nothing open: " + nothing.raw);
            Map<String, Object> account = map(bench.account());
            check(num(account.get("equity")) == num(account.get("balance")) && num(account.get("balance")) != 10000.0, "the account: " + account);
            List<Object> deals = bench.deals();
            int ins = 0;
            int outs = 0;
            List<Object> seenDeals = new ArrayList<>();
            for (Object d : deals) {
                Map<String, Object> m = map(d);
                if ("in".equals(m.get("entry"))) {
                    ins++;
                }
                if ("out".equals(m.get("entry"))) {
                    outs++;
                }
                seenDeals.add(List.of(m.get("entry"), m.get("side"), m.get("volume")));
            }
            check(ins == 1 && outs == 2, "the deals: " + deals);
            Bench kept = bench.keep("kept");
            check(kept.positions().isEmpty(), "the kept workbench reads");
            // Past the days' end, advance stands at their last moment, by either door.
            Long end = bench.advance(4102444800000L);
            check(end != null && end > 0 && end < 4102444800000L, "the days' end: " + end);
            Map<String, Object> seen = new LinkedHashMap<>();
            List<Object> said = new ArrayList<>();
            said.add(refused.outcome);
            said.add(refused.kind);
            said.add(refused.reason);
            seen.put("refused", said);
            seen.put("first", first);
            seen.put("group", group);
            seen.put("bars", List.of(((List<?>) closed.get(0)).get(0)));
            seen.put("deals", seenDeals);
            seen.put("balance", account.get("balance"));
            seen.put("end", end);
            System.out.println(Json.write(seen));
            System.out.println("PASS");
        }
    }
}
