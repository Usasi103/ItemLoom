# Embedded SnakeYAML source correspondence

The 2.4.0 audit locates the source for the fork embedded by Sparrow YAML 1.0.22:
[Catnies/snakeyaml-engine, commit 30e9499fcd6e1569b81518db13e12d900c5da2eb](https://github.com/Catnies/snakeyaml-engine/tree/30e9499fcd6e1569b81518db13e12d900c5da2eb).
The pinned source archive and binary URLs/hashes are in
[runtime-dependencies.json](runtime-dependencies.json). The earlier “source not
located” finding is resolved for this exact dependency; it is not an assertion
about all upstream history or legal ownership.

## Correspondence checks

Sparrow's [binary import](https://github.com/Xiao-MoMi/sparrow-yaml/commit/5d0a6062dcbf79cd031c7619d7ce3a541a167010)
was authored by Catnies after the fork's final commit. The source POM is identical
to the binary's embedded POM after line-ending normalization. The source adds
raw scalar text and comment-preserving behavior required by Sparrow. Stock
SnakeYAML Engine 3.1 lacks those APIs and is not substituted.

The source archive contains 136 main Java files. Compilation with JDK 25,
`-source 11 -target 11 -g -encoding UTF-8`, produces the same 224 class names as
the original binary. ItemLoom excludes `module-info.class` when shading; Maven's
generated module package/version metadata is outside the comparison of runtime
classes. The fork binary has no non-class runtime data resources.

| Check | Result |
|---|---|
| Runtime classes with byte-identical rebuilds | 210 of 223 |
| Remaining runtime classes | 13; compare equal after removing source/line/local-variable debug information and reserializing with ASM 7.3.1 |
| Rebuilt runtime class structure versus original fork | 223 of 223 equal |
| Runtime class structure in final plugin, after reversing relocation | 223 of 223 equal |
| Class sets | No missing or additional runtime classes |

The comparison retains executable instructions, constants, fields, methods,
annotations, access flags, descriptors, reflection-visible method parameters
and stack frames. It is stronger than
matching filenames or public signatures, but it is not whole-JAR byte identity:
debug metadata, constant-pool serialization, module packaging and ZIP/manifest
metadata differ. The current plugin continues using the same pinned Sparrow
binary; this audit does not replace its YAML implementation or change parsing.

## Reproduce

Download the source archive, original fork JAR, ASM 7.3.1 and ASM Commons 7.3.1
from the exact URLs in the dependency manifest. With JDK 25 and a built plugin:

```text
python -B tools/verify_fork_source.py --source /inputs/fork-source.zip --fork-jar /inputs/fork.jar --plugin /build/ItemLoom-2.4.0.jar --asm /inputs/asm-7.3.1.jar --asm-commons /inputs/asm-commons-7.3.1.jar --java-home /jdk25 --work /external/fork-verification
```

The tool checks input hashes before compiling, uses a fresh temporary directory,
compares class sets and normalized class contents, and writes a report outside
the repository. It refuses altered source archives, binaries or ASM inputs.
It also rejects duplicate entries, multi-release fork classes and packaged
module descriptors. A failed rerun invalidates any earlier successful report.
Different compiler updates can require investigating a comparison difference;
do not loosen the comparison merely to obtain a passing result.

## Attribution and remaining limits

The source is Apache-2.0. Its main headers credit SnakeYAML (2018); three bundled
Google GData escaping sources credit Google Inc. (2008). The fork changes are
attributed to Catnies. The complete source archive has no separate upstream
NOTICE. ItemLoom packages its own clearly identified source-header/origin notice
alongside the complete Apache-2.0 license; it does not label that notice as an
upstream-authored file.

NI/SX source exposure and project history remain disclosed. Tests, matching
bytecode, source availability and attribution cannot certify zero copyright
risk or legal clean-room authorship. Existing GPL licensing and historical
Releases remain intact.
