# Source and dependencies

ItemLoom is distributed under the GNU General Public License, version 3. See [LICENSE](LICENSE).
Its Java runtime, item identity, and public API are independent of the upstream plugins.
Architectural independence does not erase source provenance or make this a clean-room implementation.

## Configuration compatibility sources

- **NeigeItems**, by **Neige** and its contributors: [NeigeItems-Kotlin](https://github.com/ankhorg/NeigeItems-Kotlin).
  Its GPL-3.0 source and configuration behavior informed the NI compatibility frontend and subsequent Java implementation.
  NI configuration and supported legacy script calls are adapted by separate modules. The plugin does not require or embed the NI JAR, Kotlin runtime, or NI framework.
- **SX-Item 4.5.11**, by **Saukiya, Ray_Hughes, Lounode**, and its contributors: [SX-Item](https://github.com/Saukiya/SX-Item),
  source revision `4ea02cacfe8687ab9cee34695fe2b44656d2ff35`.
  Its GPL-3.0 source and behavior informed the SX configuration adapter. The original plugin and its Java API classes are neither required nor embedded.

Attribution and GPL-3.0 licensing are retained. Compatibility does not cover every upstream defect,
third-party extension, command, Java API, or legacy server implementation. Supported behavior and
intentional differences are documented in [USAGE.md](USAGE.md).

Version 2.0.0 included a JavaScript action helper resource derived from NI's `lib.js`.
Version 2.1.0 removes that resource and replaces it with Java helpers and generated binding glue.
The source review also identified formula tokenization, gradient rendering, nested NBT editing,
regular-expression editing, and pack sampling for replacement; these were reimplemented from
behavioral contracts, together with SX branch/time parsing. This records the replacement work, not a claim of legal clean-room
status or an erasure of the project's development history. Required configuration vocabulary,
legacy script aliases, and data-format names remain in the compatibility boundary.

Version 2.2.0 extends the review to every existing production Java file. Additional
contract-based replacements cover NI collection nodes, percent text and escaped
paths, literal editors, inventory requirements, display templates and proofs,
durability/drop/trigger decisions, and the SX math/script boundaries. Retained
adapters and simple format policies have file-level review reasons in
[the source inventory](provenance/README.md). The review and replacements do not
certify historical originality or remove the licensing and attribution above.

Version 2.3.0 extends the review inventory to production resources and maintained
build inputs. Legacy numeric material conversion now uses observations generated
from the pinned Paper API, not NI/SX implementation code; see
[the material data record](provenance/legacy-materials.md). This follow-up also
corrects missing dependency license resources in the 2.2.0 distribution. It does
not establish a new historical independent-authorship claim.

## Included source and runtime libraries

- **ItemBridge 1.0.32**, copyright 2025 **jhqwqmc**: [ItemBridge](https://github.com/jhqwqmc/ItemBridge),
  MIT License, source revision `bdf107863b64dc6d70888e4597a7413d3eea88fb`.
  The public Maven artifact `cn.gtemc:itembridge:1.0.32` is filtered, embedded and relocated into
  `dev.itemloom.internal.itembridge`; its license is packaged at `META-INF/licenses/itembridge-MIT.txt`.
  ItemLoom removes the NI/SX provider bytecode and replaces provider discovery with the explicitly
  attributed customization in `vendor/itembridge`. The remaining provider implementations and
  core come from ItemBridge. Its input hash, source revision and modifications are documented in
  that directory and packaged at `META-INF/licenses/itembridge-modifications.md`. No NI/SX
  provider classes or direct NI/SX JVM type references are allowed in the resulting JAR.

- **Keystone 0.3.6**: the source subset used by ItemLoom is included under `vendor/keystone`, derived from
  source revision `3554cf5502ff4b52532511c396d5ef5c9bd29791`. Its source and license are supplied with this repository.
  The build embeds the required code and relocates `dev.keystone` into ItemLoom's private namespace.
- **Sparrow YAML 1.0.22**, by Xiao-MoMi and its contributors: [sparrow-yaml](https://github.com/Xiao-MoMi/sparrow-yaml).
  This GPL-3.0 configuration library is used by Keystone and relocated with the runtime dependency.
  Its exact source commit is `e325884f1e92dc4a86b35cdbc90bf0183458a7a6`. It includes Apache-2.0
  `ExtendedConstructor` code (copyright 2024 dejvokep) and an Apache-2.0 SnakeYAML Engine
  `3.1-SNAPSHOT-forked` binary. The exact fork binary is identified in
  [the dependency manifest](provenance/runtime-dependencies.json); matching modified source and a
  separate fork NOTICE have not been located. Stock SnakeYAML sources are not asserted equivalent.
- **OpenJDK Nashorn 15.4** (GPL-2.0 with Classpath exception) and its **ASM 7.3.1** dependencies
  (BSD-3-Clause): embedded for JavaScript support. Nashorn includes Joni and double-conversion
  components with their own notices. Complete version-matched license/notice texts are under
  `licenses/` and packaged at `META-INF/licenses/`. Nashorn keeps its original package names because
  its generated bytecode refers to them; ASM is relocated.

Resolved runtime artifact hashes and source URLs are recorded in
[the dependency manifest](provenance/runtime-dependencies.json). Packaging refuses unreviewed
binary changes; the artifact checker checks complete notice hashes. The precise remaining
source limits are described in [dependency origins](licenses/dependency-origins.md).

Paper and PlaceholderAPI are compile-time/server-provided dependencies and are not embedded.
PlaceholderAPI is optional at runtime. Optional integration plugins, including MythicMobs and Vault,
must be supplied separately by the server administrator and are not distributed with ItemLoom.
