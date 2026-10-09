# ItemLoom

[简体中文](README.md) · [Downloads](https://github.com/Usasi103/ItemLoom/releases) · [Building](BUILDING.md)

ItemLoom is an open-source custom item plugin for **Paper 26.2 and Java 25**. Define equipment, random attributes, interactions, and loot bags in YAML, or use supported **NeigeItems (NI) and SX-Item configurations**.

Its item generation, storage, and Java API run independently. Neither NI nor SX-Item is required. ItemLoom is licensed under GPL-3.0 and retains upstream attribution in [NOTICE](NOTICE.md).

## Features

- Item materials, names, lore, enchantments, attributes, NBT, and modern item components.
- Configuration nodes, weighted random values, saved rolls, and JavaScript.
- Item interactions and combat triggers, cooldowns, consumption, durability, charges, expiration, and updates.
- Item packs and loot bags using shared lists, bag-specific lists, or MythicMobs item drop tables.
- Native MythicMobs `itemloom` drops, PlaceholderAPI support, and a Java provider API.
- Item browsing, search, inspection, snapshot saving, and parameterized batch generation. Failed reloads preserve the active catalog.
- Optional player-specific display through `client_bound_data`. The server resolves a display copy before sending it; the client receives the resolved result.

## Quick start

1. Use Java 25 and Paper 26.2. The current NMS build target is Paper `26.2 build 123`; other server versions require separate validation.
2. Download the plugin JAR from [Releases](https://github.com/Usasi103/ItemLoom/releases), place it in your server's `plugins/` directory, and start the server.
3. Create `plugins/ItemLoom/Items/example.yml`:

```yaml
ExampleSword:
  material: DIAMOND_SWORD
  name: '&bWoven Blade'
  lore:
    - '&7Created with ItemLoom'
```

4. Run these commands as an operator or with the `itemloom.admin` permission:

```text
/il reload
/il get ExampleSword
```

The main command is `/itemloom`, with `/il` as an alias. No resource pack is required for ordinary items. Supply your own pack for custom textures and models.

## Existing configurations

| Source | Location | Supported scope |
|---|---|---|
| NI | Preserve its directories under `plugins/ItemLoom/`, including `Items/`, `GlobalSections/`, `Scripts/`, `ItemActions/`, `ItemPacks/`, and `Functions/` | Items, nodes, actions, packs, and adapted legacy script calls |
| SX-Item | Preserve `Item/`, `RandomString/`, `Scripts/`, and `Config.yml` under `plugins/ItemLoom/SX-Item/` | Built-in Default/Import generators, random expressions, ordinary scripts, updates, and NBT protection |

Reading configurations and generating items does not rewrite source files. Explicit save commands and script save APIs can write files. NI and SX configurations may coexist, but their item IDs must be unique.

Compatibility is limited to the implemented configuration and script contracts. Legacy NI/SX commands, arbitrary binary Java API compatibility, and third-party SX generators are not provided. Full legacy script coverage, real-client behavior, and every third-party combination remain under validation. Existing inventory migration and external API upgrades require separate handling.

## Integrations and development

With MythicMobs 5 installed, use its native probability, quantity, and drop-table rules:

```yaml
Drops:
  - itemloom{id=ExampleSword} 1 0.25
```

MythicMobs and PlaceholderAPI are optional. Supported `%ni_*%` placeholders retain their existing namespace. Asynchronous inventory or script queries use the latest main-thread result; the first request returns an empty value and schedules a refresh.

Developers obtain `dev.itemloom.api.ItemLoom` from Bukkit's ServicesManager to generate items, register providers, and receive events. See [BUILDING](BUILDING.md) for a standalone build and [USAGE](USAGE.md) for detailed configuration and API documentation in Chinese.

Report issues with the plugin and Paper versions, a minimal configuration, and relevant logs. Remove credentials and private data before posting. Current implementation boundaries and remaining work are recorded in [IMPLEMENTATION](IMPLEMENTATION.md).
