package org.helex.loinc.delta;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What the diff found in one CSV file. */
final class FileResult {

    enum State {
        /** In both releases, no row differs. */
        UNCHANGED,
        /** In both releases, some rows differ. */
        CHANGED,
        /** Only in the current release: every row is ADDED. */
        FILE_ADDED,
        /** Only in the previous release: every row is REMOVED. */
        FILE_REMOVED
    }

    final String path;
    State state = State.UNCHANGED;
    /** The columns used to match rows; empty means whole rows were compared. */
    List<String> keyColumns = List.of();
    /** True when the file has known key columns that one of the headers lacks, so whole rows were compared. */
    boolean keyFallback;
    long added;
    long modified;
    long removed;
    long unchanged;
    List<String> columnsAdded = List.of();
    List<String> columnsRemoved = List.of();
    /** Distinct keys that occur more than once in the file. */
    long duplicateKeysPrevious;
    long duplicateKeysCurrent;
    /** For MODIFIED rows: how often each column differed. */
    final Map<String, Long> changedColumns = new TreeMap<>();
    /** For MODIFIED rows of a file with a STATUS column: {@code "ACTIVE -> DEPRECATED"} counts. */
    final Map<String, Long> statusTransitions = new TreeMap<>();

    FileResult(String path) {
        this.path = path;
    }

    long rowsExported() {
        return added + modified + removed;
    }

    boolean hasRows() {
        return rowsExported() > 0;
    }
}
