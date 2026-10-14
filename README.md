# ItemLoom

[English](README.en.md) · [下载](https://github.com/Usasi103/ItemLoom/releases) · [使用说明](USAGE.md) · [构建说明](BUILDING.md)

**用 YAML 定义自定义物品，让装备、随机词条、交互动作和奖励共用一个物品库。**

ItemLoom 是运行于 **Paper 26.2 / Java 25** 的开源物品插件。可以从一个简单的物品定义开始，也可以直接载入支持范围内的 **NeigeItems（NI）与 SX-Item 配置**。物品生成、数据存储和 Java API 由 ItemLoom 独立提供，无需安装 NI 或 SX-Item。

## 主要功能

- **物品定义**：配置材质、名称、Lore、附魔、属性、NBT 和现代物品组件。
- **随机生成**：使用配置节点、权重随机、锁定结果与 JavaScript，生成不同品质的装备或材料。
- **物品行为**：为交互和战斗配置动作、冷却与消耗，管理耐久、充能、到期和自动更新。
- **奖励组织**：使用物品包、共享或专属权重列表、MythicMobs 物品掉落表，配置可右键开启的战利品袋。
- **物品管理**：浏览和搜索物品库、检查主手数据、保存当前物品快照，按参数批量发放。
- **按玩家展示**：通过可选的 `client_bound_data` 生成面向接收玩家的展示副本，内容由服务端解析后发送。

## 安装与入门

### 1. 安装插件

准备 Java 25 和 Paper 26.2，从 [Releases](https://github.com/Usasi103/ItemLoom/releases) 下载 `ItemLoom-<版本>.jar`，放入服务器的 `plugins/` 目录，然后启动服务器。

当前 NMS 构建目标为 **Paper 26.2 build 123**；更换服务端版本需单独验证。发布附件包含插件 JAR 与 `SHA256SUMS.txt`。

### 2. 定义第一个物品

创建 `plugins/ItemLoom/Items/example.yml`：

```yaml
ExampleSword:
  material: DIAMOND_SWORD
  name: '&b织纹之刃'
  lore:
    - '&7由 ItemLoom 生成'
```

### 3. 重载并获取

以 OP 或拥有 `itemloom.admin` 权限的身份执行：

```text
/il reload
/il get ExampleSword
```

之后可以在 [使用说明](USAGE.md) 中继续添加随机节点、动作和奖励。普通物品无需资源包；自定义贴图与模型使用管理员自己的资源包。

## 常用命令

根命令为 `/itemloom`，别名为 `/il`。下列命令均使用 `itemloom.admin` 权限，默认授予 OP。

| 命令 | 用途 |
|---|---|
| `/il list [页]` | 分页浏览物品 ID |
| `/il search <ID片段> [页]` | 按 ID 搜索物品 |
| `/il get <ID> [数量] [stack或roll] [参数JSON]` | 为自己生成物品 |
| `/il give <玩家> <ID> [数量] [stack或roll] [参数JSON]` | 为指定在线玩家生成物品 |
| `/il inspect` | 查看主手物品的组件与 NBT |
| `/il save <ID> [文件.yml]` | 将主手物品保存为配置快照 |
| `/il reload` | 重新载入物品与相关配置 |

发放数量为 1–256，默认 1。`stack` 生成一次后复制，`roll` 每件独立生成。`save` 保存当前结果，生成规则需自行编写。完整参数与示例见 [管理命令](USAGE.md#管理命令)。

## 沿用 NI 与 SX-Item 配置

两种格式可以同时使用，并通过同一套 ItemLoom 命令获取物品。保留原目录结构，将配置放在以下位置：

| 格式 | 目录 | 已支持的主要内容 |
|---|---|---|
| NI | `plugins/ItemLoom/` 下的 `Items/`、`GlobalSections/`、`Scripts/`、`ItemActions/`、`ItemPacks/`、`Functions/` 等 | 物品、继承、节点、动作、物品包及已适配的脚本调用 |
| SX-Item | `plugins/ItemLoom/SX-Item/` 下的 `Item/`、`RandomString/`、`Scripts/` 与 `Config.yml` | 内置 Default/Import、随机表达式、普通脚本、更新与 NBT 保护 |

物品 ID 必须唯一。正常加载、重载和生成保留来源配置原文；显式保存命令或脚本保存接口会写入约定的文件。配置或脚本准备校验失败时，保留当前可用配置。

兼容范围以 [NI 配置说明](USAGE.md#ni-配置与数据目录) 和 [SX-Item 配置说明](USAGE.md#sx-item-配置) 为准。旧 NI/SX 命令、任意旧 Java API 的二进制兼容及 SX 第三方生成器扩展不在支持范围内；迁移已生成物品时，还需核对其数据身份。

## 插件联动

| 接入方式 | 用途 | 安装要求 |
|---|---|---|
| MythicMobs 5 | 在原生掉落表、装备和物品交付中引用 ItemLoom 物品 | 按需安装 MythicMobs |
| PlaceholderAPI | 按玩家解析占位符，并提供已支持的 `%ni_*%` 兼容入口 | 按需安装 PlaceholderAPI |
| ItemBridge | 使用 CraftEngine 等提供者的完整物品作为底稿，再应用配置字段 | ItemBridge 已内嵌；提供者插件需单独安装 |

例如，在 MythicMobs 怪物配置中添加：

```yaml
Drops:
  - itemloom{id=ExampleSword} 1 0.25
```

数量、概率和掉落表组合由 MythicMobs 处理。完整配置见 [MythicMobs 接入](USAGE.md#mythicmobs-原生类型)。

使用 CraftEngine 物品作为底稿时，可将 NI 定义的材质写为：

```yaml
material: 'itembridge:craftengine:example:blade'
```

`example:blade` 需要先在 CraftEngine 中定义。ItemLoom 保留底稿中未被配置字段覆盖的组件、PDC 和自定义标记。来源语法、SX 写法及覆盖规则见 [外部物品底稿](USAGE.md#外部物品底稿itembridge)。

## 文档与开发

开发者可通过 Bukkit ServicesManager 获取 `dev.itemloom.api.ItemLoom`，生成物品、注册自有生成器并订阅事件；2.7.0 起还可以按覆盖的节点值重建已有物品。接入示例见 [Java API](USAGE.md#独立-java-api)，重建接口见 [重建已有物品](USAGE.md#重建已有物品)。

| 文档 | 内容 |
|---|---|
| [USAGE.md](USAGE.md) | 配置、脚本、命令、联动、战利品袋与 Java API |
| [BUILDING.md](BUILDING.md) | 独立构建、依赖、测试与发布准备 |
| [IMPLEMENTATION.md](IMPLEMENTATION.md) | 当前架构、验证范围与待办 |
| [CHANGELOG.md](CHANGELOG.md) | 累计版本记录 |

提交问题时，请在 [Issues](https://github.com/Usasi103/ItemLoom/issues) 中附上 ItemLoom 与 Paper 版本、最小配置和相关日志，并移除敏感数据。

ItemLoom 采用 [GPL-3.0](LICENSE) 许可，保留上游与依赖署名。来源和修改说明见 [NOTICE](NOTICE.md) 与 [来源记录](provenance/README.md)。
