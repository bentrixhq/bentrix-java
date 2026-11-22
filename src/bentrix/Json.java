// A small dependency-free JSON reader and writer: objects are LinkedHashMap<String, Object>, arrays
// List<Object>, numbers Double (read whole: Double.parseDouble gives the double the engine wrote),
// strings, booleans and null.
package bentrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static Object parse(String text) {
        int[] at = {0};
        Object v = value(text, at);
        space(text, at);
        if (at[0] != text.length()) {
            throw new BentrixException("JSON: trailing text");
        }
        return v;
    }

    public static String write(Object v) {
        StringBuilder out = new StringBuilder();
        write(v, out);
        return out.toString();
    }

    private static void write(Object v, StringBuilder out) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof Boolean b) {
            out.append(b);
        } else if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new BentrixException("JSON: a number that is not finite");
            }
            out.append(d);
        } else if (v instanceof Float f) {
            write(f.doubleValue(), out);
        } else if (v instanceof Number n) {
            out.append(n.longValue());
        } else if (v instanceof String s) {
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        } else if (v instanceof Map<?, ?> m) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() == null) {
                    continue; // an argument not given is absent, never null
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (v instanceof List<?> l) {
            out.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(l.get(i), out);
            }
            out.append(']');
        } else {
            throw new BentrixException("JSON: cannot write a " + v.getClass().getName());
        }
    }

    private static void space(String s, int[] at) {
        while (at[0] < s.length() && " \n\r\t".indexOf(s.charAt(at[0])) >= 0) {
            at[0]++;
        }
    }

    private static Object value(String s, int[] at) {
        space(s, at);
        if (at[0] >= s.length()) {
            throw new BentrixException("JSON: unexpected end");
        }
        char c = s.charAt(at[0]);
        if (c == '{') {
            Map<String, Object> o = new LinkedHashMap<>();
            at[0]++;
            space(s, at);
            if (s.charAt(at[0]) == '}') {
                at[0]++;
                return o;
            }
            for (;;) {
                space(s, at);
                String k = string(s, at);
                space(s, at);
                at[0]++; // ':'
                o.put(k, value(s, at));
                space(s, at);
                if (s.charAt(at[0]++) != ',') {
                    return o;
                }
            }
        }
        if (c == '[') {
            List<Object> a = new ArrayList<>();
            at[0]++;
            space(s, at);
            if (s.charAt(at[0]) == ']') {
                at[0]++;
                return a;
            }
            for (;;) {
                a.add(value(s, at));
                space(s, at);
                if (s.charAt(at[0]++) != ',') {
                    return a;
                }
            }
        }
        if (c == '"') {
            return string(s, at);
        }
        if (s.startsWith("true", at[0])) {
            at[0] += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", at[0])) {
            at[0] += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", at[0])) {
            at[0] += 4;
            return null;
        }
        int start = at[0];
        while (at[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(at[0])) >= 0) {
            at[0]++;
        }
        return Double.parseDouble(s.substring(start, at[0]));
    }

    private static String string(String s, int[] at) {
        StringBuilder out = new StringBuilder();
        at[0]++; // the opening quote
        while (s.charAt(at[0]) != '"') {
            char c = s.charAt(at[0]++);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = s.charAt(at[0]++);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    // Java strings are UTF-16, so a surrogate pair is two chars appended as they come.
                    out.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16));
                    at[0] += 4;
                }
                default -> out.append(e);
            }
        }
        at[0]++; // the closing quote
        return out.toString();
    }
}
