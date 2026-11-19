package io.cloudscale;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 parser (quoted fields, "" escapes, CRLF/LF). Bounded: refuses more than maxRows rows. */
public final class Csv {
    private Csv() {}

    public static List<String[]> parse(String s, int maxRows) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean rowHasContent = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> { quoted = true; rowHasContent = true; }
                case ',' -> { row.add(field.toString()); field.setLength(0); rowHasContent = true; }
                case '\r' -> { }
                case '\n' -> {
                    if (rowHasContent || field.length() > 0) {
                        row.add(field.toString());
                        rows.add(row.toArray(new String[0]));
                        if (rows.size() > maxRows) {
                            throw new PermanentJobException("CSV has more than " + maxRows + " rows");
                        }
                    }
                    row = new ArrayList<>();
                    field.setLength(0);
                    rowHasContent = false;
                }
                default -> { field.append(c); rowHasContent = true; }
            }
        }
        if (quoted) {
            throw new PermanentJobException("CSV has an unterminated quoted field");
        }
        if (rowHasContent || field.length() > 0) {
            row.add(field.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }
}
