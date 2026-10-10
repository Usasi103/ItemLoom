# Building ItemLoom

[简体中文介绍](README.md) · [English introduction](README.en.md)

## Requirements

- A JDK 25 installation, with `JAVA_HOME` pointing to it.
- Network access to resolve Gradle and the declared Maven dependencies on the first build.
- Python 3 when running the release preparation tool.

Use the checked-in Gradle wrapper. The Paper module targets the Mojang mappings in
`26.2.build.123-stable`; changing that target requires a corresponding runtime check.
The core and compatibility modules emit Java 21 bytecode, while the Paper module emits
Java 25 bytecode. The complete plugin therefore requires Java 25.

## Build

From the repository root, on Windows:

```powershell
.\gradlew.bat build :paper26:probeJar
```

On Linux or macOS:

```sh
bash ./gradlew build :paper26:probeJar
```

Build output defaults to `~/.gradle-builds/ItemLoom/`, with a directory for each module.
The installable plugin is `paper26/libs/ItemLoom-<version>.jar`. The separately generated
`ItemLoomProbe.jar` is a development test plugin, not a release attachment.

Override the output root when needed:

```text
gradlew build :paper26:probeJar -PbuildRoot=/absolute/path/to/itemloom-build
```

Use the appropriate wrapper command for your platform. Keep build output and test servers
outside a deployment server's directory.

## Dependencies and reproducibility

The build resolves Paper, PlaceholderAPI, ItemBridge 1.0.32, Nashorn, ASM, and test dependencies from the
repositories declared in Gradle. The required Keystone source subset is included in
`vendor/keystone`; a private repository or prebuilt local Keystone JAR is not required.
NI and SX-Item are not runtime dependencies. See [NOTICE](NOTICE.md) for origins and licenses.

ItemBridge's input JAR is pinned by SHA-256. The build removes its NI/SX provider classes and
compiles the attributed discovery customization in `vendor/itembridge`. Its other providers
and core remain from the MIT-licensed artifact. Review the input hash, provider inventory and
discovery API together before updating this dependency; the artifact audit rejects removed
adapters and direct NI/SX type references.

The production Java review inventory is under `provenance/`. After reviewing a
source change, update its decision and LF-normalized hash. It also covers main
resources, legal texts, maintained build inputs and release tools. Run
`python -B tools/verify_source_review.py`; release preparation requires this check.
It verifies review coverage and hashes, not authorship or legal originality.

`provenance/runtime-dependencies.json` pins the seven resolved included runtime
artifacts by SHA-256 and records their official source URLs. `shadowJar` verifies
that exact set before packaging. Version-matched complete legal texts from
`licenses/` are included in the JAR and checked by content hash. ItemBridge has
its separate original-input check. Review source and license changes together
when updating dependencies. Sparrow's embedded SnakeYAML fork has pinned matching
[source and a reproducible class comparison](provenance/snakeyaml-fork.md). Run
`tools/verify_fork_source.py` as documented there when verifying this origin or
changing the fork/packaging inputs; stock Engine 3.1 is not interchangeable.

The legacy material table is generated from the target Paper API. See
[its observation record](provenance/legacy-materials.md) for regeneration and
exhaustive checking. The oracle/export probe may stall for Paper initialization;
the normal generation path must not call it.

Do not add server plugin binaries or paid integration JARs to the repository. Optional
runtime integrations must be installed separately in a test server that you control.

## Tests and runtime checks

`build` runs the ordinary unit tests. Some reference-comparison tests require explicit
external inputs and otherwise skip; review test reports rather than counting skips as success.
The optional inputs are:

```text
-PniReferenceJar=/absolute/path/to/compatible-reference.jar
-PniConfigRoot=/absolute/path/to/test-configuration
```

These inputs are used for development comparisons only. Do not distribute private configurations
or imply that one fixture set proves every NI/SX extension compatible.

For Paper/NMS checks, install the built plugin and `ItemLoomProbe.jar` in a separate Paper 26.2
test server. Use the probe commands and fixtures under `paper26/src/probe/`. Install optional
plugins only for the integration scenarios being exercised. Check the server's successful
startup, individual JSON assertions, errors, and normal shutdown; the process exit code alone
does not establish that all scenarios passed.

For the 2.5.0 replacements, run `ilprobe source-replacement` with the same new probe
JAR against 2.4.0 and the candidate in separate, sequential Paper 26.2 instances.
Compare the `observations` objects in `plugins/ItemLoomProbe/source-replacement.json`,
including value types and expected exception classes. Top-level string values
are recorded as UTF-16 code units so isolated surrogate results remain exact.
Its `passed` flag reports
that observation groups completed; it does not establish equivalence without the
cross-version comparison. Inspect normal fixtures as well: two matching errors
are not proof that a valid fixture worked. Durability, state carry, NBT/item
ordering, text nodes, SX locks and item fields are covered; arbitrary scripts and
all configurations are not. Use the action/runtime/editor and other relevant
probes alongside the unit tests for execution paths outside this observation set.

Synthetic players and events do not test actual client rendering, prediction, or network load.
Record real-client and stress testing separately. Do not load the probe on a live server.

## Prepare a release

After testing the exact JAR to be distributed:

```text
python -B tools/prepare_release.py --jar /absolute/path/to/ItemLoom-<version>.jar --output /absolute/path/to/new-release-directory
```

The output directory must be new. The tool checks the source tree and JAR, then prepares
only the plugin and `SHA256SUMS.txt`. Keep validation logs outside that directory.

Commit the final source and cumulative changelog, create a matching version tag, and publish
a formal release. Download its assets and verify the hashes against `SHA256SUMS.txt`; also
verify the tag and source archive. Do not overwrite an existing version with different bytes.
