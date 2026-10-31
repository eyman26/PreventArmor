package org.eyman_.preventarmor;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Punishes (or, with block-equip, outright refuses) armor a player isn't allowed to wear.
 * <p>
 * Every check runs on the player's own entity scheduler, which is the player's region thread on
 * Folia and the main thread on Paper, so one code path serves both.
 */
public final class PreventArmor extends JavaPlugin implements Listener {

    private static final String RELOAD_PERMISSION = "preventarmor.reload";
    private volatile ArmorPolicy policy;
    private DamageSource penalty;
    private final Map<UUID, ScheduledTask> checks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // STARVE bypasses armor, enchantments and Resistance. Trade-off: death message reads "starved to death".
        penalty = DamageSource.builder(DamageType.STARVE).build();
        policy = ArmorPolicy.load(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getOnlinePlayers().forEach(this::scheduleChecks);
    }

    @Override
    public void onDisable() {
        checks.values().forEach(ScheduledTask::cancel);
        checks.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        scheduleChecks(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ScheduledTask task = checks.remove(event.getPlayer().getUniqueId());
        if (task != null) task.cancel();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        ArmorPolicy current = policy;
        if (!current.blockEquip() || event.getNewItem().isEmpty()) return;
        Player player = event.getPlayer();
        // The slot is still mid-update while this fires; strip on the next tick of the player's own scheduler.
        player.getScheduler().run(this, task -> strip(player, policy), null);
    }

    private void scheduleChecks(Player player) {
        ScheduledTask task = player.getScheduler().runAtFixedRate(this, t -> check(player), null, 1L, policy.interval());
        ScheduledTask previous = task == null ? checks.remove(player.getUniqueId()) : checks.put(player.getUniqueId(), task);
        if (previous != null) previous.cancel();
    }

    private void check(Player player) {
        ArmorPolicy current = policy;
        if (current.blockEquip()) {
            strip(player, current);
            return;
        }
        if (isImmune(player)) return;

        PlayerInventory inventory = player.getInventory();
        boolean violation = false;
        for (EquipmentSlot slot : ArmorPolicy.ARMOR_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item.isEmpty() || current.allows(player, item, slot)) continue;

            violation = true;
            if (current.removeArmor()) {
                ItemStack removed = item.clone();
                inventory.setItem(slot, null);
                player.getWorld().dropItemNaturally(player.getLocation(), removed);
            }
        }
        if (!violation) return;

        if (current.damage() > 0) player.damage(current.damage(), penalty);
        player.sendActionBar(current.warning());
    }

    /** Moves every piece the player may not wear back into their inventory (or to the ground if it's full). */
    private void strip(Player player, ArmorPolicy current) {
        if (!player.isOnline() || isImmune(player)) return;

        PlayerInventory inventory = player.getInventory();
        boolean stripped = false;
        for (EquipmentSlot slot : ArmorPolicy.ARMOR_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item.isEmpty() || current.allows(player, item, slot)) continue;

            ItemStack removed = item.clone();
            inventory.setItem(slot, null);
            for (ItemStack overflow : inventory.addItem(removed).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
            stripped = true;
        }
        if (stripped) player.sendActionBar(current.equipDenied());
    }

    private static boolean isImmune(Player player) {
        // Neither mode can take damage, so punishing them would only strip their armor for nothing.
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(Component.text("Usage: /preventarmor reload", NamedTextColor.YELLOW));
            return true;
        }

        reloadConfig();
        policy = ArmorPolicy.load(this);
        // check-interval is baked into each repeating task, so they all have to be rescheduled.
        getServer().getOnlinePlayers().forEach(this::scheduleChecks);
        sender.sendMessage(Component.text("PreventArmor config reloaded!", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission(RELOAD_PERMISSION) && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("reload");
        }
        return List.of();
    }
}
