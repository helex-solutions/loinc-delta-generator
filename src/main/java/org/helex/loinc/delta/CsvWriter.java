package org.helex.loinc.delta;

import java.io.BufferedWriter;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Writes CSV the way LOINC ships it: every field quoted, CRLF line endings, no BOM. */
final class CsvWriter implements Flushable {

    private final BufferedWriter out;

    CsvWriter(OutputStream stream) {
        this.out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), 1 << 16);
    }

    void write(String... fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write('"');
            String f = fields[i] == null ? "" : fields[i];
            out.write(f.indexOf('"') < 0 ? f : f.replace("\"", "\"\""));
            out.write('"');
        }
        out.write("\r\n");
    }

    /** Flushes the buffer without closing the underlying stream (a zip entry stays open). */
    @Override
    public void flush() throws IOException {
        out.flush();
    }
}
