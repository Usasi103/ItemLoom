package dev.itemloom.paper.command;

import static dev.keystone.command.CommandBuilder.*;

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
import dev.itemloom.paper.ItemsService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Administrator operations with bounded generation and explicit full-inventory handling. */
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
                        (sender, context, argument) ->
                                sender.sendMessage(
                                        "ItemLoom: list [页] / search <ID片段> [页] / inspect / save <ID> [文件.yml] / get <ID> [数量] [stack|roll] [参数JSON] / give <玩家> <ID> [数量] [stack|roll] [参数JSON] / reload"))
                .then(
                        literal("reload")
                                .executes(
                                        (sender, context, argument) ->
                                                guarded(
                                                        sender,
                                                        () -> {
                                                            if (items.reload(sender))
                                                                sender.sendMessage(
                                                                        "ItemLoom 已重载，共 "
                                                                                + items.ids().size()
                                                                                + " 个物品。");
                                                        })))
                .then(
                        literal("list")
                                .executes(
                                        (sender, context, argument) ->
                                                guarded(
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
                                                        })))
                .then(
                        literal("search")
                                .then(
                                        argument("query")
                                                .executes(
                                                        (sender, context, argument) ->
                                                                guarded(
                                                                        sender,
                                                                        () -> {
                                                                            String[] parts =
                                                                                    argument.split(
                                                                                            "\\s+");
                                                                            if (parts.length > 2)
                                                                                throw new IllegalArgumentException(
                                                                                        "用法: /il search <ID片段> [页]");
                                                                            directory(
                                                                                    sender,
                                                                                    parts[0],
                                                                                    parts.length
                                                                                                    == 2
                                                                                            ? Integer
                                                                                                    .parseInt(
                                                                                                            parts[
                                                                                                                    1])
                                                                                            : 1);
                                                                        }))))
                .then(
                        literal("inspect")
                                .executes(
                                        Player.class,
                                        (player, context, argument) ->
                                                guarded(player, () -> inspect(player))))
                .then(
                        literal("save")
                                .then(
                                        argument("id")
                                                .executes(
                                                        Player.class,
                                                        (player, context, argument) ->
                                                                guarded(
                                                                        player,
                                                                        () -> {
                                                                            String[] parts =
                                                                                    argument.split(
                                                                                            "\\s+");
                                                                            if (parts.length > 2)
                                                                                throw new IllegalArgumentException(
                                                                                        "用法: /il save <ID> [文件.yml]");
                                                                            String path =
                                                                                    parts.length
                                                                                                    == 2
                                                                                            ? parts[
                                                                                                    1]
                                                                                            : "saved.yml";
                                                                            var result =
                                                                                    items.save(
                                                                                            player.getInventory()
                                                                                                    .getItemInMainHand(),
                                                                                            parts[
                                                                                                    0],
                                                                                            path,
                                                                                            false);
                                                                            player.sendMessage(
                                                                                    switch (result) {
                                                                                        case SUCCESS ->
                                                                                                "已保存当前物品状态为 "
                                                                                                        + parts[
                                                                                                                0]
                                                                                                        + "（Items/"
                                                                                                        + path
                                                                                                        + "）。";
                                                                                        case AIR ->
                                                                                                "主手没有物品。";
                                                                                        case CONFLICT ->
                                                                                                "该 ID 已存在，请使用新 ID。";
                                                                                    });
                                                                        }))))
                .then(
                        literal("get")
                                .then(
                                        argument("id")
                                                .suggestUnchecked(
                                                        (sender, context) ->
                                                                new ArrayList<>(items.ids()))
                                                .executes(
                                                        Player.class,
                                                        (player, context, argument) ->
                                                                giveCommand(
                                                                        player, player, argument))))
                .then(
                        literal("give")
                                .then(
                                        argument("player")
                                                .suggestPlayers()
                                                .then(
                                                        argument("id")
                                                                .suggestUnchecked(
                                                                        (sender, context) ->
                                                                                new ArrayList<>(
                                                                                        items
                                                                                                .ids()))
                                                                .executes(
                                                                        (sender,
                                                                                context,
                                                                                argument) -> {
                                                                            Player target =
                                                                                    Bukkit
                                                                                            .getPlayerExact(
                                                                                                    context
                                                                                                            .get(
                                                                                                                    "player"));
                                                                            if (target == null)
                                                                                sender.sendMessage(
                                                                                        "玩家不在线。");
                                                                            else
                                                                                giveCommand(
                                                                                        sender,
                                                                                        target,
                                                                                        argument);
                                                                        }))))
                .register();
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
        for (String id :
                matches.subList(
                        Math.min((page - 1) * 20, matches.size()),
                        Math.min(page * 20, matches.size())))
            sender.sendMessage(
                    Component.text(id).clickEvent(ClickEvent.suggestCommand("/il get " + id)));
    }

    private void inspect(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) throw new IllegalArgumentException("主手没有物品");
        String snbt =
                net.minecraft.world.item.ItemStack.CODEC
                        .encodeStart(
                                CraftRegistry.getMinecraftRegistry()
                                        .createSerializationContext(NbtOps.INSTANCE),
                                CraftItemStack.asNMSCopy(item))
                        .getOrThrow()
                        .toString();
        player.sendMessage(
                item.getType()
                        + " × "
                        + item.getAmount()
                        + "; ID: "
                        + items.identify(item)
                                .map(identity -> identity.id())
                                .orElse("无 ItemLoom/NI 标识"));
        if (snbt.length() <= 32_000)
            player.sendMessage(
                    Component.text("[点击复制完整组件与 NBT]").clickEvent(ClickEvent.copyToClipboard(snbt)));
        player.sendMessage(Component.text(snbt.substring(0, Math.min(4096, snbt.length()))));
        if (snbt.length() > 4096)
            player.sendMessage(
                    "显示已截断；可用 /il save 保存完整状态。" + (snbt.length() > 32_000 ? " 数据过大，未创建复制按钮。" : ""));
    }

    private void giveCommand(CommandSender sender, Player target, String arguments) {
        guarded(
                sender,
                () -> {
                    GiveRequest request = GiveRequest.parse(arguments);
                    give(target, request)
                            .whenComplete(
                                    (count, error) -> {
                                        if (error == null)
                                            sender.sendMessage(
                                                    "已发放 "
                                                            + count
                                                            + " 件给 "
                                                            + target.getName()
                                                            + "；背包放不下的物品掉落在脚下。");
                                        else sender.sendMessage("发放失败：" + error.getMessage());
                                    });
                });
    }

    /** Generates first, at most one independent item per tick per request; delivery follows success. */
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
            job.task = Bukkit.getScheduler().runTaskTimer(plugin, job::step, 1, 1);
        } catch (RuntimeException error) {
            jobs.remove(player.getUniqueId());
            job.result.completeExceptionally(error);
        }
        return job.result;
    }

    private final class Job {
        final Player player;
        final GiveRequest request;
        final Object catalog = items.placeholderRevision(), providers = items.providerRevision();
        final List<ItemStack> generated = new ArrayList<>();
        final CompletableFuture<Integer> result = new CompletableFuture<>();
        BukkitTask task;
        int delivered;
        boolean stepping;

        Job(Player player, GiveRequest request) {
            this.player = player;
            this.request = request;
        }

        void step() {
            stepping = true;
            try {
                ready();
                ItemStack item = items.create(request.id(), player, request.parameters());
                if (item == null || item.isEmpty()) throw new IllegalStateException("生成器返回空物品");
                item = item.clone();
                ready();
                if (request.independent()) {
                    item.setAmount(1);
                    generated.add(item);
                } else
                    for (int remaining = request.count(); remaining > 0; ) {
                        ItemStack part = item.clone();
                        part.setAmount(Math.min(remaining, item.getMaxStackSize()));
                        generated.add(part);
                        remaining -= part.getAmount();
                    }
                if (request.independent() && generated.size() < request.count()) return;
                for (ItemStack part : generated) {
                    ready();
                    int count = part.getAmount();
                    var leftovers = player.getInventory().addItem(part).values();
                    delivered += count - leftovers.stream().mapToInt(ItemStack::getAmount).sum();
                    for (ItemStack overflow : leftovers) {
                        ready();
                        if (!dropOverflow(player, overflow))
                            throw new IllegalStateException("背包余量掉落被取消");
                        delivered += overflow.getAmount();
                    }
                }
                ready();
                complete(null);
            } catch (RuntimeException error) {
                complete(
                        new IllegalStateException(
                                error.getMessage() + "；已确认发放 " + delivered + " 件，请核对后处理余量。",
                                error));
            } finally {
                stepping = false;
            }
        }

        void ready() {
            if (closed || !player.isOnline()) throw new IllegalStateException("目标离线或插件已关闭，任务取消");
            if (catalog != items.placeholderRevision() || providers != items.providerRevision())
                throw new IllegalStateException("物品目录已变化，任务取消；请重新执行");
        }

        void complete(Throwable error) {
            if (task != null) task.cancel();
            jobs.remove(player.getUniqueId(), this);
            generated.clear();
            if (error == null) result.complete(request.count());
            else result.completeExceptionally(error);
        }

        void cancel() {
            if (task != null) task.cancel();
            // A spawn listener can close us inside this step. Let the step account for that
            // insertion and stop at its next fence, rather than clearing its live iteration.
            if (!stepping) complete(new IllegalStateException("插件关闭，发放任务取消"));
        }
    }

    private static boolean dropOverflow(Player player, ItemStack stack) {
        var location = player.getLocation();
        var world = ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle();
        var entity =
                new net.minecraft.world.entity.item.ItemEntity(
                        world,
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        CraftItemStack.asNMSCopy(stack));
        entity.setDefaultPickUpDelay();
        ((org.bukkit.entity.Item) entity.getBukkitEntity()).setOwner(player.getUniqueId());
        // Paper's insertion result includes ItemSpawnEvent cancellation. isValid also depends
        // on chunk tracking and cannot distinguish a rejected spawn from an untracked entity.
        return world.addFreshEntity(
                entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
    }

    private void guarded(CommandSender sender, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            sender.sendMessage("操作失败：" + error.getMessage());
        }
    }

    @Override
    public void close() {
        ItemsService.requireThread();
        closed = true;
        List.copyOf(jobs.values()).forEach(Job::cancel);
    }
}
