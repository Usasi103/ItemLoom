# ItemLoom 使用说明

适用于 `2.2.0`，目标 Paper 26.2 / Java 25。入门见 [README](README.md)，构建见 [BUILDING](BUILDING.md)，当前验收缺口见 [IMPLEMENTATION](IMPLEMENTATION.md)。

集合节点的随机实现保留权重、重复项及缓存规则，但不保证同一个随机种子的逐次结果与旧版本相同。已保存的锁定随机值仍按原配置规则读取。

## NI 配置与数据目录

将 NI 的配置目录结构复制到 `plugins/ItemLoom/`，保留 `Items/`、`GlobalSections/`、`Scripts/`、`ItemActions/`、`ItemPacks/`、`Functions/` 和需要的 `config.yml`。不需要安装 NI 插件。普通读取、生成和维护不改写配置原文；显式保存命令或脚本保存接口才会写文件。

最小物品定义放在 `Items/example.yml`：

```yaml
ExampleGem:
  material: EMERALD
  name: '&a<quality> 宝石'
  sections:
    quality: common
```

执行 `/il reload`，再执行 `/il get ExampleGem 1 roll {"quality":"rare"}`。支持范围包括继承、节点、主要物品字段、动作、物品包、普通脚本和已适配的旧脚本调用。直接导入 `pers.neige.neigeitems` 的脚本仅能使用兼容层已经提供的类名和方法；任意反射、NI 插件二进制 API 或所有第三方扩展不在保证范围内。

当 `plugins/ItemLoom/Items/` 和 `plugins/ItemLoom/SX-Item/` 均不存在时，会尝试读取相邻 `plugins/NeigeItems/`。这只是配置输入回退；之后的显式保存也会写入该实际输入树。创建自有目录后，应将需要同时加载的配置放在同一输入树中，不会自动合并两个目录。

新生成物品使用 `itemloom:items` 身份。NI 配置/数据兼容与此前独立版本的改名迁移是不同路径；此前独立版本的数据目录、私有物品标记和直接 Java API 调用方须另行迁移。插件不携带专用迁移工具，也不按物品名称或贴图推断身份。

## 外部物品底稿（ItemBridge）

ItemBridge 1.0.32 已内嵌，无需另外安装 ItemBridge。安装所需的提供者插件，例如 CraftEngine，在其物品库中创建自己的物品，再使用明确的来源语法：

```yaml
# Items/example.yml（NI 格式）
BridgeBlade:
  material: 'itembridge:craftengine:example:blade'
  name: '&6<quality>之剑'
  sections: {quality: 稀有}
  options: {durability: 100}

# SX-Item/Item/example.yml（SX 格式）
SXBridgeBlade:
  ID: 'itembridge:craftengine:example:blade'
  Name: '&6自定义之剑'
  Update: true
```

`itembridge:<提供者>:<物品 ID>` 中，提供者使用 ItemBridge 的完整小写 ID，例如 `craftengine`、`itemsadder`、`oraxen`、`nexo`、`mythicmobs`、`magicgem`；物品 ID 中的冒号保留。例子的 `example:blade` 是管理员自建物品，不随插件附送。普通 NI `material` 和 SX `ID` 保持原有材质语义；NI 的 `static.material` 也支持外部来源。

每次生成在主线程向提供者取完整物品并先克隆，再应用 ItemLoom 字段。未指定的名称、Lore、组件、PDC、自定义 NBT 和底稿数量保留；SX 可用 `Amount` 覆盖数量，命令和掉落交付流程仍按自己的数量参数处理。NI 静态覆盖先于普通字段，显式 `components` / SX `Components` 仍按原顺序覆盖。对外部底稿，明确的 `lore: []` / `Lore: []` 清除 Lore，`unbreakable: false` / `Unbreakable: false` 清除不可破坏状态。NBT 子树合并保留未覆盖字段；明确替换整个 `custom_data` 组件则替换该组件。ItemLoom 身份由本插件管理，普通 NI 的 `options.removeNBT` 约定仍有效。

会把在线玩家传给提供者；ItemLoom 的随机参数、节点和锁定值仍由本插件展开，不自动注入外部插件的参数命名空间。外部物品不进入静态成品缓存，因此每次都能获得提供者的最新或按玩家生成的结果。提供者缺失、停用、物品不存在或 API 不兼容时生成失败并报告来源，不改成石头或自动换另一个来源。NI、SX 和 ItemLoom 本身不能作为外部提供者，以免重新依赖原插件或递归生成。

保留完整底稿也意味着保留外部插件的识别标记：其监听器可能继续对该物品生效。名称和属性由 ItemLoom 覆盖，并不取消外部插件的行为。各提供者版本仍须按实际环境验证。

## 管理命令

根命令 `/itemloom`，别名 `/il`；权限 `itemloom.admin`，默认 OP。旧 NI/SX 命令不兼容。

| 命令 | 行为 |
|---|---|
| `/il list [页]` | 按 ID 排序，每页 20 项；点击 ID 填入获取命令 |
| `/il search <ID片段> [页]` | ID 不区分大小写的子串搜索，不调用生成器 |
| `/il inspect` | 查看主手材质、数量、ItemLoom/NI 标识和原生组件/NBT；过长内容截断，适合的长度提供完整复制按钮 |
| `/il save <ID> [文件.yml]` | 保存主手当前状态，默认 `Items/saved.yml`；遇到已有 ID 拒绝覆盖 |
| `/il get <ID> [数量] [stack或roll] [参数JSON]` | 为自己生成和发放 |
| `/il give <玩家> <ID> [数量] [stack或roll] [参数JSON]` | 为指定在线玩家生成和发放 |
| `/il reload` | 完整候选重载；校验失败保留旧目录 |

例如：

```text
/il get ExampleSword 3 roll {"quality":"rare","owner":"Alice"}
/il give Alice ExampleGem 64 stack {"quality":"rare"}
```

数量为 1–256，默认 1；默认模式为 `stack`。`stack` 生成一次后复制、按物品最大堆叠数拆堆；`roll` 每件重新生成。这里的数量是最终件数，覆盖配方生成的数量。参数必须是 JSON 字符串键值对象，支持空格，拒绝重复键、非字符串值和超过 16 Ki 字符的对象。

每个目标同时最多一个发放任务，全局最多八个。每个任务每 tick 最多生成一件随机物品，全部生成成功后再发放；目录变更、目标离线或插件关闭会取消未交付任务。单次任意 Java/JS 回调仍可能很慢，此调度不是硬性 CPU 配额。背包放不下的余量掉落在目标脚下，设置目标 UUID 为拾取主人。掉落被取消或交付异常时报告已确认发放数量，停止剩余交付，不自动重试整批；生成阶段无部分发奖，不代表交付阶段具备事务回滚。

保存的是当前物品状态，不会逆向恢复原随机节点、脚本和生成规则，也不保存堆叠数量。快照保留已存在的身份 NBT，新定义 ID 不会自动重写物品里原有的 ID。保存复用现有组件/NBT 快照和文件校验，拒绝越界路径；捕获到的写入失败会尝试回滚，不承诺跨文件崩溃原子性。若当前输入仍回退到相邻 `NeigeItems/`，显式保存也写入该实际输入树。普通加载、生成和迁移继续不回写配置。

## SX-Item 配置

将原 SX 配置复制到 `plugins/ItemLoom/SX-Item/`，保留原文；不需要安装 SX-Item。目录如下：

```text
ItemLoom/
  Items/                    # 原有 NI 格式
  SX-Item/
    Config.yml              # 注意 SX 使用大写 C，独立于 NI 的 config.yml
    Item/                   # SX 使用单数 Item；支持子目录
    RandomString/
    Scripts/
      Global/
```

`Item/` 和 `RandomString/` 中读取 `.yml`/`.yaml`；脚本读取 `.js`。路径中任何文件或目录名以 `NoLoad` 开头时跳过，同名前缀的物品/随机节点也跳过。别名 `Alias: Target`、YAML 锚点与 `ItemId.ID: PAPER` 简写可以保留。若仍使用相邻 `NeigeItems/` 作为实际输入目录，则 SX 子目录也放在该实际输入目录下；创建自有 `ItemLoom/SX-Item/` 后将使用自有输入树，需要同时放入要继续读取的 NI 配置。

通用示例（`SX-Item/Item/example.yml`）：

```yaml
SXExample:
  Type: Default
  ID: PAPER
  Name: '&b<l:quality> item'
  Lore:
    - 'Roll: <l:power>'
    - '<if:<cmp:<l:power> ge 15>?High:Normal>'
  Random:
    quality:
      - 3: common
      - 1: rare
    power: '<i:10_20>'
  Update: true
  ProtectNBT:
    - components.minecraft:enchantments
```

运行 `/il reload` 后，使用 `/il get SXExample`，或 `/il get SXExample 1 roll {"quality":"rare"}`。命令/API 和 MM 的 `itemloom{id=SXExample}` 使用相同物品 ID；NI/SX/provider 的 ID 不得冲突。`/il save` 保存当前状态为 NI 格式快照，不覆盖同名 SX 定义，也不反向生成 SX 模板。

| 范围 | 支持行为 |
|---|---|
| 内置生成器 | `Default`（缺省）与 `Import`；Import 的 `Item` 为 Bukkit 序列化 ItemStack，按次复制，不解析随机节点 |
| 外观与数据 | `ID/Id/id`（规范 `ID` 优先）、ID 列表、Name、Lore、Amount、Durability、EnchantList、ItemFlagList、Unbreakable、SkullName、Color、Potion、CustomModelData、ClearAttribute、Attributes、NBT、Components |
| 材质/耐久 | 现代材质、旧数字/名称与数据值；`material:damage`、绝对损耗、`<剩余值`、剩余百分比；旧输入按 Paper 26.2 的预生成映射转为现代物品，不提供旧服务器适配 |
| 随机 | 局部 Random / 全局 RandomString、权重、多行组、局部 null 回退全局；`l` 锁定本次结果，调用参数优先；未命中节点删除所在 Lore 行 |
| 表达式 | 嵌套 `s/l/i/r/d/b/c/min/max/eq/like/cmp/if/when/null/u/t/j`；`[byte/short/int/long/float/double]` 数值转换；`$<…>` 转义 |
| 脚本/PAPI | JS 文件与 Global 作用域，`<j:File.function#args>` 调用 `function(handler,args)`；参数中在线玩家名字转玩家对象。真实 PAPI 在生成时按 viewer 解析，不回写 YAML |
| 更新 | Update 开启且定义改变时，玩家登录、切换槽位或打开背包后入队，每 20 tick 处理待检查背包；保留数量及锁定值。全局 ProtectNBT 与物品列表合并，`!path` 取消全局保护，`components.<ID>` 保留整项组件 |

SX 的 `Config.yml` 读取 `DecimalPrecision`（0–12）、`TimeFormat`、`ScriptEngine`（js/javascript/nashorn 或 null 禁用）、`ProtectNBT`、`Restrictions.CraftingTable/EnchantingTable/BlockPlace`。三项 SX 使用限制缺省开启；不影响 NI 物品的原有默认规则。SX 的日志、命令发放与死亡自动入包设置不接管 ItemLoom/MM 的流程。

兼容基线为 SX-Item 4.5.11、提交 `4ea02cacfe8687ab9cee34695fe2b44656d2ff35`。以下边界需要保留：

- 不支持 SX-Attribute、SX-Item-Action 或其他插件注册的生成器/表达式，也不提供旧 SX 命令、事件类型或 `github.saukiya.*` Java API。未知生成器和物品字段拒绝加载；动态值错误在生成时报出，不静默生成替代品。
- 普通 JS 的 `handler.getPlayer/getLockMap/getOtherMap/replace/random`、`Bukkit/Arrays/Utils` 可用；示例中的 `SXItem` 提供 `getInst/getRandom`，其中 `getInst()` 返回 ItemLoom 插件。脚本可以直接调用 Bukkit，任意自行安排的文件写入、任务或其他插件副作用不能由配置回滚撤销；使用提供的 listener 注册的事件会随版本关闭注销。
- `NBT` 位于现代 custom_data，`Components` 使用 26.2 原生结构；不会把所有旧版本 NBT 或旧 food 等组件结构自动升级。ProtectNBT 的普通路径支持 compound 的点路径，不支持列表索引；不允许保护 ItemLoom 自身身份或整个 custom_data 组件。
- 新物品只写入 `itemloom:items` 标识；本版不自动接管旧 SX 插件已经生成的背包物品。NI 脚本物品管理器、NI ItemPacks 的内部物品引用和 `%ni_parse_*%` 仍使用 NI 前端，不能视作 SX 管理接口。
- 修正原版别名指向失效、全负数 max 与药水效果选错；未知材质/附魔/flag、非正数量、空/非法权重显式报错。Lore 转义按一次解析保留字面文本，避免原版二次解析吞掉转义；不复现有缺陷的计算器边缘行为。组件错误会报告，纯静态定义会在重载准备时构建验证；随机/脚本定义不提前试抽。
- 旧数字/旧名称材质通过内置的 Paper 26.2 映射表解析，普通生成不再初始化 Paper 的 legacy 转换器。现代名称继续优先；数据值与耐久后缀保留原语义。该改进不限制任意脚本或外部插件的执行耗时。

读取配置、生成物品和自动更新均不改写来源文件。NI 和 SX 的完整重载共用一次发布；普通配置错误、脚本加载错误、别名循环和 ID 冲突不会替换当前可用目录。

## MythicMobs 原生类型

安装 MythicMobs 5 时自动注册 `itemloom`（大小写不敏感）。不包含 MM4 适配，不打包 MM 类或付费 JAR。缺少 MM 可正常启动。MM 的配置 reload 和 ItemLoom 的 reload 均可继续使用该类型；不支持通过通用热加载工具动态更换 MM。

```yaml
# MythicMobs 的怪物配置
ExampleMonster:
  Type: ZOMBIE
  Equipment:
  - itemloom{id=ExampleSword;quality=rare} HAND
  Drops:
  - itemloom{id=ExampleGem;quality=rare} 2-4 0.25
  - IndependentSwords 3 1

# MythicMobs 的 DropTables 配置（另一个文件）
IndependentSwords:
  Drops:
  - itemloom{id=ExampleSword;quality=rare} 1 1
```

MM 负责概率、数量、条件、装备槽和表组合。ItemLoom 的回调每次生成一个物品栈；`itemloom{...} 3 1` 是同一结果三件，上例重复抽表才是三次独立生成。MM 传入的数量向下取整，小于 1 不生成物品；非有限数和超过整型上限的数量拒绝处理。

`item` 和 `id` 指定物品 ID，同时存在时使用 `item`。ID 和其他参数可以使用 MM 的占位符，回调时按本次掉落上下文解析。击杀/调用原因是玩家时将该玩家作为生成 viewer，否则 viewer 为 null。

其他参数传给生成器的 saved rolls。默认补充 `mob_level`；掉落者为 ActiveMob 时补充 `mob_name_display`、`mob_name_internal`、`mob_uuid`，显式参数优先。`item/id/amount/a/chance/c/weight/w/condition/conditions/triggercondition/triggerconditions` 为保留键；使用 `data.amount=...` 等前缀传入同名物品参数。

该类型实现 MM 的原生 `IItemDrop`，可用于 LootBag 的掉落、装备及 give 流程。需要直接入包时使用 MM 原生 giveitem 技能/交付流程，其数量和余量处理由 MM 执行；ItemLoom 不额外注册死亡发奖监听器。所有 MM 技能配置组合和真实客户端行为仍须按实际配置验证。

## PlaceholderAPI 与按玩家展示

安装 PlaceholderAPI 后，生成时可按本次 viewer 解析占位符。支持 NI 的 `%ni_parse_*%`、`%ni_data_*%`、`%ni_nbt_*%`、`%ni_amount_*%` 和 `%ni_count_*%`，保留 `Papi.enableJs/enableRegex` 开关。若已安装 NI，或已有其他插件注册 `ni` expansion，则保留原拥有者。

主线程请求即时计算。异步请求不等待主线程：首次返回空字符串并安排刷新，之后读取最近一次主线程结果。缓存按玩家和配置版本隔离，退出、重载或关闭后旧结果失效。脚本求值使用独立请求作用域，不复现跨请求残留的 JavaScript `var`。

`client_bound_data` 是可选的发送副本处理。服务端在主线程解析玩家相关内容，再将结果发给客户端；原物品不因此写入展示数据，也不会将脚本交给客户端执行。支持背包、装备、掉落实体物品数据及 Bundle 包。显示回传校验按连接隔离。

插件不每 tick 主动刷新全部背包。每连接显示队列有界，过载或超时会回退原始显示；队列上限不能抢占任意 JS/Java 回调，也不是全服 CPU 或带宽配额。登录早期的原始同步可能先到达，不能保证完全无闪烁。普通静态名称/Lore 可以继续保存在服务端物品上，无需为了接入本插件改成网络展示。

## 可选原版使用限制

在当前输入目录的 `config.yml` 中主动添加规则，未配置时保持原行为，无需改写已有物品文件：

```yaml
ItemLoom:
  UsageRestrictions:
    ExampleGem:
      crafting: false
      enchanting: false
      placement: false
```

键是物品实际存储的 ItemLoom/NI ID；`false` 禁止该操作，`true` 或缺省允许。完整 reload 验证并发布规则，字段拼写或布尔值错误会取消重载。已有物品立即按新规则判断，不需要重新生成。

- `crafting`：2×2、3×3 和自动合成器；清除受限结果并在取出/执行时再次检查当前原料。
- `enchanting`：原版附魔台的准备和提交，不包含铁砧、磨石或第三方强化系统。
- `placement`：玩家放置方块，包含主手和副手；不代表限制所有物品交互或发射器行为。

允许把物品移动到槽位，但实际操作会被阻止；不取消通用拖拽或箱子菜单点击。无身份的物品（例如移除了身份 NBT，或外部 provider 返回普通原版物品）无法按此规则识别。其他插件主动绕过原版事件或取消本插件的取消结果，不属于这层限制的保证范围。真实客户端和第三方菜单仍待联动验收。

## 独立 Java API

调用方声明依赖 ItemLoom，通过 Bukkit ServicesManager 获取 `dev.itemloom.api.ItemLoom`。公开接口不引用 NI 兼容类。2.0.0 更换了包名、服务类型和数据命名空间，旧独立 API 的调用方必须更新依赖并重新编译；仅更改配置里的插件名称不能完成二进制迁移。

```java
ItemLoom api = Bukkit.getServicesManager().load(ItemLoom.class);
ProviderRegistration registration = api.registerProvider(this, "myplugin",
    new ItemProvider(Map.of("token", context -> {
        OfflinePlayer viewer = context.get(ItemContext.VIEWER);
        String quality = context.rolls().getOrDefault("quality", "common");
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> meta.displayName(Component.text(quality)));
        return item;
    }), Map.of("pair", List.of("token", "token"))));

ItemStack one = api.create("myplugin:token", player, Map.of("quality", "rare"));
List<ItemStack> pair = api.createGroup("myplugin:pair", player, Map.of());
// 主线程执行；也会在 owner 停用时自动清理。
registration.close();
```

- 注册时复制不可变目录和组列表；完整 ID 为 `namespace:localId`。命名空间只能用小写字母、数字、下划线、点和连字符；局部 ID 允许大小写字母、数字、下划线、点、斜杠和连字符。组内只引用同一 provider 的局部 ID，允许重复。
- 不允许重复命名空间或与配置物品冲突；配置 reload 出现冲突时保留旧目录。旧脚本随后动态制造同名冲突时，生成会明确报错，不悄悄替换任一方。
- 每次生成拥有独立 `GenerationContext`，经过独立 `ItemGenerateEvent`。复制 provider 返回栈以及事件返回栈，避免调用方修改共享原型。不会自动添加 ItemLoom 身份，provider 决定其物品协议；不会为这些原生配方伪造 NI 的展开配置和 post-generate 脚本事件。
- `groupIds/createGroup` 用于注册的顺序组；原配置物品包继续使用 `packIds/createPack`，两者语义分别保留。重复组成员各生成一次，不共享随机缓存。
- 配置 reload 保留外部注册；owner 停用、注册句柄关闭或 ItemLoom 关闭会清理。过期句柄不能删除后来的同名注册。递归调用同一 provider 物品会报错。
- `save(item,id,path,replace)` 返回独立 `ItemLoom.SaveResult`，复用安全保存路径；不会覆盖外部 provider 的 ID。只有调用方明确传 `replace=true` 才允许覆盖配置物品。
- `ItemsReloadEvent` 仅在完整配置成功安装后触发，提供 revision 和 initial 标志；失败不触发，不覆盖旧脚本局部目录 reload。监听器内不得递归执行完整 reload。初次加载的事件早于服务注册，调用方应在自身 enable 时获取服务和当前目录。
- 生成、注册、保存、关闭句柄和 live Bukkit 操作要求主线程。`ids/groupIds` 返回不可变集合。原来的 `%ni_*%` 异步读取规则保持不变。

## 战利品袋

配置输入目录根的 `loot-bags.yml` 定义袋子与奖励来源；袋子本身仍是 `Items/` 中的普通物品，也能出现在 MM 的 `itemloom` 掉落中。管理员提供自己的物品配置与外观资源。
例如先在 `Items/bags.yml` 创建不需要资源包的普通纸袋，再用 `/il get ExampleLootBag` 获取：

```yaml
ExampleLootBag:
  material: PAPER
  name: '示例战利品袋'
```

同一袋子在 `pack`、`mythic`、`table`、`loot` 中选一个。引用 NI/ItemLoom 物品包：

```yaml
bags:
  ExampleLootBag:
    enabled: true
    cooldown-ticks: 20
    pack: Example1 # 对应 ItemPacks/ 中的 ID；按实际奖励修改
```

引用 MythicMobs 原生表时，把 `pack` 替换为 `mythic: ExampleDropTable`。MM 负责条件、概率、数量和嵌套表抽取；玩家是 trigger，level 默认 1，没有虚构怪物 dropper。可以包含原生 `itemloom` 物品类型。只接受物品表：包括未抽中的条目在内，含经验、货币、命令或未知多重掉落类型的表会拒绝开袋。缺 MM、表不存在或生成失败都不扣袋。不通过 `mm` 命令发奖。

袋子专属列表直接写 `loot`：

```yaml
bags:
  ExampleLootBag:
    enabled: true
    loot:
      rolls: {min: 1, max: 3}
      entries:
      - {item: 'minecraft:diamond', amount: 1, weight: 3}
      - item: ExampleGem
        amount: {min: 1, max: 2}
        weight: 1
        data: {quality: rare}
```

这只是配置示例，`ExampleGem` 须换成真实物品 ID。每轮按权重选一项，允许重复；本例每轮两项的概率为 75% 和 25%。每轮单独生成一次，再设置该轮数量，同轮多件共享一次随机结果。`data` 值必须是字符串；原版物品用 `minecraft:` 前缀，其他 ID 走 ItemLoom 生成器及已注册 provider。
多个袋子共享同一个自有列表时，将上述 `loot` 内容放到根节点 `tables.材料池`，袋子改写为 `table: 材料池`。

- 自有列表每次 1–64 轮，每项数量 1–64，1–256 个条目，正整数权重最大 1,000,000。所有来源最终最多 4,096 件、256 堆；超限拒绝整次开袋，不截断奖励。
- 袋子引用 NI/ItemLoom 物品包时另有 4,096 步工作预算，包含条目处理、物品生成/复制、拆堆和加权候选遍历；大量生成或复制在进入循环前检查额度，保留奖励也在包执行期间检查数量/堆数。普通 `createPack` 和旧脚本物品包入口不启用这个袋子专用限制。
- MM 表单次校验保留深度 32（根为 0）、单表 256 条限制，并限制整图检查及按引用展开规模为 4,096 次访问。共享子表去重不共享奖励或随机结果，表内容每次开袋重新检查。上述限额不能抢占任意 Java/JS 回调或 MM 自身的动态执行，也不是 CPU 毫秒限额。
- 主手右键开启，包括潜行；副手不打开。默认共享 20 tick 冷却，空结果和失败也限频；至少 1 tick。已有明确物品使用禁令和损坏状态检查仍有效。
- 先生成完整奖励，再核对手持原物品和目录版本，确认后扣一个袋子。空结果不扣袋，所以不要把概率全落空设计成“消耗后无奖励”。普通 ItemActions 的右键行为不会再与袋子奖励叠加。
- 背包余量落在脚边。扣袋后奖励由服务级账本负责，重载不会重新抽取；结果不明的交付停止自动重试并记录诊断。重启遗留进入人工核查，不承诺跨崩溃的恰好一次交付。
- `legacy-id` 是可选的显式兼容标记，对应 `custom_data.itemloom.loot_bag`，只用于无 ItemLoom/NI 身份的 PAPER。其他来源的旧物品必须先由外部迁移程序转换为该标记；插件不会自动识别未经处理的旧物品，不按名称或贴图猜测身份，也不批量改写现有 Lore。
- 保存后执行 `/il reload`。未知物品包/自有表、重复旧 ID、混用来源、非法范围及拼错配置键会拒绝候选并保留旧配置。MM 表和外部 provider 在使用时检查，允许它们稍后完成加载。

新建袋子无需配置 `legacy-id`。自定义外观由管理员在自己的资源包和物品组件中设置，插件发布包只包含运行 JAR 与校验文件。
