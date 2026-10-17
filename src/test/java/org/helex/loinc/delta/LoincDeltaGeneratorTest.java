package org.helex.loinc.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end runs on small synthetic release zips. The fixtures are invented rows in LOINC's file
 * layout; the repository holds no LOINC content.
 */
class LoincDeltaGeneratorTest {

    private static final String LOINC = "LoincTable/Loinc.csv";
    private static final String DUTCH = "AccessoryFiles/LinguisticVariants/nlNL22LinguisticVariant.csv";
    private static final String HIERARCHY = "AccessoryFiles/ComponentHierarchyBySystem/ComponentHierarchyBySystem.csv";

    @TempDir
    Path dir;

    @Test
    void reportsAddedModifiedAndRemovedTermsWithChangedColumns() throws IOException {
        Path previous = zip("Loinc_9.1.zip", Map.of(LOINC, csv(
                row("LOINC_NUM", "COMPONENT", "STATUS", "LONG_COMMON_NAME"),
                row("1-1", "Alpha", "ACTIVE", "Alpha in Blood"),
                row("2-2", "Beta", "ACTIVE", "Beta in Urine"),
                row("3-3", "Gamma", "ACTIVE", "Gamma in Serum"))));
        Path current = zip("Loinc_9.2.zip", Map.of(LOINC, csv(
                row("LOINC_NUM", "COMPONENT", "STATUS", "LONG_COMMON_NAME"),
                row("1-1", "Alpha", "ACTIVE", "Alpha in Blood"),
                row("2-2", "Beta", "DEPRECATED", "Beta [Mass] in Urine"),
                row("4-4", "Delta", "ACTIVE", "Delta in Plasma"))));

        Run run = run(previous, current);

        assertEquals(0, run.exit);
        assertEquals(Path.of("LoincDelta_9.1_9.2.zip").getFileName(), run.output.getFileName());
        List<String[]> rows = run.entry(LOINC);
        assertEquals(List.of("DELTA_ACTION", "DELTA_CHANGED_COLUMNS", "LOINC_NUM", "COMPONENT", "STATUS", "LONG_COMMON_NAME"),
                List.of(rows.get(0)));
        assertRow(rows.get(1), "MODIFIED", "STATUS;LONG_COMMON_NAME", "2-2", "Beta", "DEPRECATED", "Beta [Mass] in Urine");
        assertRow(rows.get(2), "ADDED", "", "4-4", "Delta", "ACTIVE", "Delta in Plasma");
        assertRow(rows.get(3), "REMOVED", "", "3-3", "Gamma", "ACTIVE", "Gamma in Serum");
        assertEquals(4, rows.size());

        String json = run.text(LoincDeltaGenerator.SUMMARY_JSON);
        assertTrue(json.contains("\"statusTransitions\": {\"ACTIVE -> DEPRECATED\": 1}"), json);
        assertTrue(json.contains("\"totals\": {\"added\": 1, \"modified\": 1, \"removed\": 1"), json);
        assertTrue(run.text(LoincDeltaGenerator.SUMMARY_MD).contains("1 LOINC code(s) are missing"));
        assertTrue(run.stdout.endsWith("Rows exported: 3" + System.lineSeparator()), run.stdout);
    }

    @Test
    void catchesTranslationChangesAndWholeLanguageFiles() throws IOException {
        String german = "AccessoryFiles/LinguisticVariants/deDE15LinguisticVariant.csv";
        String estonian = "AccessoryFiles/LinguisticVariants/etEE10LinguisticVariant.csv";
        String[] header = row("LOINC_NUM", "COMPONENT", "LONG_COMMON_NAME");
        Path previous = zip("Loinc_9.1.zip", Map.of(
                DUTCH, csv(header, row("1-1", "alfa", "Alfa [massa] in bloed"), row("2-2", "beta", "Beta in serum")),
                german, csv(header, row("1-1", "Alfa", "Alfa im Blut"))));
        Path current = zip("Loinc_9.2.zip", Map.of(
                DUTCH, csv(header, row("1-1", "alfa", "alfa in bloed (concentratie)"), row("2-2", "beta", "Beta in serum")),
                estonian, csv(header, row("1-1", "alfa", "Alfa veres"))));

        Run run = run(previous, current);

        List<String[]> dutch = run.entry(DUTCH);
        assertEquals(2, dutch.size());
        assertRow(dutch.get(1), "MODIFIED", "LONG_COMMON_NAME", "1-1", "alfa", "alfa in bloed (concentratie)");
        assertRow(run.entry(estonian).get(1), "ADDED", "", "1-1", "alfa", "Alfa veres");
        assertRow(run.entry(german).get(1), "REMOVED", "", "1-1", "Alfa", "Alfa im Blut");
        String json = run.text(LoincDeltaGenerator.SUMMARY_JSON);
        assertTrue(json.contains("\"state\": \"FILE_ADDED\""), json);
        assertTrue(json.contains("\"state\": \"FILE_REMOVED\""), json);
    }

    @Test
    void identicalReleasesGiveAnEmptyDeltaNotAFailure() throws IOException {
        Map<String, String> files = Map.of(LOINC, csv(row("LOINC_NUM", "COMPONENT"), row("1-1", "Alpha")));
        Run run = run(zip("Loinc_9.1.zip", files), zip("Loinc_9.2.zip", files));

        assertEquals(0, run.exit);
        assertFalse(run.names().contains(LOINC));
        assertTrue(run.names().contains(LoincDeltaGenerator.SUMMARY_JSON));
        assertTrue(run.stdout.contains("Rows exported: 0"));
    }

    @Test
    void repeatedKeysAndIdenticalRowsAreMatchedOneToOne() throws IOException {
        String[] header = row("PATH_TO_ROOT", "SEQUENCE", "IMMEDIATE_PARENT", "CODE", "CODE_TEXT");
        Path previous = zip("Loinc_9.1.zip", Map.of(HIERARCHY, csv(header,
                row("LP1.LP2", "1", "LP2", "LP9", "Nine"),
                row("LP1.LP2", "1", "LP2", "LP9", "Nine"),
                row("LP1.LP2", "2", "LP2", "LP8", "Eight"))));
        Path current = zip("Loinc_9.2.zip", Map.of(HIERARCHY, csv(header,
                row("LP1.LP2", "1", "LP2", "LP9", "Nine"),
                row("LP1.LP2", "1", "LP2", "LP9", "Nine"),
                row("LP1.LP2", "1", "LP2", "LP9", "Nine"),
                row("LP1.LP2", "3", "LP2", "LP8", "Eight"))));

        List<String[]> rows = run(previous, current).entry(HIERARCHY);

        assertEquals(3, rows.size());
        assertRow(rows.get(1), "ADDED", "", "LP1.LP2", "1", "LP2", "LP9", "Nine");
        assertRow(rows.get(2), "MODIFIED", "SEQUENCE", "LP1.LP2", "3", "LP2", "LP8", "Eight");
    }

    @Test
    void aNewColumnDoesNotMarkEveryRowModified() throws IOException {
        Path previous = zip("Loinc_9.1.zip", Map.of(LOINC, csv(
                row("LOINC_NUM", "COMPONENT", "OLD_FIELD"), row("1-1", "Alpha", "x"), row("2-2", "Beta", "y"))));
        Path current = zip("Loinc_9.2.zip", Map.of(LOINC, csv(
                row("LOINC_NUM", "NEW_FIELD", "COMPONENT"), row("1-1", "n", "Alpha"), row("2-2", "n", "Beta2"))));

        Run run = run(previous, current);

        List<String[]> rows = run.entry(LOINC);
        assertEquals(2, rows.size());
        assertRow(rows.get(1), "MODIFIED", "COMPONENT", "2-2", "n", "Beta2");
        String json = run.text(LoincDeltaGenerator.SUMMARY_JSON);
        assertTrue(json.contains("\"columnsAdded\": [\"NEW_FIELD\"], \"columnsRemoved\": [\"OLD_FIELD\"]"), json);
    }

    @Test
    void wrappingFolderIsIgnoredAndQuotedFieldsSurvive() throws IOException {
        String tricky = "Line one, with comma\r\nline two with \"quotes\"";
        Path previous = zip("Loinc_9.1.zip", Map.of("Loinc_9.1/" + LOINC,
                "﻿" + csv(row("LOINC_NUM", "COMPONENT"), row("1-1", "plain"))));
        Path current = zip("Loinc_9.2.zip", Map.of(LOINC, csv(row("LOINC_NUM", "COMPONENT"), row("1-1", tricky))));

        List<String[]> rows = run(previous, current).entry(LOINC);

        assertEquals(2, rows.size());
        assertRow(rows.get(1), "MODIFIED", "COMPONENT", "1-1", tricky);
    }

    @Test
    void includeAndExcludeFilterThePaths() throws IOException {
        String[] header = row("LOINC_NUM", "COMPONENT");
        Path previous = zip("Loinc_9.1.zip", Map.of(LOINC, csv(header, row("1-1", "a")), DUTCH, csv(header, row("1-1", "a"))));
        Path current = zip("Loinc_9.2.zip", Map.of(LOINC, csv(header, row("1-1", "b")), DUTCH, csv(header, row("1-1", "b"))));

        Run included = run(previous, current, "--include", "AccessoryFiles/LinguisticVariants/*", "-o", dir.resolve("a.zip").toString());
        assertTrue(included.names().contains(DUTCH));
        assertFalse(included.names().contains(LOINC));

        Run excluded = run(previous, current, "--exclude", "AccessoryFiles/**", "-o", dir.resolve("b.zip").toString());
        assertFalse(excluded.names().contains(DUTCH));
        assertTrue(excluded.names().contains(LOINC));
    }

    @Test
    void versionsComeFromLoincCsvWhenTheFileNameHasNone() throws IOException {
        String[] header = row("LOINC_NUM", "VersionLastChanged");
        Path previous = zip("old.zip", Map.of(LOINC, csv(header, row("1-1", "2.9"), row("2-2", "2.10"))));
        Path current = zip("new.zip", Map.of(LOINC, csv(header, row("1-1", "2.9"), row("2-2", "2.11"))));

        Run run = run(previous, current, "--current-version", "custom");

        assertTrue(run.stdout.contains("(version 2.10)"), run.stdout);
        assertEquals("LoincDelta_2.10_custom.zip", run.output.getFileName().toString());
    }

    @Test
    void outputDirHoldsTheDefaultNameAndRelativeOutputNames() throws IOException {
        String[] header = row("LOINC_NUM", "COMPONENT");
        Path previous = zip("Loinc_9.1.zip", Map.of(LOINC, csv(header, row("1-1", "a"))));
        Path current = zip("Loinc_9.2.zip", Map.of(LOINC, csv(header, row("1-1", "b"))));
        Path outDir = dir.resolve("deltas/nested");

        assertEquals(0, LoincDeltaGenerator.run(new String[] {previous.toString(), current.toString(), "-d", outDir.toString()},
                new PrintStream(OutputStream.nullOutputStream()), System.err));
        assertTrue(Files.isRegularFile(outDir.resolve("LoincDelta_9.1_9.2.zip")));

        assertEquals(0, LoincDeltaGenerator.run(
                new String[] {previous.toString(), current.toString(), "--output-dir", outDir.toString(), "-o", "custom.zip"},
                new PrintStream(OutputStream.nullOutputStream()), System.err));
        assertTrue(Files.isRegularFile(outDir.resolve("custom.zip")));
    }

    @Test
    void outputNamingAnExistingFolderPutsTheDefaultNameInside() throws IOException {
        String[] header = row("LOINC_NUM", "COMPONENT");
        Path previous = zip("Loinc_9.1.zip", Map.of(LOINC, csv(header, row("1-1", "a"))));
        Path current = zip("Loinc_9.2.zip", Map.of(LOINC, csv(header, row("1-1", "b"))));
        Path folder = Files.createDirectories(dir.resolve("existing"));

        assertEquals(0, LoincDeltaGenerator.run(new String[] {previous.toString(), current.toString(), "-o", folder.toString()},
                new PrintStream(OutputStream.nullOutputStream()), System.err));
        assertTrue(Files.isRegularFile(folder.resolve("LoincDelta_9.1_9.2.zip")));
    }

    @Test
    void fileErrorsSayWhatWentWrong() {
        assertEquals("/x/y: no such file or folder", LoincDeltaGenerator.describe(new java.nio.file.NoSuchFileException("/x/y")));
        assertEquals("/x/y: is a folder that is not empty",
                LoincDeltaGenerator.describe(new java.nio.file.DirectoryNotEmptyException("/x/y")));
    }

    @Test
    void badArgumentsExitWithTwo() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = LoincDeltaGenerator.run(new String[] {"only-one.zip"}, new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(2, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Run run(Path previous, Path current, String... extra) throws IOException {
        List<String> args = new ArrayList<>(List.of(previous.toString(), current.toString()));
        args.addAll(List.of(extra));
        Path output = null;
        if (!args.contains("-o")) {
            String name = null;
            // Mirror the default name, but inside the temp dir.
            try (LoincRelease p = LoincRelease.open(previous, valueOf(extra, "--previous-version"));
                 LoincRelease c = LoincRelease.open(current, valueOf(extra, "--current-version"))) {
                name = LoincDeltaGenerator.defaultOutputName(p.version(), c.version());
            }
            output = dir.resolve(name);
            args.addAll(List.of("-o", output.toString()));
        } else {
            output = Path.of(args.get(args.indexOf("-o") + 1));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = LoincDeltaGenerator.run(args.toArray(String[]::new),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));
        return new Run(exit, output, out.toString(StandardCharsets.UTF_8));
    }

    private static String valueOf(String[] args, String option) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(option)) {
                return args[i + 1];
            }
        }
        return null;
    }

    private Path zip(String name, Map<String, String> files) throws IOException {
        Path zip = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> f : new LinkedHashMap<>(files).entrySet()) {
                out.putNextEntry(new ZipEntry(f.getKey()));
                out.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    private static String[] row(String... fields) {
        return fields;
    }

    private static String csv(String[]... rows) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CsvWriter writer = new CsvWriter(bytes);
        for (String[] r : rows) {
            writer.write(r);
        }
        writer.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void assertRow(String[] actual, String... expected) {
        assertEquals(List.of(expected), List.of(actual));
    }

    private record Run(int exit, Path output, String stdout) {

        List<String> names() throws IOException {
            try (ZipFile zip = new ZipFile(output.toFile())) {
                return zip.stream().map(ZipEntry::getName).toList();
            }
        }

        String text(String entry) throws IOException {
            try (ZipFile zip = new ZipFile(output.toFile())) {
                ZipEntry e = zip.getEntry(entry);
                assertTrue(e != null, "missing entry " + entry);
                return new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        List<String[]> entry(String entry) throws IOException {
            List<String[]> rows = new ArrayList<>();
            try (CsvReader reader = new CsvReader(new ByteArrayInputStream(text(entry).getBytes(StandardCharsets.UTF_8)))) {
                for (String[] r = reader.next(); r != null; r = reader.next()) {
                    rows.add(r);
                }
            }
            return rows;
        }
    }
}
