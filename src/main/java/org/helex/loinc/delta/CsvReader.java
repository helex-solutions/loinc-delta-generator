package org.helex.loinc.delta;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 reader for the LOINC CSV files: every field quoted, {@code ""} escapes a quote, fields may
 * hold commas and line breaks, rows end in CRLF (LF is accepted too), and a leading BOM is dropped.
 * Blank lines are skipped.
 */
final class CsvReader implements Closeable {

    private final PushbackReader in;
    private boolean first = true;
    private long line = 1;

    CsvReader(InputStream stream) {
        this.in = new PushbackReader(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 1 << 16), 1);
    }

    /** The next record, or {@code null} at the end of the input. */
    String[] next() throws IOException {
        while (true) {
            String[] record = readRecord();
            if (record == null || record.length > 1 || !record[0].isEmpty()) {
                return record;
            }
        }
    }

    private String[] readRecord() throws IOException {
        int c = in.read();
        if (first) {
            first = false;
            if (c == '﻿') {
                c = in.read();
            }
        }
        if (c == -1) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStart = true;
        long startLine = line;
        while (true) {
            if (quoted) {
                if (c == -1) {
                    throw new IOException("Unterminated quoted field starting on line " + startLine);
                }
                if (c == '"') {
                    int n = in.read();
                    if (n == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        c = n;
                        continue;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append((char) c);
                }
            } else if (c == '"' && fieldStart) {
                quoted = true;
                fieldStart = false;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                fieldStart = true;
            } else if (c == '\r' || c == '\n' || c == -1) {
                if (c == '\r') {
                    int n = in.read();
                    if (n != '\n' && n != -1) {
                        in.unread(n);
                    }
                }
                if (c != -1) {
                    line++;
                }
                fields.add(field.toString());
                return fields.toArray(String[]::new);
            } else {
                field.append((char) c);
                fieldStart = false;
            }
            c = in.read();
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
