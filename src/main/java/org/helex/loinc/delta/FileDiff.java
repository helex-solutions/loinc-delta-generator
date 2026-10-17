package org.helex.loinc.delta;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Diffs one CSV file between two releases and writes its delta rows as a zip entry.
 *
 * <p>Memory stays bounded by keys and 64-bit row hashes, not by row content, so the 1.3M-row
 * LoincPartLink_Supplementary.csv fits in a small heap. The price is four streaming passes:
 * <ol>
 *   <li>index the previous file (key → hash of the compared columns);</li>
 *   <li>index the current file the same way, then classify keys as added, modified or removed;</li>
 *   <li>re-read the previous file: keep the old values of modified rows, spool removed rows;</li>
 *   <li>re-read the current file: write ADDED and MODIFIED rows in file order, then the spooled REMOVED rows.</li>
 * </ol>
 *
 * <p>Rows are compared on the columns both headers share, matched by name. A column that only one
 * release has is reported in the summary but does not by itself make every row MODIFIED.
 */
final class FileDiff {

    static final String ACTION = "DELTA_ACTION";
    static final String CHANGED_COLUMNS = "DELTA_CHANGED_COLUMNS";

    private static final char SEP = '\u001F';
    /** Separates a repeated key from its occurrence number; cannot appear in LOINC text. */
    private static final String OCCURRENCE = "\u0000#";

    private final String path;
    private final LoincRelease previous;
    private final LoincRelease current;
    private final Path tempDir;

    FileDiff(String path, LoincRelease previous, LoincRelease current, Path tempDir) {
        this.path = path;
        this.previous = previous;
        this.current = current;
        this.tempDir = tempDir;
    }

    FileResult run(ZipOutputStream out) throws IOException {
        if (!previous.has(path)) {
            return wholeFile(current, "ADDED", FileResult.State.FILE_ADDED, out);
        }
        if (!current.has(path)) {
            return wholeFile(previous, "REMOVED", FileResult.State.FILE_REMOVED, out);
        }
        FileResult result = new FileResult(path);
        String[] prevHeader = header(previous);
        String[] curHeader = header(current);

        // Compared columns: the current header's order, restricted to names the previous header has.
        List<String> common = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (String col : curHeader) {
            (indexOf(prevHeader, col) >= 0 ? common : added).add(col);
        }
        List<String> dropped = new ArrayList<>();
        for (String col : prevHeader) {
            if (indexOf(curHeader, col) < 0) {
                dropped.add(col);
            }
        }
        result.columnsAdded = added;
        result.columnsRemoved = dropped;
        int[] prevCmp = indexes(prevHeader, common);
        int[] curCmp = indexes(curHeader, common);

        List<String> keys = FileKeys.forPath(path);
        if (!keys.isEmpty() && !common.containsAll(keys)) {
            result.keyFallback = true;
            keys = List.of();
        }
        result.keyColumns = keys;
        int[] prevKey = keys.isEmpty() ? prevCmp : indexes(prevHeader, keys);
        int[] curKey = keys.isEmpty() ? curCmp : indexes(curHeader, keys);

        // Passes 1 and 2.
        Index prevIndex = index(previous, prevKey, prevCmp);
        Index curIndex = index(current, curKey, curCmp);
        result.duplicateKeysPrevious = prevIndex.duplicateBases.size();
        result.duplicateKeysCurrent = curIndex.duplicateBases.size();
        Set<String> addedKeys = new HashSet<>();
        Set<String> modifiedKeys = new HashSet<>();
        Set<String> removedKeys = new HashSet<>();
        curIndex.hashes.forEach((key, hash) -> {
            Long old = prevIndex.hashes.get(key);
            if (old == null) {
                addedKeys.add(key);
            } else if (old.longValue() != hash.longValue()) {
                modifiedKeys.add(key);
            }
        });
        for (String key : prevIndex.hashes.keySet()) {
            if (!curIndex.hashes.containsKey(key)) {
                removedKeys.add(key);
            }
        }
        result.added = addedKeys.size();
        result.modified = modifiedKeys.size();
        result.removed = removedKeys.size();
        result.unchanged = curIndex.hashes.size() - result.added - result.modified;
        Set<String> prevDuplicates = prevIndex.duplicateBases;
        Set<String> curDuplicates = curIndex.duplicateBases;
        prevIndex.hashes.clear();
        curIndex.hashes.clear();
        if (!result.hasRows()) {
            return result;
        }
        result.state = FileResult.State.CHANGED;

        // Pass 3: old values of modified rows; removed rows spooled in the current header's layout.
        int[] prevByCurColumn = new int[curHeader.length];
        for (int i = 0; i < curHeader.length; i++) {
            prevByCurColumn[i] = indexOf(prevHeader, curHeader[i]);
        }
        Map<String, String[]> oldValues = new HashMap<>();
        Path spool = Files.createTempFile(tempDir, "removed", ".csv");
        try {
            try (OutputStream spoolOut = Files.newOutputStream(spool); CsvReader reader = previous.read(path)) {
                CsvWriter writer = new CsvWriter(spoolOut);
                reader.next();
                Occurrences occurrences = new Occurrences(prevDuplicates);
                for (String[] row = reader.next(); row != null; row = reader.next()) {
                    String key = occurrences.key(key(row, prevKey));
                    if (modifiedKeys.contains(key)) {
                        oldValues.put(key, values(row, prevCmp));
                    } else if (removedKeys.contains(key)) {
                        String[] line = new String[curHeader.length + 2];
                        line[0] = "REMOVED";
                        line[1] = "";
                        for (int i = 0; i < curHeader.length; i++) {
                            line[i + 2] = prevByCurColumn[i] < 0 ? "" : field(row, prevByCurColumn[i]);
                        }
                        writer.write(line);
                    }
                }
                writer.flush();
            }

            // Pass 4.
            out.putNextEntry(new ZipEntry(path));
            CsvWriter writer = new CsvWriter(new NonClosing(out));
            writer.write(withActionColumns(curHeader));
            int status = statusColumn(common);
            try (CsvReader reader = current.read(path)) {
                reader.next();
                Occurrences occurrences = new Occurrences(curDuplicates);
                for (String[] row = reader.next(); row != null; row = reader.next()) {
                    String key = occurrences.key(key(row, curKey));
                    if (addedKeys.contains(key)) {
                        writer.write(line("ADDED", "", row, curHeader.length));
                    } else if (modifiedKeys.contains(key)) {
                        String[] old = oldValues.get(key);
                        List<String> changed = new ArrayList<>();
                        for (int i = 0; i < curCmp.length; i++) {
                            String now = field(row, curCmp[i]);
                            if (!old[i].equals(now)) {
                                changed.add(common.get(i));
                                result.changedColumns.merge(common.get(i), 1L, Long::sum);
                                if (i == status) {
                                    result.statusTransitions.merge(old[i] + " -> " + now, 1L, Long::sum);
                                }
                            }
                        }
                        writer.write(line("MODIFIED", String.join(";", changed), row, curHeader.length));
                    }
                }
            }
            writer.flush();
            Files.copy(spool, out);
            out.closeEntry();
        } finally {
            Files.deleteIfExists(spool);
        }
        return result;
    }

    /** A file only one release has: every row is ADDED (or REMOVED), in that file's own header. */
    private FileResult wholeFile(LoincRelease release, String action, FileResult.State state, ZipOutputStream out)
            throws IOException {
        FileResult result = new FileResult(path);
        result.state = state;
        result.keyColumns = FileKeys.forPath(path);
        try (CsvReader reader = release.read(path)) {
            String[] header = reader.next();
            if (header == null) {
                return result;
            }
            out.putNextEntry(new ZipEntry(path));
            CsvWriter writer = new CsvWriter(new NonClosing(out));
            writer.write(withActionColumns(header));
            long rows = 0;
            for (String[] row = reader.next(); row != null; row = reader.next()) {
                writer.write(line(action, "", row, header.length));
                rows++;
            }
            writer.flush();
            out.closeEntry();
            if (state == FileResult.State.FILE_ADDED) {
                result.added = rows;
            } else {
                result.removed = rows;
            }
        }
        return result;
    }

    private Index index(LoincRelease release, int[] keyColumns, int[] compared) throws IOException {
        Index index = new Index();
        try (CsvReader reader = release.read(path)) {
            reader.next();
            for (String[] row = reader.next(); row != null; row = reader.next()) {
                String base = key(row, keyColumns);
                String key = base;
                if (index.hashes.containsKey(key)) {
                    index.duplicateBases.add(base);
                    int n = 2;
                    while (index.hashes.containsKey(base + OCCURRENCE + n)) {
                        n++;
                    }
                    key = base + OCCURRENCE + n;
                }
                index.hashes.put(key, hash(row, compared));
            }
        }
        return index;
    }

    private String[] header(LoincRelease release) throws IOException {
        try (CsvReader reader = release.read(path)) {
            String[] header = reader.next();
            return header == null ? new String[0] : header;
        }
    }

    private static String key(String[] row, int[] columns) {
        if (columns.length == 1) {
            return field(row, columns[0]);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(field(row, columns[i]));
        }
        return sb.toString();
    }

    /** 64-bit FNV-1a over the compared values. */
    private static long hash(String[] row, int[] columns) {
        long h = 0xcbf29ce484222325L;
        for (int column : columns) {
            String value = field(row, column);
            for (int i = 0; i < value.length(); i++) {
                h ^= value.charAt(i);
                h *= 0x100000001b3L;
            }
            h ^= SEP;
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static String[] values(String[] row, int[] columns) {
        String[] values = new String[columns.length];
        for (int i = 0; i < columns.length; i++) {
            values[i] = field(row, columns[i]);
        }
        return values;
    }

    private static String field(String[] row, int index) {
        return index < row.length ? row[index] : "";
    }

    private static String[] line(String action, String changed, String[] row, int width) {
        String[] line = new String[width + 2];
        line[0] = action;
        line[1] = changed;
        for (int i = 0; i < width; i++) {
            line[i + 2] = field(row, i);
        }
        return line;
    }

    private static String[] withActionColumns(String[] header) {
        String[] line = new String[header.length + 2];
        line[0] = ACTION;
        line[1] = CHANGED_COLUMNS;
        System.arraycopy(header, 0, line, 2, header.length);
        return line;
    }

    private static int statusColumn(List<String> common) {
        for (int i = 0; i < common.size(); i++) {
            if (common.get(i).equalsIgnoreCase("STATUS")) {
                return i;
            }
        }
        return -1;
    }

    private static int[] indexes(String[] header, List<String> columns) {
        return columns.stream().mapToInt(c -> indexOf(header, c)).toArray();
    }

    private static int indexOf(String[] header, String column) {
        return Arrays.asList(header).indexOf(column);
    }

    private static final class Index {
        final Map<String, Long> hashes = new HashMap<>();
        final Set<String> duplicateBases = new HashSet<>();
    }

    /** Re-numbers repeated keys in a later pass exactly as {@link #index} numbered them. */
    private static final class Occurrences {
        private final Set<String> duplicateBases;
        private final Map<String, Integer> seen = new HashMap<>();

        Occurrences(Set<String> duplicateBases) {
            this.duplicateBases = duplicateBases;
        }

        String key(String base) {
            if (!duplicateBases.contains(base)) {
                return base;
            }
            int n = seen.merge(base, 1, Integer::sum);
            return n == 1 ? base : base + OCCURRENCE + n;
        }
    }

    /** Lets a {@link CsvWriter} write into the open zip entry without closing the zip. */
    private static final class NonClosing extends OutputStream {
        private final OutputStream out;

        NonClosing(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() {
        }
    }
}
