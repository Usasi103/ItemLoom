# ItemLoom maintenance

## Scope and source

- ItemLoom is a public GPL-3.0 project. Keep upstream attribution and dependency licenses. Runtime independence does not remove source provenance.
- Use Java for plugin implementation and the configured Paper/NMS version. Kotlin DSL is limited to Gradle build scripts; do not introduce a Kotlin runtime or TabooLib.
- The public API is under `dev.itemloom.api`. Keep format-specific behavior in compatibility modules; `core` must remain independent of Bukkit and those modules.
- Preserve NI and SX configuration text during ordinary loading and generation. Explicit save operations may write their documented targets. Do not imply that configuration compatibility includes arbitrary legacy Java APIs.
- Maintain normal Java formatting and remove confirmed dead code, obsolete documentation, and unused dependencies within the affected scope. Retain valid compatibility code and documented pending work.

## Public content boundary

- Do not commit server-specific artwork, deployment configurations, player data, credentials, machine paths, local evidence, or dedicated migration tools. Use generic examples and synthetic fixtures.
- Keep the independent public repository history separate from any earlier private development history. Do not import private commits, tags, or release assets.
- Use ItemLoom consistently for the plugin name, commands, permissions, API packages, and native identity. Migration from another product's private namespace belongs outside the plugin.
- The vendored Keystone source subset is a build input. Retain its license, origin revision, and changes; do not silently replace it with a local binary dependency.

## Validation and release

- Follow [BUILDING.md](BUILDING.md). Build output, server sandboxes, caches, and evidence stay outside deployment server directories.
- Distinguish unit tests, synthetic-player probes, real-client checks, load tests, and optional reference comparisons. A skipped test is not a passed test; previous-version results are not current-version validation.
- Inspect the built JAR as well as the source tree for identity, dependencies, licenses, and unintended content. Rebuild after source or packaged-resource changes.
- Releases contain only the plugin JAR and `SHA256SUMS.txt`. Use `tools/prepare_release.py`; do not upload probe JARs, validation archives, extra source ZIPs, resource packs, or migration packages. GitHub's generated source archives remain available.
- Completed changes require a cumulative changelog, final source commit, matching version tag, and formal GitHub Release (`draft=false`, `prerelease=false`). Verify the remote commit, tag, metadata, and downloaded asset hashes. Preserve existing published versions.
- Publishing does not authorize changing a user's running server. Deployment requires explicit task scope and a safe stop/install/start procedure.

## Documentation

- `README.md` is the Chinese introduction; `README.en.md` is a standalone English introduction. Keep reciprocal language links.
- Put usage in `USAGE.md`, build instructions in `BUILDING.md`, current architecture and open work in `IMPLEMENTATION.md`, and cumulative history in `CHANGELOG.md`.
- Describe current behavior accurately. Do not claim full NI/SX compatibility, verified production performance, or real-client validation without corresponding evidence.
