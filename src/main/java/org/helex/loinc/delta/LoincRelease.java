package org.helex.loinc.delta;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One LOINC release zip ({@code Loinc_2.82.zip}): its CSV entries by normalized path, and its version.
 *
 * <p>Paths are normalized so two releases line up: if every CSV sits under one wrapping folder that
 * is not a LOINC folder (a re-zipped {@code Loinc_2.82/LoincTable/Loinc.csv}), that folder is dropped.
 */
final class LoincRelease implements Closeable {

    private static final Pattern FILE_NAME_VERSION = Pattern.compile("(?i)loinc[_-]?v?(\\d+\\.\\d+)");
    private static final Set<String> LOINC_TOP_FOLDERS = Set.of("AccessoryFiles", "LoincTable", "LoincTableCore");

    private final Path path;
    private final ZipFile zip;
    private final Map<String, ZipEntry> csvEntries;
    private final String version;

    private LoincRelease(Path path, ZipFile zip, Map<String, ZipEntry> csvEntries, String version) {
        this.path = path;
        this.zip = zip;
        this.csvEntries = csvEntries;
        this.version = version;
    }

    /** Opens the zip; {@code versionOverride} wins over the version read from the file name or Loinc.csv. */
    static LoincRelease open(Path path, String versionOverride) throws IOException {
        ZipFile zip = new ZipFile(path.toFile());
        try {
            Map<String, ZipEntry> entries = csvEntries(zip);
            if (entries.isEmpty()) {
                throw new IOException(path + " holds no CSV files; is it a LOINC release zip?");
            }
            LoincRelease release = new LoincRelease(path, zip, entries, null);
            String version = versionOverride != null ? versionOverride : release.detectVersion();
            return new LoincRelease(path, zip, entries, version);
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
    }

    private static Map<String, ZipEntry> csvEntries(ZipFile zip) {
        Map<String, ZipEntry> raw = new LinkedHashMap<>();
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            String name = entry.getName().replace('\\', '/');
            String base = name.substring(name.lastIndexOf('/') + 1);
            // macOS archives carry __MACOSX/._Loinc.csv resource forks.
            if (!entry.isDirectory() && name.toLowerCase().endsWith(".csv")
                    && !name.startsWith("__MACOSX/") && !base.startsWith("._")) {
                raw.put(name, entry);
            }
        }
        String wrapper = commonWrapperFolder(raw.keySet());
        if (wrapper == null) {
            return Collections.unmodifiableMap(raw);
        }
        Map<String, ZipEntry> stripped = new LinkedHashMap<>();
        raw.forEach((name, entry) -> stripped.put(name.substring(wrapper.length() + 1), entry));
        return Collections.unmodifiableMap(stripped);
    }

    private static String commonWrapperFolder(Set<String> names) {
        String folder = null;
        for (String name : names) {
            int slash = name.indexOf('/');
            if (slash < 0) {
                return null;
            }
            String first = name.substring(0, slash);
            if (folder == null) {
                folder = first;
            } else if (!folder.equals(first)) {
                return null;
            }
        }
        return folder == null || LOINC_TOP_FOLDERS.contains(folder) ? null : folder;
    }

    /** {@code Loinc_2.82.zip} gives 2.82; otherwise the highest VersionLastChanged in LoincTable/Loinc.csv. */
    private String detectVersion() throws IOException {
        Matcher m = FILE_NAME_VERSION.matcher(path.getFileName().toString());
        if (m.find()) {
            return m.group(1);
        }
        ZipEntry loinc = csvEntries.get("LoincTable/Loinc.csv");
        if (loinc == null) {
            return null;
        }
        try (CsvReader reader = new CsvReader(zip.getInputStream(loinc))) {
            String[] header = reader.next();
            int col = header == null ? -1 : indexOf(header, "VersionLastChanged");
            if (col < 0) {
                return null;
            }
            String max = null;
            for (String[] row = reader.next(); row != null; row = reader.next()) {
                if (col < row.length && !row[col].isEmpty() && (max == null || compareVersions(row[col], max) > 0)) {
                    max = row[col];
                }
            }
            return max;
        }
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0;
            int yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int indexOf(String[] header, String column) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equals(column)) {
                return i;
            }
        }
        return -1;
    }

    Path path() {
        return path;
    }

    /** The release version, or {@code null} when neither the file name nor Loinc.csv gives one. */
    String version() {
        return version;
    }

    Set<String> csvPaths() {
        return csvEntries.keySet();
    }

    boolean has(String csvPath) {
        return csvEntries.containsKey(csvPath);
    }

    CsvReader read(String csvPath) throws IOException {
        InputStream in = zip.getInputStream(csvEntries.get(csvPath));
        return new CsvReader(in);
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
