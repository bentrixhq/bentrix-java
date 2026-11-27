// **The Gym, for Java**: a client of Bentrix's own environment (env_make, env_reset, env_step; the
// environment is Rust's, crates/bentrix-shell/src/agenttools/env.rs), so its observations, actions,
// rewards and episodes are the ones every language reaches. The contract is Gymnasium's: reset(seed)
// -> (observation, info); step(action) -> (observation, reward, terminated, truncated, info).
package bentrix;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TradingEnv implements AutoCloseable {
    /** The last closed candles as log ratios to the last close, [window][4] (open, high, low, close),
     * and the account: side, log equity ratio to the episode's start, open profit over it. */
    public record Observation(float[][] market, float[] account) {}

    public record Reset(Observation observation, Map<String, Object> info) {}

    public record Step(Observation observation, double reward, boolean terminated, boolean truncated, Map<String, Object> info) {}

    private final Local local;
    public final String id;
    public final String symbol;

    /** An environment over {@code bench}; {@code options} env_make's other arguments
     * ("window" -> 8, "episode_bars" -> 12), its defaults where absent. */
    @SuppressWarnings("unchecked")
    public TradingEnv(Local local, String bench, Map<String, ?> options) {
        this.local = local;
        Map<String, Object> args = new LinkedHashMap<>();
        if (options != null) {
            args.putAll(options);
        }
        args.put("bench", bench);
        Map<String, Object> made = (Map<String, Object>) local.call("env_make", args);
        id = (String) made.get("env");
        symbol = (String) made.get("symbol");
    }

    @SuppressWarnings("unchecked")
    private static Observation observation(Object o) {
        Map<String, Object> obs = (Map<String, Object>) o;
        List<Object> rows = (List<Object>) obs.get("market");
        float[][] market = new float[rows.size()][4];
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = (List<Object>) rows.get(i);
            for (int k = 0; k < 4; k++) {
                market[i][k] = ((Double) r.get(k)).floatValue();
            }
        }
        List<Object> acc = (List<Object>) obs.get("account");
        float[] account = new float[acc.size()];
        for (int k = 0; k < account.length; k++) {
            account[k] = ((Double) acc.get(k)).floatValue();
        }
        return new Observation(market, account);
    }

    /** A new episode; a seed given starts the draws over, null takes the next episode. */
    @SuppressWarnings("unchecked")
    public Reset reset(Long seed) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("env", id);
        args.put("seed", seed);
        Map<String, Object> got = (Map<String, Object>) local.call("env_reset", args);
        return new Reset(observation(got.get("obs")), (Map<String, Object>) got.get("info"));
    }

    /** One action: 0, 1 or 2 (discrete), a number in [-1, 1] (continuous), or a map
     * {side, size, stop, target} (order). */
    @SuppressWarnings("unchecked")
    public Step step(Object action) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("env", id);
        args.put("action", action);
        Map<String, Object> got = (Map<String, Object>) local.call("env_step", args);
        return new Step(observation(got.get("obs")), (Double) got.get("reward"), (Boolean) got.get("terminated"), (Boolean) got.get("truncated"), (Map<String, Object>) got.get("info"));
    }

    @Override
    public void close() {
        try {
            local.call("env_close", Map.of("env", id));
        } catch (BentrixException ignored) {
            // an environment already gone is let go
        }
    }
}
