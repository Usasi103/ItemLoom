# Legacy material observations

`paper26/src/main/resources/compat-sx/legacy-materials-26.2.tsv` records the observable
conversion result of Paper **26.2 build 123** (`26.2-123-5001879`). The observation
generator is the test-only `LegacyMaterialsProbe`, invoked by
`ilprobe legacy-materials export` in an isolated Paper server. Neither NI nor
SX-Item is installed for this export. No upstream converter implementation is
copied into ItemLoom.

For each of the 463 legacy enum identifiers, the generator calls Paper's public
`UnsafeValues.fromLegacy(MaterialData, true)` for all unsigned byte values 0–255.
It records the resulting modern item material, or `-` when the former ItemLoom
boundary rejected the result (null, air, or non-item). There are 118,528 observed
pairs. This is format/platform mapping data, with its platform origin explicitly
recorded; the resolver/parser is ItemLoom code.

Each TSV row has the numeric identifier, the unprefixed legacy name, and sorted
`start=material` runs. The last run ends at 255. The table occupies 22,383 bytes
as LF UTF-8 text. Modern names retain priority over legacy names, and the original
damage suffix is passed on unchanged. The data table does not interpret item
damage, percentages, scripts or item identity.

`ilprobe legacy-materials verify` compares all pairs against the live Paper
oracle, checks every legacy name and extra syntax boundaries. Run this only in
an isolated server: the oracle itself deliberately triggers Paper's expensive
legacy initialization. Ordinary ItemLoom generation uses immutable lookup data
and never invokes that initialization. `bench` measures the resolver without
loading the oracle; use fresh JVMs for cold measurements.

The table is pinned to the configured Paper target. Review/regenerate it and run
the exhaustive comparison when that target changes. Its normalized checksum is
included in `source-inventory.json`. This provenance record is not a legal
originality or zero-risk certification.
