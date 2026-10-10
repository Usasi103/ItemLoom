# Keystone runtime source

ItemLoom's source repository contains the subset of Keystone 0.3.6 under
`vendor/keystone`.
It is a separate build dependency, shaded and relocated into the plugin. No
separate Keystone plugin or private binary download is required.

Source revision: `3554cf5502ff4b52532511c396d5ef5c9bd29791`. The included source
files and their upstream hashes are listed in `vendor/keystone/upstream.json`. Runtime code is
unchanged; one Javadoc permission example uses a generic name. Copyright and
GPL-3.0 licensing are retained; see `keystone-GPL-3.0.txt` beside this file and
the repository `NOTICE.md`.

Only the transitive class set used by the item engine is included. Unused
resource installers, update checkers, menus and database pooling are omitted.
Keep this as a pinned dependency snapshot, rather than a second implementation
of the plugin's configuration, command, scheduling or storage behavior. Review
the hashes and consumers when updating it.
