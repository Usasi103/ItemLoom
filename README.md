# ItemLoom

[English](README.en.md) · [下载](https://github.com/Usasi103/ItemLoom/releases) · [使用说明](USAGE.md) · [构建](BUILDING.md) · [更新日志](CHANGELOG.md)

ItemLoom 是面向 **Paper 26.2 / Java 25** 的开源自定义物品插件。用 YAML 定义装备、随机词条、交互动作和战利品袋，也可以沿用支持范围内的 **NeigeItems（NI）和 SX-Item 配置**。

物品生成、数据存储与 Java API 独立运行，无需安装 NI 或 SX-Item。项目采用 GPL-3.0，保留上游来源署名，详见 [NOTICE](NOTICE.md)。

2.2.0 扩大了来源审查，并按行为契约替换已识别实现；逐文件理由与校验清单见[来源审查](provenance/README.md)。配置兼容、独立运行与历史来源是不同的事项，本项目不宣称法律意义的 clean-room。

可通过内嵌的 [ItemBridge](https://github.com/jhqwqmc/ItemBridge) 使用 CraftEngine 等插件的完整物品作为底稿。ItemLoom 覆盖配置明确指定的字段，保留其他组件、PDC 和自定义标记；外部提供者需要单独安装。参见[外部物品底稿](USAGE.md#外部物品底稿itembridge)。

## 能做什么

- **定义物品**：材质、名称、Lore、附魔、属性、NBT 与现代物品组件；节点、权重随机、锁定结果和 JavaScript。
- **编写行为**：物品交互与战斗触发、冷却、消耗、耐久、充能、到期与自动更新。
- **组织奖励**：物品包、共享或专属战利品袋列表，以及 MythicMobs 原生掉落表。
- **接入其他插件**：MythicMobs 的 `itemloom` 掉落类型、PlaceholderAPI，以及可注册物品生成器的 Java API。
- **管理物品库**：分页列表、搜索、主手检查、快照保存和带参数的批量发放；重载失败保留当前可用配置。
- **按玩家展示**：可选 `client_bound_data` 在发送前生成展示副本；解析发生在服务端，客户端接收解析结果。

## 安装与第一个物品

1. 准备 Java 25 和 Paper 26.2。当前 NMS 构建基线为 Paper `26.2 build 123`；其他服务端版本需要另行验证。
2. 从 [Releases](https://github.com/Usasi103/ItemLoom/releases) 下载插件 JAR，放入服务器 `plugins/`，启动服务器。
3. 创建 `plugins/ItemLoom/Items/example.yml`：

```yaml
ExampleSword:
  material: DIAMOND_SWORD
  name: '&b织纹之刃'
  lore:
    - '&7由 ItemLoom 生成'
```

4. 以 OP 或拥有 `itemloom.admin` 权限的身份执行：

```text
/il reload
/il get ExampleSword
```

根命令为 `/itemloom`，别名 `/il`。插件本身不需要资源包；自定义贴图和模型由管理员自行提供。

## 使用已有配置

| 配置来源 | 放置位置 | 主要范围 |
|---|---|---|
| NI | `plugins/ItemLoom/` 下的 `Items/`、`GlobalSections/`、`Scripts/`、`ItemActions/`、`ItemPacks/`、`Functions/` 等原目录 | 物品、节点、动作、物品包及已适配的旧脚本调用 |
| SX-Item | `plugins/ItemLoom/SX-Item/`，保留 `Item/`、`RandomString/`、`Scripts/`、`Config.yml` | 内置 Default/Import、随机表达式、普通脚本、更新与 NBT 保护 |

正常读取和生成保留配置原文；显式保存命令或脚本保存接口才写入文件。两种配置可以共存，物品 ID 必须唯一。

**配置兼容有明确范围。** 本项目不提供旧 NI/SX 命令、任意旧插件 Java API 的二进制兼容，也不包含 SX 的第三方生成器扩展。完整旧脚本、真实客户端和全部第三方组合仍需逐项验证；现有物品迁移与外部插件 API 升级应单独处理。详细边界见[使用说明](USAGE.md)。

## 联动与开发

MythicMobs 5 可直接引用物品，概率、数量与掉落表组合交给 MM：

```yaml
Drops:
  - itemloom{id=ExampleSword} 1 0.25
```

PlaceholderAPI 和 MythicMobs 均为可选依赖，按需安装。NI 的 `%ni_*%` 占位符保留兼容入口；涉及背包或脚本的异步读取先返回缓存，首次为空，再由主线程刷新。

开发者通过 Bukkit ServicesManager 获取 `dev.itemloom.api.ItemLoom`，注册自有物品生成器、生成物品或订阅事件。见 [Java API](USAGE.md#独立-java-api) 和[构建说明](BUILDING.md)。

[实现与待办](IMPLEMENTATION.md) 记录当前能力及验证缺口。[提交问题](https://github.com/Usasi103/ItemLoom/issues)时请附上插件版本、Paper 版本、最小配置与相关日志，并移除敏感数据。
