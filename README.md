# loinc-delta-generator

Compares two LOINC release zips and writes every row that was added, modified or removed, file by
file, into a delta zip.

```bash
java -jar loinc-delta-generator.jar Loinc_2.78.zip Loinc_2.82.zip
```

## Why

Every LOINC release is a full snapshot. Unlike SNOMED CT RF2, LOINC has no Delta release type, and
rows carry no effective time.

LOINC does ship some change files, but they cover terms and Parts only:

- `LoincTable/Loinc.csv` has `VersionLastChanged`, `CHNG_TYPE` and `STATUS`.
- `AccessoryFiles/ChangeSnapshot/*.csv` and `AccessoryFiles/Updates/*.csv` list property changes.

Nothing marks a change in the linguistic variants, answer lists, part links, the component hierarchy
or panels. A translation that was reworded or dropped looks exactly like one that never changed.
Between 2.78 and 2.82:

- 60,858 Dutch rows changed;
- 1,428 Estonian rows disappeared;
- 12,005 supplementary part links were removed.

None of this appears in the change files. This tool finds those changes by comparing the two releases
directly.

LOINC almost never deletes a code. A retired term stays in `Loinc.csv` with `STATUS` set to
`DEPRECATED`, so it shows here as `MODIFIED`. The summary counts these status transitions. A
`REMOVED` row means the row is missing from the current release. That is normal for translations,
links and hierarchy paths, and a red flag for `Loinc.csv`; the summary calls it out.

## Usage

```
java -jar loinc-delta-generator.jar <previous.zip> <current.zip> [options]

  -o, --output <file>         Delta zip to write
                              (default: LoincDelta_<previous>_<current>.zip in the working directory)
      --previous-version <v>  Version label of the previous release (default: from the file name,
                              else the highest VersionLastChanged in LoincTable/Loinc.csv)
      --current-version <v>   Version label of the current release (same default)
      --include <glob>        Only compare CSV paths matching the glob; repeatable
                              (e.g. 'AccessoryFiles/LinguisticVariants/*')
      --exclude <glob>        Skip CSV paths matching the glob; repeatable
      --summary-only          Print the summary and write no zip
  -h, --help
      --version
```

The argument order follows the [SNOMED CT delta generator](https://github.com/IHTSDO/delta-generator-tool):
previous release first, then current. The last line of output is `Rows exported: N`, as in that tool.

Exit codes:

- `0`: success. Two identical releases also give `0`, with an empty delta.
- `1`: failure.
- `2`: bad arguments.

Requirements: Java 21 or newer. Diffing 2.78 against 2.82 in full took 51 s on a laptop, run with
`-Xmx2g` (peak resident memory 880 MB).

## Output

The delta zip mirrors the release layout. It contains only the CSV files that changed, plus two
summary files at the root.

Each delta CSV keeps the current release's columns and adds two in front:

| Column | Value |
|---|---|
| `DELTA_ACTION` | `ADDED`, `MODIFIED` or `REMOVED` |
| `DELTA_CHANGED_COLUMNS` | For `MODIFIED`: the columns that differ, joined with `;` |

The rows come in this order:

1. `ADDED` and `MODIFIED` rows, in the current file's order. They carry the current values.
2. `REMOVED` rows. They carry the previous values, mapped onto the current columns by name.

If a file exists in only one release, every row in it is `ADDED` (a new language, for example) or
`REMOVED`.

The summary files are:

- `delta-summary.json`: for each file, the counts, the key columns, columns added or removed, how
  often each column changed, and status transitions.
- `delta-summary.md`: the same, for people. It is also printed to stdout.

### How rows are matched

Each file is keyed by the columns that identify a row (below). That way a changed row shows as one
`MODIFIED` row, not as a `REMOVED` row plus an `ADDED` one. Each key was checked against real
releases.

Rows are compared on the columns both headers share, matched by name. A column that only one release
has is reported in the summary; it does not, by itself, make every row `MODIFIED`.

| File | Key |
|---|---|
| `Loinc.csv`, `LoincTableCore.csv`, `*LinguisticVariant.csv`, `ImagingDocumentCodes.csv`, `LoincUniversalLabOrdersValueSet.csv` | `LOINC_NUM` |
| `MapTo.csv` | `LOINC`, `MAP_TO` |
| `Part.csv` | `PartNumber` |
| `LoincPartLink_Primary.csv`, `LoincPartLink_Supplementary.csv` | `LoincNumber`, `PartNumber`, `LinkTypeName`, `Property` |
| `PartRelatedCodeMapping.csv` | `PartNumber`, `ExtCodeSystem`, `ExtCodeId` |
| `AnswerList.csv` | `AnswerListId`, `AnswerStringId` |
| `LoincAnswerListLink.csv` | `LoincNumber`, `AnswerListId`, `AnswerListLinkType`, `ApplicableContext` |
| `ComponentHierarchyBySystem.csv` | `PATH_TO_ROOT`, `CODE` |
| `PanelsAndForms.csv` | `ParentLoinc`, `ID` |
| `ConsumerName.csv` | `LoincNumber` |
| `DocumentOntology.csv` | `LoincNumber`, `PartNumber`, `PartTypeName` |
| `Group.csv` / `GroupAttributes.csv` / `GroupLoincTerms.csv` | `GroupId` / `GroupId`, `Type` / `GroupId`, `LoincNumber` |
| `ParentGroup.csv` / `ParentGroupAttributes.csv` | `ParentGroupId` / `ParentGroupId`, `Type` |
| `LinguisticVariants.csv`, `SourceOrganization.csv` | `ID` |
| `LoincIeeeMedicalDeviceCodeMappingTable.csv` | `LOINC_NUM`, `IEEE_REFID` |
| `LoincRsnaRadiologyPlaybook.csv` | `LoincNumber`, `PartNumber`, `PartTypeName`, `PartSequenceOrder`, `RID`, `RPID` |
| anything else | the whole row: changes show as `REMOVED` + `ADDED` |

A few files repeat a key. `ComponentHierarchyBySystem.csv` even holds identical rows. Repeated keys
are numbered by occurrence, so those rows are still matched one to one.

## Build

```bash
./gradlew build
```

The jar lands in `build/libs/`. It has no runtime dependencies.

A jar is not a native executable, so the shell cannot run it directly (`exec format error`). Start it
with `java -jar`, and give both release zips with their `.zip` extension:

```bash
java -Xmx2g -jar build/libs/loinc-delta-generator-0.1.0-SNAPSHOT.jar ~/Downloads/LOINC/Loinc_2.78.zip ~/Downloads/LOINC/Loinc_2.83.zip
```

The delta is written to `LoincDelta_2.78_2.83.zip` in the working directory; pass `-o <file>` to put
it elsewhere.

To release, push a tag `v<version>`. CI then does two things:

- attaches `loinc-delta-generator-<version>.jar` and its `.sha256` to a GitHub release;
- publishes `org.helex.loinc:loinc-delta-generator:<version>` to GitHub Packages.

To publish an existing tag that predates one of those steps, run the Release workflow by hand with
that version.

### From GitHub Packages

Reading from GitHub Packages needs a token with `read:packages`, even for a public repository:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/helex-solutions/loinc-delta-generator")
        credentials {
            username = providers.environmentVariable("GITHUB_ACTOR").orNull
            password = providers.environmentVariable("GITHUB_TOKEN").orNull
        }
    }
}

dependencies {
    implementation("org.helex.loinc:loinc-delta-generator:0.1.0")
}
```

## LOINC content

This repository contains no LOINC content. The tests use invented rows in LOINC's file layout. To
run the tool on real releases, download them from [loinc.org](https://loinc.org/downloads/) under the
[LOINC licence](https://loinc.org/license/). A delta made from LOINC files is itself LOINC content
and falls under the same licence.

## Licence

[Apache License 2.0](LICENSE)
