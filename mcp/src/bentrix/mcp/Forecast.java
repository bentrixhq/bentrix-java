// A model's forecast on the chart, built by hand: the forecast_spec document step by step - a time form
// (an interval) or an element form (a chart built by price: renko, kagi, line_break, point_figure, range_bars - no
// interval, its rule in form.elements) - put through forecast_put; and an evaluation's sheet waited on:
// forecast_eval_make's answer is the run at once, or an ask the person answers in Bentrix's window, watched on the
// bentrix://forecasts/eval event. A forecast never names its arrival: Bentrix stamps put_ms.
package bentrix.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The forecast_spec document. anchorMs is the open of the last closed bar (or element) it is made at. */
public final class Forecast {
    /** What a forecast is put through: {@code client::call}, or a test's script. */
    @FunctionalInterface
    public interface Caller {
        Object call(String name, Map<String, ?> args);
    }

    /** The event an ask's moves are carried on. */
    public static final String EVAL_EVENT = "bentrix://forecasts/eval";

    /** One bar of a forecast; everything but close left out when null or empty. */
    public static final class Step {
        public double close;
        public Double open, high, low, volume;
        public Long expectedMs;
        public final List<double[]> bands = new ArrayList<>(); // {lo, hi} or {lo, hi, p}
        public final Map<String, Double> quantiles = new LinkedHashMap<>();
        public final List<Double> samples = new ArrayList<>();

        public Step(double close) {
            this.close = close;
        }

        /** When the step is expected - on every step or none; what places an element form on a chart by time. */
        public Step expected(long ms) {
            this.expectedMs = ms;
            return this;
        }

        Map<String, Object> value() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("close", close);
            if (open != null) s.put("open", open);
            if (high != null) s.put("high", high);
            if (low != null) s.put("low", low);
            if (volume != null) s.put("volume", volume);
            if (expectedMs != null) s.put("expected_ms", expectedMs);
            if (!bands.isEmpty()) {
                List<Object> b = new ArrayList<>();
                for (double[] x : bands) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("lo", x[0]);
                    m.put("hi", x[1]);
                    if (x.length > 2) m.put("p", x[2]);
                    b.add(m);
                }
                s.put("bands", b);
            }
            if (!quantiles.isEmpty()) s.put("quantiles", new LinkedHashMap<>(quantiles));
            if (!samples.isEmpty()) s.put("samples", new ArrayList<>(samples));
            return s;
        }
    }

    public final String model, symbol, chartType;
    /** A time form's interval; null for an element form. */
    public final String timeframe;
    /** An element form's rule, where it is not the chart's own. */
    public final Map<String, ?> elements;
    public final long anchorMs;
    public int epoch = 0;
    public String note;
    public Map<String, String> look;
    public final List<Step> steps = new ArrayList<>();

    private Forecast(String model, String symbol, String chartType, String timeframe, Map<String, ?> elements, long anchorMs) {
        this.model = model;
        this.symbol = symbol;
        this.chartType = chartType;
        this.timeframe = timeframe;
        this.elements = elements;
        this.anchorMs = anchorMs;
    }

    /** A forecast of timeframe's bars, drawn as candles. */
    public static Forecast ofTime(String model, String symbol, String timeframe, long anchorMs) {
        return new Forecast(model, symbol, "candlestick", timeframe, null, anchorMs);
    }

    /** A forecast of a chart built by price: no interval, elements its rule (null for the chart's own). */
    public static Forecast ofElements(String model, String symbol, String chartType, long anchorMs, Map<String, ?> elements) {
        return new Forecast(model, symbol, chartType, null, elements, anchorMs);
    }

    public Forecast step(Step s) {
        steps.add(s);
        return this;
    }

    /** The document. */
    public Map<String, Object> spec() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("chart_type", chartType);
        if (timeframe != null) form.put("timeframe", timeframe);
        if (elements != null) form.put("elements", new LinkedHashMap<>(elements));
        List<Object> st = new ArrayList<>();
        for (Step s : steps) st.add(s.value());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("schema_version", 1);
        d.put("model", model);
        d.put("symbol", symbol);
        d.put("form", form);
        d.put("anchor", Map.of("time_ms", anchorMs));
        d.put("steps", st);
        d.put("epoch", epoch);
        if (note != null) d.put("note", note);
        if (look != null) d.put("look", look);
        return d;
    }

    /** forecast_put on the workbench bench: its arrival, its state, where it stands on each chart. */
    public Object put(Caller c, String bench) {
        return c.call("forecast_put", Map.of("bench", bench, "forecast", spec()));
    }

    /** forecast_put_many: several markets and models at one instant. */
    public static Object putMany(Caller c, String bench, List<Forecast> fs, boolean allOrNothing) {
        List<Object> specs = new ArrayList<>();
        for (Forecast f : fs) specs.add(f.spec());
        return c.call("forecast_put_many", Map.of("bench", bench, "forecasts", specs, "all_or_nothing", allOrNothing));
    }

    /** The person's No on the evaluation's sheet. Not a failure: nothing was made or fetched. */
    public static final class DeclinedByThePerson extends RuntimeException {
        public final String ask;

        public DeclinedByThePerson(String ask) {
            super("the person declined " + ask);
            this.ask = ask;
        }
    }

    /** A laboratory not made over the span, or a run that did not start on it, with Bentrix's why. */
    public static final class EvalFailed extends RuntimeException {
        public final String ask, why;

        public EvalFailed(String ask, String why) {
            super(why);
            this.ask = ask;
            this.why = why;
        }
    }

    /**
     * The run to drive, from forecast_eval_make's answer: its eval at once, or - over days the workbench's laboratory
     * does not hold - the person's answer on the sheet, watched on EVAL_EVENT: started answers the run's id; declined
     * throws DeclinedByThePerson; failed throws EvalFailed. No tool answers the sheet. idleLimit waits in a row with no
     * move of this ask end the watch (never, when 0).
     */
    @SuppressWarnings("unchecked")
    public static long evalStarted(Caller c, Map<String, ?> made, long timeoutMs, int idleLimit) {
        if (made.get("eval") instanceof Number n) return n.longValue();
        String ask = String.valueOf(made.get("ask"));
        String uri = made.get("watch") instanceof String w ? w : EVAL_EVENT;
        String cursor = "0";
        int at = uri.indexOf("after=");
        if (at >= 0) cursor = uri.substring(at + 6).split("&", 2)[0];
        uri = uri.split("[{?]", 2)[0];
        for (int idle = 0; idleLimit == 0 || idle < idleLimit;) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("uri", uri);
            args.put("after", cursor);
            args.put("timeout_ms", timeoutMs);
            Map<String, Object> page = (Map<String, Object>) c.call("wait_for", args);
            if (page.get("next") instanceof String n) cursor = n;
            boolean moved = false;
            if (page.get("items") instanceof List<?> items) {
                for (Object o : items) {
                    Map<String, Object> item = (Map<String, Object>) o;
                    if (!ask.equals(item.get("ask"))) continue;
                    moved = true;
                    Object state = item.get("state");
                    if ("started".equals(state)) return ((Number) item.get("eval")).longValue();
                    if ("declined".equals(state)) throw new DeclinedByThePerson(ask);
                    if ("failed".equals(state)) throw new EvalFailed(ask, item.get("why") instanceof String w ? w : "the evaluation did not start");
                }
            }
            idle = moved ? 0 : idle + 1;
        }
        throw new IllegalStateException(ask + ": no answer on the sheet");
    }
}
