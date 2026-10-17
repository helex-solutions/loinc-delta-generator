package org.helex.loinc.delta;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Command line entry point:
 *
 * <pre>
 * java -jar loinc-delta-generator.jar &lt;previous.zip&gt; &lt;current.zip&gt; [options]
 * </pre>
 *
 * Writes a delta zip that mirrors the release layout. Each changed CSV keeps its own columns behind
 * two leading ones, {@code DELTA_ACTION} (ADDED, MODIFIED or REMOVED) and {@code DELTA_CHANGED_COLUMNS};
 * {@code delta-summary.json} and {@code delta-summary.md} sit at the root. Prints
 * {@code Rows exported: N} last, like the SNOMED delta generator, so a host can parse it the same way.
 *
 * <p>Exit codes: 0 success (an empty delta included), 1 failure, 2 bad arguments.
 */
public final class LoincDeltaGenerator {

    static final String SUMMARY_JSON = "delta-summary.json";
    static final String SUMMARY_MD = "delta-summary.md";

    private static final String USAGE = """
            Usage: java -jar loinc-delta-generator.jar <previous.zip> <current.zip> [options]

            Compares two LOINC release zips and writes the rows that were added, modified or
            removed, file by file, to a delta zip.

            Options:
              -d, --output-dir <dir>      Folder for the delta zip, created if missing
                                          (default: the current folder)
              -o, --output <file>         File name of the delta zip; a relative name is placed in --output-dir,
                                          and an existing folder gets the default name inside it
                                          (default: LoincDelta_<previous>_<current>.zip)
                  --previous-version <v>  Version label of the previous release (default: from the file name,
                                          else the highest VersionLastChanged in LoincTable/Loinc.csv)
                  --current-version <v>   Version label of the current release (same default)
                  --include <glob>        Only compare CSV paths matching the glob; repeatable
                                          (e.g. 'AccessoryFiles/LinguisticVariants/*')
                  --exclude <glob>        Skip CSV paths matching the glob; repeatable
                  --summary-only          Print the summary and write no zip
              -h, --help                  Show this help
                  --version               Show the tool version
            """;

    private LoincDeltaGenerator() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println();
            err.print(USAGE);
            return 2;
        }
        if (options.help) {
            out.print(USAGE);
            return 0;
        }
        if (options.version) {
            out.println("loinc-delta-generator " + toolVersion());
            return 0;
        }
        try {
            generate(options, out, err);
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("Failed: " + describe(e));
            return 1;
        }
    }

    /** File-system exceptions carry only the path as their message; say what happened to it. */
    static String describe(Exception e) {
        if (e instanceof FileSystemException fse) {
            String what = switch (e) {
                case NoSuchFileException x -> "no such file or folder";
                case AccessDeniedException x -> "permission denied";
                case FileAlreadyExistsException x -> "already exists";
                case DirectoryNotEmptyException x -> "is a folder that is not empty";
                case NotDirectoryException x -> "is not a folder";
                default -> fse.getReason() != null ? fse.getReason() : e.getClass().getSimpleName();
            };
            return fse.getFile() + ": " + what;
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    static void generate(Options options, PrintStream out, PrintStream err) throws IOException {
        for (Path zip : List.of(options.previous, options.current)) {
            if (!Files.isRegularFile(zip)) {
                throw new IOException("No such file: " + zip);
            }
        }
        long started = System.nanoTime();
        try (LoincRelease previous = LoincRelease.open(options.previous, options.previousVersion);
             LoincRelease current = LoincRelease.open(options.current, options.currentVersion)) {
            out.println("Previous: " + previous.path() + " (version " + previous.version() + ")");
            out.println("Current:  " + current.path() + " (version " + current.version() + ")");
            if (previous.version() != null && current.version() != null
                    && LoincRelease.compareVersions(current.version(), previous.version()) <= 0) {
                err.println("Warning: the current release (" + current.version() + ") is not newer than the previous ("
                        + previous.version() + "); the delta runs backwards.");
            }

            List<String> paths = selectPaths(previous, current, options);
            String defaultName = defaultOutputName(previous.version(), current.version());
            Path target = options.outputDir.resolve(options.output != null ? options.output : Path.of(defaultName));
            // `-o ~/deltas` naming an existing folder means "put it there", like -d.
            if (Files.isDirectory(target)) {
                target = target.resolve(defaultName);
            }
            Path parent = target.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tempDir = Files.createTempDirectory("loinc-delta");
            Path partial = Files.createTempFile(parent, ".loinc-delta", ".zip.partial");
            try {
                List<FileResult> results = new ArrayList<>();
                try (OutputStream sink = options.summaryOnly ? OutputStream.nullOutputStream() : Files.newOutputStream(partial);
                     ZipOutputStream zip = new ZipOutputStream(sink, StandardCharsets.UTF_8)) {
                    for (String path : paths) {
                        long fileStarted = System.nanoTime();
                        FileResult result = new FileDiff(path, previous, current, tempDir).run(zip);
                        results.add(result);
                        out.printf("  %-70s %-12s +%d ~%d -%d (%.1fs)%n", path, result.state,
                                result.added, result.modified, result.removed, (System.nanoTime() - fileStarted) / 1e9);
                    }
                    Summary summary = new Summary(previous, current, results, toolVersion(), Instant.now());
                    writeEntry(zip, SUMMARY_JSON, summary.json());
                    writeEntry(zip, SUMMARY_MD, summary.markdown());
                    zip.finish();
                    out.println();
                    out.print(summary.markdown());
                    out.println();
                    if (!options.summaryOnly) {
                        sink.close();
                        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                        out.println("Delta written to " + target.toAbsolutePath());
                    }
                    out.printf("Finished in %.1fs%n", (System.nanoTime() - started) / 1e9);
                    out.println("Rows exported: " + summary.rowsExported());
                }
            } finally {
                Files.deleteIfExists(partial);
                deleteTree(tempDir);
            }
        }
    }

    private static List<String> selectPaths(LoincRelease previous, LoincRelease current, Options options) {
        List<PathMatcher> includes = options.includes.stream().map(LoincDeltaGenerator::glob).toList();
        List<PathMatcher> excludes = options.excludes.stream().map(LoincDeltaGenerator::glob).toList();
        TreeSet<String> all = new TreeSet<>(previous.csvPaths());
        all.addAll(current.csvPaths());
        return all.stream()
                .filter(p -> includes.isEmpty() || includes.stream().anyMatch(m -> m.matches(Path.of(p))))
                .filter(p -> excludes.stream().noneMatch(m -> m.matches(Path.of(p))))
                .toList();
    }

    private static PathMatcher glob(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    static String defaultOutputName(String previousVersion, String currentVersion) {
        return "LoincDelta_" + (previousVersion == null ? "previous" : previousVersion)
                + "_" + (currentVersion == null ? "current" : currentVersion) + ".zip";
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    static String toolVersion() {
        String v = LoincDeltaGenerator.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    static final class Options {
        Path previous;
        Path current;
        Path output;
        Path outputDir = Path.of(".");
        String previousVersion;
        String currentVersion;
        final List<String> includes = new ArrayList<>();
        final List<String> excludes = new ArrayList<>();
        boolean summaryOnly;
        boolean help;
        boolean version;

        static Options parse(String[] args) {
            Options o = new Options();
            List<String> positional = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h", "--help" -> o.help = true;
                    case "--version" -> o.version = true;
                    case "--summary-only" -> o.summaryOnly = true;
                    case "-o", "--output" -> o.output = Path.of(value(args, ++i, a));
                    case "-d", "--output-dir" -> o.outputDir = Path.of(value(args, ++i, a));
                    case "--previous-version" -> o.previousVersion = value(args, ++i, a);
                    case "--current-version" -> o.currentVersion = value(args, ++i, a);
                    case "--include" -> o.includes.add(value(args, ++i, a));
                    case "--exclude" -> o.excludes.add(value(args, ++i, a));
                    default -> {
                        if (a.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + a);
                        }
                        positional.add(a);
                    }
                }
            }
            if (o.help || o.version) {
                return o;
            }
            if (positional.size() != 2) {
                throw new IllegalArgumentException("Expected <previous.zip> <current.zip>, got " + positional.size() + " argument(s).");
            }
            o.previous = Path.of(positional.get(0));
            o.current = Path.of(positional.get(1));
            return o;
        }

        private static String value(String[] args, int i, String option) {
            if (i >= args.length) {
                throw new IllegalArgumentException(option + " needs a value.");
            }
            return args[i];
        }
    }
}
