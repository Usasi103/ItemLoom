# Source review

The 2.2.0 review covered all 137 production Java files present in ItemLoom 2.1.0
(`d5fc26d243dbfeb8517218c9b5b319d982c45cb0`), excluding explicitly vendored dependencies.
The review read executable bodies and compared relevant upstream responsibilities;
it did not use a text-overlap percentage as an originality test.

`source-inventory.json` records each current production input, its retention or
replacement reason, and a SHA-256 over UTF-8 text with LF newlines. The inventory
also identifies vendored Keystone and the ItemBridge discovery customization.
Run `python -B tools/verify_source_review.py` to detect missing or changed inputs.
This checks the recorded review boundary; it does not determine legal authorship.

## Replacements in 2.2.0

Fresh implementation workers received observable behavior contracts, allowed
platform/adjacent API surfaces and synthetic test results. They were instructed
not to read the displaced bodies, upstream implementations or their history.
The coordinator integrated the replacements and corrected contracts against
published 2.1.0 behavior. This is a bounded engineering workflow, not a legal
clean-room claim: the coordinator and earlier development had source exposure.

- NI decimal choices, weighted sequences, joins/repetition and conditional nodes.
- Percent placeholders, escaped NBT paths, literal name/lore edits, inventory
  requirements and display templates.
- Connection-local display proofs and defensive snapshots of mutable components.
- Custom durability decisions, drop motion and trigger/consume decisions.
- SX arithmetic evaluation and script scope initialization/calling boundaries.

Required configuration names, script aliases, data layouts, simple policies and
ordinary platform adapters remain. Retaining a file means the review found no
remaining substantive replacement blocker in that file, not that its historical
creation is proven independent. Mixed files explicitly record the replaced
region and the reason for retaining the surrounding implementation.

## Dependencies and limits

Keystone is an attributed source dependency, verified against its own manifest.
ItemBridge's original MIT-licensed binary is pinned by hash; the build removes
its NI/SX provider classes and original discovery class, compiling the attributed
discovery customization instead. Other ItemBridge providers are upstream code.
Nashorn, ASM and Sparrow YAML remain disclosed runtime libraries. See [NOTICE](../NOTICE.md).

Build scripts, packaged resources, fixtures, tests and development tools are
reviewed by responsibility and public-content checks; the per-file Java manifest
does not purport to inventory third-party binary contents or certify every test
as independently authored. The artifact check separately rejects NI/SX plugin
classes, direct JVM type links, excluded providers and obsolete helper resources.

The comparisons include the available NI Kotlin sources, the maintained Java
reference and SX-Item 4.5.11 revision
`4ea02cacfe8687ab9cee34695fe2b44656d2ff35`. Missing historical/source inputs remain
a limit. Git history, historical Releases, GPL-3.0 and upstream attribution are
preserved. Future code changes require a renewed review and inventory update.
