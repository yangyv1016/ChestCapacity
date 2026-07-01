package com.redstone.chestcapacity;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * /chestcap 指令。定位：发放与个别调整，不承载日常玩法（日常靠放置+悬停+GUI）。
 *
 *   give <页数> [数量]  给自己一个扩容箱物品（放下即生效）
 *   get                 查看手持扩容箱的容量
 *   set  <页数>         调整手持扩容箱的容量（同步刷新 NBT 与 lore）
 *
 * 一律作用于“手持物品”，不直接改世界里的方块（放置驱动，避免依赖指令）。
 */
public final class ChestCommand implements CommandExecutor, TabCompleter {

    private final PluginConfig config;
    private final ChestItems items;

    public ChestCommand(PluginConfig config, ChestItems items) {
        this.config = config;
        this.items = items;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { help(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "give" -> handleGive(sender, args);
            case "get"  -> handleGet(sender);
            case "set"  -> handleSet(sender, args);
            default     -> help(sender);
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { onlyPlayer(sender); return; }
        int pages = args.length >= 2 ? parsePages(args[1]) : config.defaultPages;
        if (pages < 0) { msg(sender, "&c页数需为 1~" + config.maxPages + " 的整数。"); return; }
        int amount = args.length >= 3 ? Math.max(1, parseInt(args[2], 1)) : 1;

        ItemStack item = items.create(pages, amount);
        player.getInventory().addItem(item).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        msg(sender, "&a已发放 &f" + amount + " &a个 &f" + pages + " &a页扩容箱。");
    }

    private void handleGet(CommandSender sender) {
        if (!(sender instanceof Player player)) { onlyPlayer(sender); return; }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!items.isChestItem(hand)) { msg(sender, "&c请手持一个扩容箱物品。"); return; }
        int pages = items.readPages(hand, config.defaultPages);
        msg(sender, "&e手持扩容箱: &f" + pages + " 页 &7(共 "
                + (pages * PluginConfig.SLOTS_PER_PAGE) + " 格)");
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { onlyPlayer(sender); return; }
        if (args.length < 2) { msg(sender, "&c用法: /chestcap set <页数>"); return; }
        int pages = parsePages(args[1]);
        if (pages < 0) { msg(sender, "&c页数需为 1~" + config.maxPages + " 的整数。"); return; }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!items.isChestItem(hand)) { msg(sender, "&c请手持一个扩容箱物品。"); return; }
        items.applyMeta(hand, pages);              // 刷新 NBT + name + lore
        msg(sender, "&a已把手持扩容箱设为 &f" + pages + " &a页。");
    }

    /** 返回合法页数，非法返回 -1。 */
    private int parsePages(String s) {
        int v = parseInt(s, -1);
        if (v < 1 || v > config.maxPages) return -1;
        return v;
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private void help(CommandSender sender) {
        msg(sender, "&6ChestCapacity &7指令:");
        msg(sender, "&e/chestcap give <页数> [数量] &7获得扩容箱(放下即生效)");
        msg(sender, "&e/chestcap get &7查看手持扩容箱容量");
        msg(sender, "&e/chestcap set <页数> &7调整手持扩容箱容量");
    }

    private void onlyPlayer(CommandSender sender) { msg(sender, "&c该指令只能由玩家使用。"); }

    private void msg(CommandSender sender, String legacy) {
        sender.sendMessage(PluginConfig.text(legacy));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String sub : List.of("give", "get", "set")) {
                if (sub.startsWith(args[0].toLowerCase())) out.add(sub);
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give")
                || args[0].equalsIgnoreCase("set"))) {
            return List.of("1", "3", "6", Integer.toString(config.maxPages));
        }
        return List.of();
    }
}