# ItemLoom

[简体中文](README.md) · [Downloads](https://github.com/Usasi103/ItemLoom/releases) · [Usage](USAGE.md) · [Building](BUILDING.md)

**Define custom items in YAML and share one catalog across equipment, random rolls, interactions, and rewards.**

ItemLoom is an open-source item plugin for **Paper 26.2 and Java 25**. Start with a simple item definition or load supported **NeigeItems (NI) and SX-Item configurations** directly. Item generation, storage, and the Java API are provided by ItemLoom itself. Neither NI nor SX-Item is required.

## Features

- **Item definitions**: Materials, names, lore, enchantments, attributes, NBT, and modern item components.
- **Random generation**: Configuration nodes, weighted choices, saved rolls, and JavaScript for equipment and materials with varying qualities.
- **Item behavior**: Interaction and combat actions, cooldowns, consumption, durability, charges, expiration, and automatic updates.
- **Rewards**: Item packs, shared or bag-specific weighted lists, and MythicMobs item drop tables for loot bags opened with a right click.
- **Administration**: Browse and search the catalog, inspect held items, save snapshots, and generate items in batches with parameters.
- **Player-specific display**: Optional `client_bound_data` creates display copies for the receiving player. The server resolves their content before sending it.

## Quick start

### 1. Install

Prepare Java 25 and Paper 26.2. Download `ItemLoom-<version>.jar` from [Releases](https://github.com/Usasi103/ItemLoom/releases), place it in your server's `plugins/` directory, and start the server.

The current NMS build target is **Paper 26.2 build 123**. Other server versions require separate validation. Release attachments contain the plugin JAR and `SHA256SUMS.txt`.

### 2. Define an item

Create `plugins/ItemLoom/Items/example.yml`:

```yaml
ExampleSword:
  material: DIAMOND_SWORD
  name: '&bWoven Blade'
  lore:
    - '&7Created with ItemLoom'
```

### 3. Reload and obtain it

Run these commands as an operator or with the `itemloom.admin` permission:

```text
/il reload
/il get ExampleSword
```

Continue with [USAGE](USAGE.md) to add random nodes, actions, and rewards. Ordinary items require no resource pack. Supply your own pack for custom textures and models.

## Commands

The main command is `/itemloom`, with `/il` as an alias. All commands below require `itemloom.admin`, which is granted to operators by default.

| Command | Purpose |
|---|---|
| `/il list [page]` | Browse item IDs by page |
| `/il search <ID-fragment> [page]` | Search item IDs |
| `/il get <ID> [amount] [stack-or-roll] [JSON-parameters]` | Generate items for yourself |
| `/il give <player> <ID> [amount] [stack-or-roll] [JSON-parameters]` | Generate items for an online player |
| `/il inspect` | Inspect the held item's components and NBT |
| `/il save <ID> [file.yml]` | Save the held item as a configuration snapshot |
| `/il reload` | Reload items and related configuration |

Amounts range from 1 to 256 and default to 1. `stack` generates once and copies the result; `roll` generates each item independently. `save` captures the current result; write generation rules separately. See [USAGE](USAGE.md) for full arguments and examples.

## Existing NI and SX-Item configurations

Both formats can coexist and use the same ItemLoom commands. Preserve their directory layouts under these locations:

| Format | Location | Main supported features |
|---|---|---|
| NI | `Items/`, `GlobalSections/`, `Scripts/`, `ItemActions/`, `ItemPacks/`, `Functions/`, and related directories under `plugins/ItemLoom/` | Items, inheritance, nodes, actions, packs, and adapted script calls |
| SX-Item | `Item/`, `RandomString/`, `Scripts/`, and `Config.yml` under `plugins/ItemLoom/SX-Item/` | Built-in Default/Import generators, random expressions, ordinary scripts, updates, and NBT protection |

Item IDs must be unique. Normal loading, reloading, and generation preserve source configuration text. Explicit save commands and script save APIs write their documented targets. Failed configuration or script preparation checks preserve the active configuration.

Compatibility is limited to the contracts described in [USAGE](USAGE.md). Legacy NI/SX commands, arbitrary binary compatibility with their old Java APIs, and third-party SX generators are outside the supported scope. Migrating existing items also requires checking their stored identity.

## Integrations

| Integration | Purpose | Installation |
|---|---|---|
| MythicMobs 5 | Reference ItemLoom items in native drops, equipment, and item delivery | Install MythicMobs when needed |
| PlaceholderAPI | Resolve placeholders for players and provide supported `%ni_*%` compatibility entries | Install PlaceholderAPI when needed |
| ItemBridge | Use complete items from providers such as CraftEngine as prototypes, then apply configured fields | ItemBridge is embedded; install the chosen provider separately |

For example, add this to a MythicMobs mob configuration:

```yaml
Drops:
  - itemloom{id=ExampleSword} 1 0.25
```

MythicMobs handles quantity, probability, and drop-table composition. See [USAGE](USAGE.md) for full integration examples.

To use a CraftEngine item as a prototype, set the material in an NI definition:

```yaml
material: 'itembridge:craftengine:example:blade'
```

Define `example:blade` in CraftEngine first. ItemLoom preserves components, PDC, and custom tags that configured fields do not override. Provider syntax, SX definitions, and override rules are documented in [USAGE](USAGE.md).

## Documentation and development

Developers obtain `dev.itemloom.api.ItemLoom` through Bukkit's ServicesManager to generate items, register providers, and subscribe to events. API examples are in [USAGE](USAGE.md).

| Document | Contents |
|---|---|
| [USAGE.md](USAGE.md) | Configuration, scripts, commands, integrations, loot bags, and the Java API; written in Chinese |
| [BUILDING.md](BUILDING.md) | Standalone builds, dependencies, tests, and release preparation; written in English |
| [IMPLEMENTATION.md](IMPLEMENTATION.md) | Current architecture, validation scope, and remaining work; written in Chinese |
| [CHANGELOG.md](CHANGELOG.md) | Cumulative version history; written in Chinese |

When reporting an [issue](https://github.com/Usasi103/ItemLoom/issues), include the ItemLoom and Paper versions, a minimal configuration, and relevant logs. Remove sensitive data before posting.

ItemLoom is licensed under [GPL-3.0](LICENSE) and retains upstream and dependency attribution. See [NOTICE](NOTICE.md) and the [source records](provenance/README.md) for origins and modifications.
