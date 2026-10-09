# Native integration fixtures

Only for an isolated Paper 26.2 server outside `test_server`. Copy these ItemLoom/MythicMobs data folders into that server and install the actual MM dependency separately. Build `:paper26:shadowJar :paper26:probeJar` and install both JARs in the sandbox. These fixtures and probe classes do not ship in the plugin JAR.

Run `ilprobe provider-admin`, `ilprobe use-restrictions`, then `ilprobe mythic-drops`. The Mythic probe intentionally disables ItemLoom at the end to test retained callbacks; run it last. Provider/admin and use-restrictions can also run without MM, using only the ItemLoom fixture.

The SX fixture is a generic PAPER item with a locked quality parameter. `ilprobe mythic-drops` also generates it through MM's native drop type and checks MM-controlled quantity and generator parameters without the SX plugin. For the combined configuration regression, run `sx-config`, `loot-bags`, `papi-config`, `ni-papi`, `provider-admin`, `maintenance`, `lifecycle`, and finally `mythic-drops`; install actual PlaceholderAPI for the two PAPI probes. Wait for each report before proceeding, especially on the first legacy material conversion.

The admin probe temporarily writes controlled item definitions in the sandbox, drives the registered command tree through a synthetic player, and checks the actual scheduler. The use probe dispatches Bukkit events using controlled matrix/view fixtures and temporarily places a real crafter block, restoring its original state. The Mythic probe uses real MM tables and mob spawning plus a detached NMS player. None represents a connected-client gameplay or load test. Reports are written to the probe plugin data folder; copy them outside the disposable sandbox before the next clean run.
