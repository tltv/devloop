package com.vaadin.devloop.daemon;

/** Just enough escaping to emit the daemon's own JSON without a dependency. */
final class Json {

    private Json() {
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default -> {
                if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
            }
        }
        return sb.toString();
    }

    static String array(java.util.List<String> items) {
        return "[" + String.join(",", items) + "]";
    }

    static String strings(java.util.List<String> items) {
        return array(items.stream().map(s -> "\"" + escape(s) + "\"").toList());
    }
}
