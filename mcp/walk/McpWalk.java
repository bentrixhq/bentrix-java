// The Java MCP client's walk against a running Bentrix (bentrix --serve over the CI tape): it finds the
// application and pairs with nothing typed, reads its state through the generated surface, reads a
// resource by its template, and runs the same Gym episode every binding runs - through the agents'
// tools, over the wire - printed as JSON for sdk/python/tests/test_crosslang.py.
//
//   javac -d out sdk/java/src/bentrix/Json.java sdk/java/src/bentrix/BentrixException.java \
//         sdk/java/mcp/src/bentrix/mcp/*.java sdk/java/mcp/walk/McpWalk.java
//   LOCALAPPDATA=<the serve's> java -cp out McpWalk
import bentrix.Json;
import bentrix.mcp.Bentrix;
import bentrix.mcp.Client;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpWalk {
    private static void check(boolean held, String what) {
        if (!held) {
            throw new Client.McpException("FAILED: " + what);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object at(Object v, Object... path) {
        for (Object p : path) {
            v = p instanceof Integer i ? ((List<Object>) v).get(i) : ((Map<String, Object>) v).get(p);
        }
        return v;
    }

    public static void main(String[] argv) {
        try (Bentrix b = Bentrix.connect("java-walk")) {
            String bench = (String) at(b.workspace.getState(new Bentrix.WorkspaceApi.GetStateArgs()), "laboratories", 0, "benches", 0, "uri");
            String[] p = bench.replaceFirst("^bentrix://", "").split("/");
            Object book = b.trade.book(new Bentrix.TradeApi.BookArgs(p[0], p[1], p[3]));
            check(at(book, "positions") instanceof List<?>, "the book did not read: " + book);
            String said = "";
            try {
                b.call("no_such_tool", null);
            } catch (Client.McpException e) {
                said = e.getMessage();
            }
            check(said.contains("no_such_tool"), "a refusal did not say its tool: " + said);

            Object made = b.strategy.envMake(new Bentrix.StrategyApi.EnvMakeArgs(bench).interval("H1").window(8).episode("window").episodeBars(12));
            String env = (String) at(made, "env");
            Object start = b.strategy.envReset(new Bentrix.StrategyApi.EnvResetArgs(env).seed(5));
            check(((List<?>) at(start, "obs", "market")).size() == 8, "the observation is not shaped as declared");
            List<Double> rewards = new ArrayList<>();
            List<Object> hashes = new ArrayList<>();
            hashes.add(at(start, "info", "ledger_hash"));
            for (int a : new int[] {1, 1, 2, 0, 2, 2, 1, 0, 1, 2, 0, 1}) {
                Object s = b.strategy.envStep(new Bentrix.StrategyApi.EnvStepArgs(env, a));
                rewards.add((Double) at(s, "reward"));
                hashes.add(at(s, "info", "ledger_hash"));
                if (Boolean.TRUE.equals(at(s, "terminated")) || Boolean.TRUE.equals(at(s, "truncated"))) {
                    break;
                }
            }
            b.strategy.envClose(new Bentrix.StrategyApi.EnvCloseArgs(env));
            // A tool's own refusal (isError), not a request the wire refused.
            RuntimeException refusal = null;
            try {
                b.strategy.envStep(new Bentrix.StrategyApi.EnvStepArgs(env, 0));
            } catch (RuntimeException e) {
                refusal = e;
            }
            check(refusal instanceof Client.ToolException && refusal.getMessage().contains("No environment"), "a closed environment's step was not refused in its words: " + refusal);
            check(hashes.stream().distinct().count() > 1, "the episode traded nothing");
            Map<String, Object> episode = new LinkedHashMap<>();
            episode.put("start_ms", ((Double) at(start, "info", "start_ms")).longValue());
            episode.put("rewards", rewards);
            episode.put("hashes", hashes);
            // Double.toString writes the shortest digits that read back as the same double.
            System.out.println(Json.write(episode));
            System.out.println("PASS");
        } catch (Client.McpException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
