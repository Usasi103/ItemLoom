# Included dependency origins

Exact resolved coordinates, binary hashes and source artifact URLs are recorded in
`runtime-dependencies.json` beside this file. ItemLoom's source repository also
contains that manifest under `provenance/`.

- Nashorn 15.4: OpenJDK tag `release-15.4`, commit
  `ab2542ea0f3decad033991916167dbce4a46f314`. Its GPL-2.0-with-Classpath-exception
  LICENSE, additional license information, assembly exception, Joni and
  double-conversion notices are supplied separately. Its namespace is preserved.
- ASM 7.3.1: the five included modules are asm, asm-commons, asm-tree,
  asm-analysis and asm-util. The complete BSD-3-Clause copyright/license text is
  reproduced from the exact-version source headers. Packages are relocated into
  `dev.itemloom.internal.asm`.
- Sparrow YAML 1.0.22: official commit
  `e325884f1e92dc4a86b35cdbc90bf0183458a7a6`, GPL-3.0. Its 109 published Java
  source files match that commit. Packages are relocated into
  `dev.itemloom.internal.keystone.libs.sparrow.yaml`.
- Sparrow's `ExtendedConstructor` includes Apache-2.0 code by
  `https://dejvokep.dev/`, copyright 2024. Its copyright notice and complete Apache
  license are included.
- Sparrow embeds SnakeYAML Engine `3.1-SNAPSHOT-forked`, identified as Apache-2.0
  by its embedded POM and manifest. The exact upstream-provided fork binary has
  SHA-256 `cdfe1aa1a3f872a322f379e396cf649a8d756f2d39599607d1fda408487ac06d`
  and is present in Sparrow's official source tree under `libs/`. Its matching
  source is Catnies/snakeyaml-engine commit
  `30e9499fcd6e1569b81518db13e12d900c5da2eb`. A JDK 25 rebuild matches all
  223 runtime classes after removing debug metadata; the packaged classes match
  after reversing relocation. The dependency manifest pins the source archive
  and records the verification boundary. The source has no separate upstream
  NOTICE. `snakeyaml-fork-NOTICE.txt` records the SnakeYAML/Google source headers
  and Catnies modification origin; the complete Apache-2.0 license is included.

ItemBridge and Keystone have separate notices in this directory. These records
document included dependencies and packaging changes; they are not an authorship
certificate or a guarantee of zero copyright risk.
