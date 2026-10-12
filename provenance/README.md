# Source review

ItemLoom 2.6.0 replaces its own substantive runtime implementations against the behavior of released 2.5.0 (`03f5da3945c22f6ae9aa504abd7b8365bbbea06c`). The preserved contracts are direct NI/SX configuration and supported script input, ItemLoom's public API, existing item data, and established runtime behavior. Configuration files do not require conversion.

## Implementation scope

| Region | Replacement and retained contract |
|---|---|
| Core and service composition | Generation values, budgets, action execution, immutable catalogs and typed runtime publication; public generation descriptors remain |
| NI and SX configuration | Loading, inheritance/imports, expressions, random values, scopes and recipes; original configuration vocabulary and source files remain |
| Item runtime | Generation, editing, saving, maintenance, triggers, source commits, drops and conserved return obligations; existing stored keys and operation order remain |
| Script and NBT compatibility | Supported managers, constructors, helper functions, editors, mutable NBT views and transaction boundaries; aliases identify ItemLoom implementations rather than embedded upstream JVM classes |
| Platform and integrations | Bootstrap, provider ownership, commands, component codecs, packet copies/display proofs and optional plugin boundaries |
| Public API | Eight individually reviewed interface/event/data files retained as minimal public contracts, HandlerList registration and required defensive-copy boundaries; no blanket retention of business implementations |

Implementation workers used extracted behavior contracts, permitted adjacent APIs and project test fixtures. They were instructed not to read displaced target bodies or upstream implementations. The coordinator had source exposure, supplied clarifications and made integration corrections. Some workers had approved access to adjacent platform/dependency bodies, recorded separately from their rewrite targets. This is an engineering workflow, not a legal clean-room certification or a claim that historical source exposure disappeared.

## Reviewed inputs

`source-inventory.json` records each current production Java file, main resource, maintained build/release input and legal text, its retention/replacement reason, and its SHA-256. Text hashes use UTF-8 with LF newlines; binary inputs use their actual bytes. The inventory rejects missing, removed or changed inputs through:

```text
python -B tools/verify_source_review.py
```

A changed file must be reviewed before its decision and hash are updated. Hash coverage checks the recorded boundary, not authorship. Tests and configuration fixtures are retained regression inputs; this inventory does not certify every fixture as independently authored. Private configuration, player data, local evidence and server artwork remain outside the public repository.

The configuration census reads only configuration/script text and does not execute scripts or inspect upstream implementation source. Runtime parity is checked separately with unit tests, Paper probes and paired observations. These checks do not establish compatibility with arbitrary old extensions, every possible configuration or real-client behavior.

## Attributed dependencies

| Input | Retention reason and evidence |
|---|---|
| Keystone source subset | Thirty-seven attributed files provide the configuration, command, task, input and storage operations actually called by ItemLoom; origin and modifications are in `vendor/keystone` |
| ItemBridge | Pinned MIT-licensed artifact provides external item sources. NI/SX providers and original eager discovery are removed; one attributed discovery customization is compiled from `vendor/itembridge` |
| Nashorn and ASM | Supported JavaScript execution; exact binaries and version-matched notices are pinned |
| Sparrow YAML and embedded SnakeYAML fork | Supported YAML behavior and shared configuration input. The exact Catnies fork source/revision is identified and reproducibly compared, rather than substituted with stock Engine 3.1 |
| Legacy material observations | Retained data observed through pinned Paper APIs; the runtime reader is replaced. See [material provenance](legacy-materials.md) |

These dependencies are engineering choices supported by concrete callers and reviewed sources/licenses. No particular third-party implementation is inherently required. Replacing a dependency would also require preserving its observable behavior and accounting for its own source obligations.

[The runtime dependency manifest](runtime-dependencies.json) fixes the seven resolved runtime artifacts; ItemBridge has a separate input hash and filter check. [The SnakeYAML record](snakeyaml-fork.md) documents correspondence of all 223 runtime classes to the located source rebuild and packaged relocation, including debug normalization and the excluded module descriptor. That evidence is reused only while those exact inputs and class bytes remain unchanged; it is not a claim of whole-JAR reproducibility or legal ownership.

The artifact check rejects NI/SX plugin classes, direct JVM type links, excluded ItemBridge providers, obsolete helper resources, duplicate archive entries and unintended private content. It checks actual packaged root/license/modification text against reviewed input, not just resource names. Negative guard checks deliberately corrupt notices and descriptors to verify rejection.

GPL-3.0, upstream attribution, Git history and historical Releases remain. Historical replacements and corrected retention decisions are recorded in [CHANGELOG](../CHANGELOG.md) and [NOTICE](../NOTICE.md). Engineering replacement and source correspondence do not certify historical independent authorship or zero copyright risk.
