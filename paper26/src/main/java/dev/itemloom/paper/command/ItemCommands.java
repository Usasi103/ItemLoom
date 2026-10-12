package dev.itemloom.paper.command;

import static dev.keystone.command.CommandBuilder.argument;
import static dev.keystone.command.CommandBuilder.command;
import static dev.keystone.command.CommandBuilder.literal;

import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.nms.ItemStateCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.item.ItemEntity;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Administrative command entry points and paced generation/delivery jobs. */
public final class ItemCommands implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ItemsService items;
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private boolean closed;

    public ItemCommands(JavaPlugin plugin, ItemsService items) {
        this.plugin = plugin;
        this.items = items;
    }

    public void register() {
        command("itemloom")
                .aliases("il")
                .permission("itemloom.admin", PermissionDefault.OP)
                .executes(
                        (sender, context, value) ->
                                sender.sendMessage(
                                        "ItemLoom: list [页] / search <ID片段> [页] / inspect / save <ID> [文件.yml] / get <ID> [数量] [stack|roll] [参数JSON] / give <玩家> <ID> [数量] [stack|roll] [参数JSON] / reload"))
                .then(
                        literal("reload")
                                .executes(
                                        (sender, context, value) ->
                                                guard(
                                                        sender,
                                                        () -> {
                                                            if (items.reload(sender))
                                                                sender.sendMessage(
                                                                        "ItemLoom 已重载，共 "
                                                                                + items.ids().size()
                                                                                + " 个物品。");
                                                        })),
                        literal("list")
                                .executes(
                                        (sender, context, value) ->
                                                guard(
                                                        sender,
                                                        () -> {
                                                            String[] args = context.rawArgs();
                                                            if (args.length > 2)
                                                                throw new IllegalArgumentException(
                                                                        "用法: /il list [页]");
                                                            directory(
                                                                    sender,
                                                                    "",
                                                                    args.length == 2
                                                                            ? Integer.parseInt(
                                                                                    args[1])
                                                                            : 1);
                                                        })),
                        literal("search")
                                .then(
                                        argument("query")
                                                .executes(
                                                        (sender, context, value) ->
                                                                guard(
                                                                        sender,
                                                                        () -> {
                                                                            String[] args =
                                                                                    value.trim()
                                                                                            .split(
                                                                                                    "\\s+");
                                                                            if (args.length > 2)
                                                                                throw new IllegalArgumentException(
                                                                                        "用法: /il search <ID片段> [页]");
                                                                            directory(
                                                                                    sender,
                                                                                    args[0],
                                                                                    args.length == 2
                                                                                            ? Integer
                                                                                                    .parseInt(
                                                                                                            args[
                                                                                                                    1])
                                                                                            : 1);
                                                                        }))),
                        literal("inspect")
                                .executes(
                                        Player.class,
                                        (player, context, value) ->
                                                guard(player, () -> inspect(player))),
                        literal("save")
                                .then(
                                        argument("id")
                                                .executes(
                                                        Player.class,
                                                        (player, context, value) ->
                                                                guard(
                                                                        player,
                                                                        () ->
                                                                                save(
                                                                                        player,
                                                                                        value)))),
                        literal("get")
                                .then(
                                        argument("id")
                                                .suggestUnchecked(
                                                        (sender, context) ->
                                                                List.copyOf(items.ids()))
                                                .executes(
                                                        Player.class,
                                                        (player, context, value) ->
                                                                guard(
                                                                        player,
                                                                        () ->
                                                                                respond(
                                                                                        player,
                                                                                        player,
                                                                                        GiveRequest
                                                                                                .parse(
                                                                                                        value))))),
                        literal("give")
                                .then(
                                        argument("player")
                                                .suggestPlayers()
                                                .then(
                                                        argument("id")
                                                                .suggestUnchecked(
                                                                        (sender, context) ->
                                                                                List.copyOf(
                                                                                        items
                                                                                                .ids()))
                                                                .executes(
                                                                        (sender, context, value) ->
                                                                                guard(
                                                                                        sender,
                                                                                        () -> {
                                                                                            Player
                                                                                                    player =
                                                                                                            Bukkit
                                                                                                                    .getPlayerExact(
                                                                                                                            context
                                                                                                                                    .get(
                                                                                                                                            "player"));
                                                                                            if (player
                                                                                                    == null)
                                                                                                throw new IllegalArgumentException(
                                                                                                        "玩家不在线。");
                                                                                            respond(
                                                                                                    sender,
                                                                                                    player,
                                                                                                    GiveRequest
                                                                                                            .parse(
                                                                                                                    value));
                                                                                        })))))
                .register();
    }

    private static void guard(CommandSender sender, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            sender.sendMessage("操作失败：" + failure.getMessage());
        }
    }

    private void directory(CommandSender sender, String query, int page) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<String> matches =
                items.ids().stream()
                        .filter(id -> id.toLowerCase(Locale.ROOT).contains(needle))
                        .sorted()
                        .toList();
        int pages = Math.max(1, (matches.size() + 19) / 20);
        if (page < 1 || page > pages) throw new IllegalArgumentException("页码范围: 1–" + pages);
        sender.sendMessage("ItemLoom: " + matches.size() + " 个匹配，第 " + page + "/" + pages + " 页");
        for (int index = (page - 1) * 20; index < Math.min(page * 20, matches.size()); index++) {
            String id = matches.get(index);
            sender.sendMessage(
                    Component.text(id).clickEvent(ClickEvent.suggestCommand("/il get " + id)));
        }
    }

    private void inspect(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) throw new IllegalArgumentException("主手没有物品");
        var handle = CraftItemStack.asNMSCopy(item);
        var ops = CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE);
        String snbt =
                net.minecraft.world.item.ItemStack.CODEC
                        .encodeStart(ops, handle)
                        .getOrThrow()
                        .toString();
        String id = new ItemStateCodec().readId(handle);
        player.sendMessage(
                item.getType()
                        + " × "
                        + item.getAmount()
                        + "; ID: "
                        + (id == null ? "无 ItemLoom/NI 标识" : id));
        if (snbt.length() <= 32000)
            player.sendMessage(
                    Component.text("[点击复制完整组件与 NBT]").clickEvent(ClickEvent.copyToClipboard(snbt)));
        player.sendMessage(snbt.substring(0, Math.min(4096, snbt.length())));
        if (snbt.length() > 4096)
            player.sendMessage(
                    "显示已截断；可用 /il save 保存完整状态。" + (snbt.length() > 32000 ? " 数据过大，未创建复制按钮。" : ""));
    }

    private void save(Player player, String arguments) {
        String[] args = arguments.trim().split("\\s+");
        if (args.length > 2) throw new IllegalArgumentException("用法: /il save <ID> [文件.yml]");
        String path = args.length == 2 ? args[1] : "saved.yml";
        String message =
                switch (items.save(
                        player.getInventory().getItemInMainHand(), args[0], path, false)) {
                    case SUCCESS -> "已保存当前物品状态为 " + args[0] + "（Items/" + path + "）。";
                    case AIR -> "主手没有物品。";
                    case CONFLICT -> "该 ID 已存在，请使用新 ID。";
                };
        player.sendMessage(message);
    }

    private void respond(CommandSender sender, Player player, GiveRequest request) {
        give(player, request)
                .whenComplete(
                        (count, failure) -> {
                            if (failure == null)
                                sender.sendMessage(
                                        "已发放 "
                                                + count
                                                + " 件给 "
                                                + player.getName()
                                                + "；背包放不下的物品掉落在脚下。");
                            else sender.sendMessage("发放失败：" + failure.getMessage());
                        });
    }

    public CompletionStage<Integer> give(Player player, GiveRequest request) {
        ItemsService.requireThread();
        if (closed) throw new IllegalStateException("物品命令已关闭");
        if (jobs.size() >= 8 || jobs.containsKey(player.getUniqueId()))
            throw new IllegalStateException("有未完成的发放任务，请稍后再试");
        if (!items.ids().contains(request.id()))
            throw new IllegalArgumentException("未知物品: " + request.id());
        Job job = new Job(player, request);
        jobs.put(player.getUniqueId(), job);
        try {
            job.task = Bukkit.getScheduler().runTaskTimer(plugin, job::step, 1L, 1L);
        } catch (RuntimeException failure) {
            job.finish(failure);
        }
        return job.result;
    }

    private final class Job {
        final Player player;
        final GiveRequest request;
        final Object catalog = items.placeholderRevision();
        final Object providers = items.providerRevision();
        final List<ItemStack> generated = new ArrayList<>();
        final CompletableFuture<Integer> result = new CompletableFuture<>();
        BukkitTask task;
        int delivered;
        boolean stepping;
        RuntimeException cancellation;

        Job(Player player, GiveRequest request) {
            this.player = player;
            this.request = request;
        }

        void step() {
            if (result.isDone()) return;
            stepping = true;
            Throwable failure = null;
            boolean complete = false;
            try {
                ready();
                ItemStack prototype = items.create(request.id(), player, request.parameters());
                ready();
                if (prototype == null || prototype.isEmpty())
                    throw new IllegalStateException("生成器返回空物品");
                ItemStack owned = prototype.clone();
                if (request.independent()) {
                    owned.setAmount(1);
                    generated.add(owned);
                    if (generated.size() < request.count()) return;
                } else {
                    int remaining = request.count();
                    while (remaining > 0) {
                        ItemStack part = owned.clone();
                        int count = Math.min(remaining, Math.max(1, owned.getMaxStackSize()));
                        part.setAmount(count);
                        generated.add(part);
                        remaining -= count;
                    }
                }
                for (ItemStack item : generated) deliver(item);
                complete = true;
            } catch (RuntimeException error) {
                failure = error;
            } finally {
                stepping = false;
                if (failure != null || complete || cancellation != null)
                    finish(failure != null ? failure : cancellation);
            }
        }

        private void deliver(ItemStack item) {
            ready();
            int amount = item.getAmount();
            var overflow = player.getInventory().addItem(item);
            int remainder = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
            delivered += amount - remainder;
            ready();
            for (ItemStack excess : overflow.values()) {
                ready();
                var location = player.getLocation();
                var level = ((CraftWorld) location.getWorld()).getHandle();
                ItemEntity entity =
                        new ItemEntity(
                                level,
                                location.getX(),
                                location.getY(),
                                location.getZ(),
                                CraftItemStack.asNMSCopy(excess));
                entity.setTarget(player.getUniqueId());
                entity.setDefaultPickUpDelay();
                boolean inserted =
                        level.addFreshEntity(entity, CreatureSpawnEvent.SpawnReason.CUSTOM);
                if (inserted) delivered += excess.getAmount();
                if (!inserted) throw new IllegalStateException("背包余量掉落被取消");
                ready();
            }
        }

        private void ready() {
            if (cancellation != null) throw cancellation;
            if (closed || !plugin.isEnabled() || !player.isOnline())
                throw new IllegalStateException("目标离线或插件已关闭，任务取消");
            if (catalog != items.placeholderRevision() || providers != items.providerRevision()) {
                throw new IllegalStateException("物品目录已变化，任务取消；请重新执行");
            }
        }

        private void finish(Throwable failure) {
            if (result.isDone()) return;
            if (task != null) task.cancel();
            jobs.remove(player.getUniqueId(), this);
            generated.clear();
            if (failure == null) result.complete(request.count());
            else
                result.completeExceptionally(
                        new IllegalStateException(
                                failure.getMessage() + "；已确认发放 " + delivered + " 件，请核对后处理余量。",
                                failure));
        }

        private void cancel() {
            cancellation = new IllegalStateException("插件关闭，发放任务取消");
            if (!stepping) finish(cancellation);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Job job : List.copyOf(jobs.values())) job.cancel();
    }
}
