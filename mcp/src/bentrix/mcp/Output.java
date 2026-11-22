// A model's output of any kind on the chart: the output_spec document built by hand for the eleven kinds beside
// a path (a path is Forecast's) - scalar, classes, quantiles, rank, position, weights, event, levels, action, text,
// series - checked as Bentrix checks it, a value outside its declared range refused in words and never clamped, and
// put through output_put; and the person's meaning (output_meaning) set through output_meaning_set, never in a model's
// file. An output never names its arrival: Bentrix stamps put_ms.
package bentrix.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One output's document: its kind, its values as the document writes them, and the kind's own fields. */
public final class Output {
    /** The twelve kinds, as the document writes them. */
    public static final List<String> KINDS = List.of("path", "scalar", "classes", "quantiles", "rank", "position", "weights", "event", "levels", "action", "text", "series");
    /** The meaning's closed words. */
    public static final List<String> MEANING_WORDS = List.of("buy", "sell", "nothing", "up", "down", "flat", "in", "out", "close");
    /** An action's intents. */
    public static final List<String> INTENTS = List.of("buy", "sell", "close_buys", "close_sells", "nothing");

    /** A refusal in Bentrix's words, raised before anything is sent. */
    public static final class Refused extends IllegalArgumentException {
        public Refused(String m) {
            super(m);
        }
    }

    public final String kind, model, name, symbol;
    public final long anchorMs;
    public final List<Map<String, Object>> values = new ArrayList<>();
    /** range, units, horizon, names, levels, universe, markets, long_only, exclusive, softmax, persistent, gross, note. */
    public final Map<String, Object> fields = new LinkedHashMap<>();
    public int epoch = 0;
    public Meaning suggestedMeaning;

    private Output(String kind, String model, String name, String symbol, long anchorMs) {
        this.kind = kind;
        this.model = model;
        this.name = name;
        this.symbol = symbol;
        this.anchorMs = anchorMs;
    }

    private Output value(Object... kv) {
        Map<String, Object> v = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) v.put((String) kv[i], kv[i + 1]);
        values.add(v);
        return this;
    }

    private Output with(String k, Object v) {
        fields.put(k, v);
        return this;
    }

    /** A number per step; range its declared bounds, or null. */
    public static Output scalar(String model, String name, String symbol, long anchorMs, double[] range, double... vs) {
        Output o = new Output("scalar", model, name, symbol, anchorMs);
        for (double v : vs) o.value("value", v);
        if (range != null) o.with("range", List.of(range[0], range[1]));
        return o;
    }

    /** Probabilities over named classes, one map per step. */
    @SafeVarargs
    public static Output classes(String model, String name, String symbol, long anchorMs, List<String> names, boolean exclusive, Map<String, Double>... steps) {
        Output o = new Output("classes", model, name, symbol, anchorMs).with("names", names).with("exclusive", exclusive);
        for (Map<String, Double> p : steps) o.value("p", new LinkedHashMap<>(p));
        return o;
    }

    /** Quantiles at levels, one map per step keyed as the document writes a level ("0.1"). */
    @SafeVarargs
    public static Output quantiles(String model, String name, String symbol, long anchorMs, List<Double> levels, Map<String, Double>... steps) {
        Output o = new Output("quantiles", model, name, symbol, anchorMs).with("levels", levels);
        for (Map<String, Double> q : steps) o.value("q", new LinkedHashMap<>(q));
        return o;
    }

    /** A rank in 0..1 among universe markets. */
    public static Output rank(String model, String name, String symbol, long anchorMs, int universe, double value) {
        return new Output("rank", model, name, symbol, anchorMs).with("universe", universe).value("value", value);
    }

    /** A target position in units (fraction, lots, ...). */
    public static Output position(String model, String name, String symbol, long anchorMs, String units, double target) {
        return new Output("position", model, name, symbol, anchorMs).with("units", units).value("target", target);
    }

    /** Weights across markets. */
    public static Output weights(String model, String name, String symbol, long anchorMs, boolean longOnly, Map<String, Double> w) {
        return new Output("weights", model, name, symbol, anchorMs).with("long_only", longOnly).with("markets", new ArrayList<>(w.keySet())).value("w", new LinkedHashMap<>(w));
    }

    /** An event's score in 0..1, expected within withinSteps (0 for none). */
    public static Output event(String model, String name, String symbol, long anchorMs, double score, int withinSteps) {
        Output o = new Output("event", model, name, symbol, anchorMs);
        return withinSteps > 0 ? o.value("score", score, "within_steps", withinSteps) : o.value("score", score);
    }

    /** Named price levels (stop, target, ...). */
    public static Output levels(String model, String name, String symbol, long anchorMs, Map<String, Double> levels) {
        return new Output("levels", model, name, symbol, anchorMs).value("levels", new LinkedHashMap<>(levels));
    }

    /** An intent, with a size where it has one (null for none). */
    public static Output action(String model, String name, String symbol, long anchorMs, String intent, Double size) {
        Output o = new Output("action", model, name, symbol, anchorMs);
        return size != null ? o.value("intent", intent, "size", size) : o.value("intent", intent);
    }

    /** A sentence. */
    public static Output text(String model, String name, String symbol, long anchorMs, String text) {
        return new Output("text", model, name, symbol, anchorMs).value("text", text);
    }

    /** Named lines, one map per step. */
    @SafeVarargs
    public static Output series(String model, String name, String symbol, long anchorMs, List<String> names, Map<String, Double>... steps) {
        Output o = new Output("series", model, name, symbol, anchorMs).with("names", names);
        for (Map<String, Double> l : steps) o.value("lines", new LinkedHashMap<>(l));
        return o;
    }

    /** The horizon: bars of the anchor's interval. */
    public Output steps(int n) {
        return with("horizon", Map.of("steps", n));
    }

    /** The horizon in business days. */
    public Output businessDays(int n) {
        return with("horizon", Map.of("business_days", n));
    }

    /** Held until "levels" or "next_output". */
    public Output until(String what) {
        return with("horizon", Map.of("until", what));
    }

    static void inRange(String field, Object o, double[] range) {
        if (!(o instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new Refused(field + " " + o + " is not a number");
        double v = n.doubleValue();
        if (range != null && (v < range[0] || v > range[1])) throw new Refused(field + " the output answers " + fmt(range[0]) + " to " + fmt(range[1]) + "; " + fmt(v) + " is outside it");
    }

    static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
    }

    private double[] range() {
        if (fields.get("range") instanceof List<?> r && r.size() == 2) return new double[] {((Number) r.get(0)).doubleValue(), ((Number) r.get(1)).doubleValue()};
        return null;
    }

    private static boolean label(String s) {
        return s != null && s.matches("[a-z0-9_]{1,32}");
    }

    /** Refuses what Bentrix would refuse, in its words. */
    public Output check() {
        if (!label(model) || !label(name)) throw new Refused("the model and the output are [a-z0-9_]{1,32}");
        if (!KINDS.contains(kind)) throw new Refused(kind + " is not one of the twelve kinds");
        if (values.isEmpty()) throw new Refused("values holds no value");
        double[] range = range();
        List<?> names = fields.get("names") instanceof List<?> l ? l : List.of();
        boolean softmax = Boolean.TRUE.equals(fields.get("softmax"));
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> v = values.get(i);
            String f = "values[" + i + "]";
            switch (kind) {
                case "scalar" -> inRange(f + ".value", v.get("value"), range);
                case "rank" -> inRange(f + ".value", v.get("value"), range != null ? range : new double[] {0, 1});
                case "event" -> inRange(f + ".score", v.get("score"), new double[] {0, 1});
                case "position" -> inRange(f + ".target", v.get("target"), range);
                case "classes" -> {
                    if (names.isEmpty()) throw new Refused("names a classes output names its classes");
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) v.get("p")).entrySet()) {
                        if (!names.contains(e.getKey())) throw new Refused(f + ".p " + e.getKey() + " is not one of the classes");
                        if (!softmax) inRange(f + ".p." + e.getKey(), e.getValue(), new double[] {0, 1});
                    }
                }
                case "series" -> {
                    if (names.isEmpty()) throw new Refused("names a series output names its lines");
                }
                case "action" -> {
                    if (!INTENTS.contains(v.get("intent"))) throw new Refused(f + ".intent " + v.get("intent") + " is not one of " + String.join(", ", INTENTS));
                }
                case "text" -> {
                    String t = String.valueOf(v.get("text"));
                    if (t.codePointCount(0, t.length()) > 2000) throw new Refused(f + ".text is longer than 2000 characters");
                }
                default -> {
                }
            }
        }
        if (suggestedMeaning != null) suggestedMeaning.check(range);
        return this;
    }

    /** The output_spec document; never an arrival. */
    public Map<String, Object> spec() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("schema_version", 1);
        d.put("model", model);
        d.put("output", name);
        d.put("symbol", symbol);
        d.put("kind", kind);
        d.put("anchor", Map.of("time_ms", anchorMs));
        d.put("values", values);
        d.putAll(fields);
        if (epoch > 0) d.put("epoch", epoch);
        if (suggestedMeaning != null) d.put("suggested_meaning", suggestedMeaning.doc());
        return d;
    }

    /** output_put on the workbench bench, after check. */
    public Object put(Forecast.Caller c, String bench) {
        check();
        return c.call("output_put", Map.of("bench", bench, "output", spec()));
    }

    /** output_put_many: several outputs at one instant. */
    public static Object putOutputs(Forecast.Caller c, String bench, List<Output> outputs, boolean allOrNothing) {
        List<Object> specs = new ArrayList<>();
        for (Output o : outputs) specs.add(o.check().spec());
        return c.call("output_put_many", Map.of("bench", bench, "outputs", specs, "all_or_nothing", allOrNothing));
    }

    /** The output_meaning document: what a value means to the person. */
    public static final class Meaning {
        public String reads = "direction", highMeans, lowMeans;
        public Double actAbove, actBelow;
        public double hysteresis = 0;
        public final Map<String, String> labels = new LinkedHashMap<>();

        /** high above actAbove, low below actBelow; any of them null for none. */
        public static Meaning of(String high, String low, Double actAbove, Double actBelow) {
            Meaning m = new Meaning();
            m.highMeans = high;
            m.lowMeans = low;
            m.actAbove = actAbove;
            m.actBelow = actBelow;
            return m;
        }

        /** Refuses a word that is not the product's, a threshold outside range, a label empty or over 24. */
        public Meaning check(double[] range) {
            for (String w : new String[] {highMeans, lowMeans})
                if (w != null && !MEANING_WORDS.contains(w)) throw new Refused("\"" + w + "\" is not one of the meaning's words");
            for (Double t : new Double[] {actAbove, actBelow})
                if (t != null && range != null && (t < range[0] || t > range[1])) throw new Refused("the output answers " + fmt(range[0]) + " to " + fmt(range[1]) + "; " + fmt(t) + " is outside it");
            for (Map.Entry<String, String> e : labels.entrySet()) {
                String l = e.getValue();
                if (!MEANING_WORDS.contains(e.getKey()) || l == null || l.isEmpty() || l.codePointCount(0, l.length()) > 24)
                    throw new Refused("the label \"" + l + "\" for " + e.getKey() + " is not a meaning word's, or is empty or longer than 24");
            }
            return this;
        }

        /** The document. */
        public Map<String, Object> doc() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("reads", reads);
            d.put("hysteresis", hysteresis);
            if (highMeans != null) d.put("high_means", highMeans);
            if (lowMeans != null) d.put("low_means", lowMeans);
            if (actAbove != null) d.put("act_above", actAbove);
            if (actBelow != null) d.put("act_below", actBelow);
            if (!labels.isEmpty()) d.put("labels", new LinkedHashMap<>(labels));
            return d;
        }
    }

    /** output_meaning_set: the person's meaning of model's output on the workbench bench. */
    public static Object setMeaning(Forecast.Caller c, String bench, String model, String output, Meaning m, double[] range) {
        m.check(range);
        return c.call("output_meaning_set", Map.of("bench", bench, "model", model, "output", output, "meaning", m.doc()));
    }
}
