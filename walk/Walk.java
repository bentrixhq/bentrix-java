// The Java binding's walk on the CI tape: the engine (a second session refused, thirty hours advanced,
// a market order filled, the book read, a refusal in its words) and the Gym episode every binding runs,
// printed as JSON for sdk/python/tests/test_crosslang.py.
//
//   javac -d out sdk/java/src/bentrix/*.java sdk/java/walk/Walk.java
//   java --enable-native-access=ALL-UNNAMED -Djava.library.path=<dir> -cp out Walk <empty directory>
import bentrix.BentrixException;
import bentrix.Json;
import bentrix.Local;
import bentrix.TradingEnv;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Walk {
    private static void check(boolean held, String what) {
        if (!held) {
            throw new BentrixException("FAILED: " + what);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object at(Object v, Object... path) {
        for (Object p : path) {
            v = p instanceof Integer i ? ((List<Object>) v).get(i) : ((Map<String, Object>) v).get(p);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] argv) {
        if (argv.length != 1) {
            System.err.println("usage: Walk <empty directory>");
            System.exit(2);
        }
        try {
            System.out.println("bentrix " + Local.version());
            String[] tape = Local.makeTape(argv[0]);
            try (Local local = new Local(tape[0], tape[1])) {
                String refused = null;
                try {
                    new Local(tape[0], tape[1]).close(); // reached only if the held workspace opened twice
                } catch (BentrixException e) {
                    refused = e.getMessage();
                }
                check(refused != null, "a second session on the held workspace was not refused");
                System.out.println("a second session refused: " + refused);
                String bench = (String) at(local.call("get_state", null), "laboratories", 0, "benches", 0, "uri");
                local.arm(bench);
                long from = ((Double) at(local.call("replay_step", Map.of("bench", bench, "unit", "tick", "n", 1)), "cursor_ms")).longValue();
                long reached = local.advance(bench, from + 30L * 3_600_000);
                check(reached >= from + 30L * 3_600_000, "the workbench did not advance");
                local.call("order_market", Map.of("bench", bench, "symbol", "XAUUSD", "side", "buy", "volume", 0.01));
                local.advance(bench, reached + 60_000);
                List<Object> positions = (List<Object>) local.book(bench).get("positions");
                check(positions.size() == 1 && "buy".equals(at(positions, 0, "side")), "the order did not fill");
                String said = "";
                try {
                    local.call("no_such_tool", null);
                } catch (BentrixException e) {
                    said = e.getMessage();
                }
                check(said.contains("no_such_tool"), "a refusal did not say its tool");
                check(Local.intervalMs("H1") == 3_600_000, "H1 is not an hour");

                Map<String, Object> options = new LinkedHashMap<>();
                options.put("interval", "H1");
                options.put("window", 8);
                options.put("episode", "window");
                options.put("episode_bars", 12);
                List<Double> rewards = new ArrayList<>();
                List<Object> hashes = new ArrayList<>();
                long startMs;
                try (TradingEnv env = new TradingEnv(local, bench, options)) {
                    TradingEnv.Reset started = env.reset(5L);
                    check(started.observation().market().length == 8, "the observation is not shaped as declared");
                    startMs = ((Double) started.info().get("start_ms")).longValue();
                    hashes.add(started.info().get("ledger_hash"));
                    for (int a : new int[] {1, 1, 2, 0, 2, 2, 1, 0, 1, 2, 0, 1}) {
                        TradingEnv.Step s = env.step(a);
                        rewards.add(s.reward());
                        hashes.add(s.info().get("ledger_hash"));
                        if (s.terminated() || s.truncated()) {
                            break;
                        }
                    }
                }
                check(hashes.stream().distinct().count() > 1, "the episode traded nothing");
                Map<String, Object> episode = new LinkedHashMap<>();
                episode.put("start_ms", startMs);
                episode.put("rewards", rewards);
                episode.put("hashes", hashes);
                // Double.toString writes the shortest digits that read back as the same double.
                System.out.println(Json.write(episode));
                System.out.println("PASS");
            }
        } catch (BentrixException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
