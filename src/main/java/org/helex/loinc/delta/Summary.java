package org.helex.loinc.delta;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Renders the run's results as {@code delta-summary.json} and {@code delta-summary.md}. */
final class Summary {

    private final LoincRelease previous;
    private final LoincRelease current;
    private final List<FileResult> files;
    private final String toolVersion;
    private final Instant generated;

    Summary(LoincRelease previous, LoincRelease current, List<FileResult> files, String toolVersion, Instant generated) {
        this.previous = previous;
        this.current = current;
        this.files = files;
        this.toolVersion = toolVersion;
        this.generated = generated;
    }

    long rowsExported() {
        return files.stream().mapToLong(FileResult::rowsExported).sum();
    }

    String json() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"tool\": \"loinc-delta-generator\",\n");
        sb.append("  \"toolVersion\": ").append(str(toolVersion)).append(",\n");
        sb.append("  \"generated\": ").append(str(generated.toString())).append(",\n");
        sb.append("  \"previous\": ").append(release(previous)).append(",\n");
        sb.append("  \"current\": ").append(release(current)).append(",\n");
        sb.append("  \"totals\": {")
                .append("\"added\": ").append(sum(r -> r.added))
                .append(", \"modified\": ").append(sum(r -> r.modified))
                .append(", \"removed\": ").append(sum(r -> r.removed))
                .append(", \"filesChanged\": ").append(count(FileResult.State.CHANGED))
                .append(", \"filesAdded\": ").append(count(FileResult.State.FILE_ADDED))
                .append(", \"filesRemoved\": ").append(count(FileResult.State.FILE_REMOVED))
                .append(", \"filesUnchanged\": ").append(count(FileResult.State.UNCHANGED))
                .append("},\n");
        sb.append("  \"files\": [");
        for (int i = 0; i < files.size(); i++) {
            FileResult r = files.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\"path\": ").append(str(r.path))
                    .append(", \"state\": ").append(str(r.state.name()))
                    .append(", \"keyColumns\": ").append(strings(r.keyColumns))
                    .append(", \"keyFallback\": ").append(r.keyFallback)
                    .append(", \"added\": ").append(r.added)
                    .append(", \"modified\": ").append(r.modified)
                    .append(", \"removed\": ").append(r.removed)
                    .append(", \"unchanged\": ").append(r.unchanged)
                    .append(", \"columnsAdded\": ").append(strings(r.columnsAdded))
                    .append(", \"columnsRemoved\": ").append(strings(r.columnsRemoved))
                    .append(", \"duplicateKeysPrevious\": ").append(r.duplicateKeysPrevious)
                    .append(", \"duplicateKeysCurrent\": ").append(r.duplicateKeysCurrent)
                    .append(", \"changedColumns\": ").append(counts(r.changedColumns))
                    .append(", \"statusTransitions\": ").append(counts(r.statusTransitions))
                    .append('}');
        }
        sb.append(files.isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# LOINC delta ").append(label(previous)).append(" → ").append(label(current)).append("\n\n");
        sb.append("- Previous: `").append(previous.path().getFileName()).append("`\n");
        sb.append("- Current: `").append(current.path().getFileName()).append("`\n");
        sb.append("- Generated: ").append(generated).append(" by loinc-delta-generator ").append(toolVersion).append("\n");
        sb.append("- Rows: ").append(sum(r -> r.added)).append(" added, ")
                .append(sum(r -> r.modified)).append(" modified, ")
                .append(sum(r -> r.removed)).append(" removed\n\n");

        sb.append("| File | State | Added | Modified | Removed | Unchanged | Most changed columns |\n");
        sb.append("|---|---|---:|---:|---:|---:|---|\n");
        for (FileResult r : files) {
            if (r.state == FileResult.State.UNCHANGED && r.columnsAdded.isEmpty() && r.columnsRemoved.isEmpty()) {
                continue;
            }
            sb.append("| `").append(r.path).append("` | ").append(r.state).append(" | ")
                    .append(r.added).append(" | ").append(r.modified).append(" | ").append(r.removed).append(" | ")
                    .append(r.unchanged).append(" | ").append(topColumns(r.changedColumns)).append(" |\n");
        }
        long unchanged = count(FileResult.State.UNCHANGED);
        if (unchanged > 0) {
            sb.append("\n").append(unchanged).append(" other file(s) are unchanged.\n");
        }

        StringBuilder notes = new StringBuilder();
        for (FileResult r : files) {
            if (!r.columnsAdded.isEmpty() || !r.columnsRemoved.isEmpty()) {
                notes.append("- `").append(r.path).append("`: columns added ").append(r.columnsAdded)
                        .append(", removed ").append(r.columnsRemoved).append(" (rows compared on the shared columns).\n");
            }
            if (r.keyFallback) {
                notes.append("- `").append(r.path).append("`: a key column is missing from one header, so whole rows")
                        .append(" were compared (changes show as REMOVED + ADDED).\n");
            }
            if (!r.statusTransitions.isEmpty()) {
                notes.append("- `").append(r.path).append("` status changes: ").append(r.statusTransitions).append("\n");
            }
            if (r.removed > 0 && r.path.endsWith("LoincTable/Loinc.csv")) {
                notes.append("- `").append(r.path).append("`: ").append(r.removed)
                        .append(" LOINC code(s) are missing from the current release. LOINC normally deprecates")
                        .append(" codes instead of dropping them; check these REMOVED rows.\n");
            }
        }
        if (!notes.isEmpty()) {
            sb.append("\n## Notes\n\n").append(notes);
        }
        return sb.toString();
    }

    private static String topColumns(Map<String, Long> changed) {
        return changed.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(4)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String label(LoincRelease release) {
        return release.version() == null ? release.path().getFileName().toString() : release.version();
    }

    private long sum(java.util.function.ToLongFunction<FileResult> f) {
        return files.stream().mapToLong(f).sum();
    }

    private long count(FileResult.State state) {
        return files.stream().filter(r -> r.state == state).count();
    }

    private static String release(LoincRelease release) {
        return "{\"file\": " + str(release.path().getFileName().toString())
                + ", \"version\": " + (release.version() == null ? "null" : str(release.version())) + "}";
    }

    private static String strings(Collection<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (String v : values) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(str(v));
        }
        return sb.append(']').toString();
    }

    private static String counts(Map<String, Long> values) {
        StringBuilder sb = new StringBuilder("{");
        values.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(str(k)).append(": ").append(v);
        });
        return sb.append('}').toString();
    }

    static String str(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
        return sb.append('"').toString();
    }
}
