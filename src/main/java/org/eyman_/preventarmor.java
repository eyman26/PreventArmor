package org.eyman_;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * Punishes players for wearing armor: a repeating task damages anyone with a
 * non-exempt item in an armor slot (and can optionally strip it off them).
 */
public class preventarmor extends JavaPlugin {
    private static final String RELOAD_PERMISSION = "preventarmor.reload";

    private double damageAmount;
    private Component warningMessage;
    private boolean removeArmor;
    private long checkInterval;
    private boolean blockSpecific;
    private Set<Material> blockedArmor;
    private NamespacedKey exemptNbtKey; // null when the NBT exemption is off or misconfigured
    private String exemptNbtValue;
    private Set<String> exemptLore; // lowercase plain text; empty when the lore exemption is off
    private DamageSource penaltySource; // null means "fall back to plain damage()"
    private BukkitTask armorTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        penaltySource = createPenaltySource();
        loadConfigValues();
        startArmorTask();
        getLogger().info("PreventArmor enabled! - made by Eyman");
    }

    @Override
    public void onDisable() {
        if (armorTask != null) {
            armorTask.cancel();
        }
        getLogger().info("PreventArmor disabled!");
    }

    private DamageSource createPenaltySource() {
        try {
            // STARVE is in the bypasses_armor and bypasses_effects damage-type tags, so armor points,
            // protection enchantments and Resistance can't shrink the penalty. Trade-off: the death
            // message reads "starved to death". DamageType.MAGIC says "killed by magic" instead, but
            // Protection enchantments and Resistance still reduce it.
            return DamageSource.builder(DamageType.STARVE).build();
        } catch (LinkageError | RuntimeException e) {
            getLogger().warning("DamageSource API unavailable on this server, using plain damage instead.");
            return null;
        }
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();

        damageAmount = config.getDouble("damage-amount", 2.0);
        if (damageAmount < 0) {
            getLogger().warning("damage-amount can't be negative, using 0 (no damage).");
            damageAmount = 0;
        }

        checkInterval = config.getLong("check-interval", 20L);
        if (checkInterval < 1) {
            getLogger().warning("check-interval must be at least 1 tick, using 1.");
            checkInterval = 1;
        }

        warningMessage = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(config.getString("warning-message", "&cArmor is forbidden!"));
        removeArmor = config.getBoolean("remove-armor", false);
        blockSpecific = config.getBoolean("block-specific", false);

        blockedArmor = new HashSet<>();
        for (String name : config.getStringList("blocked-armor")) {
            // Locale.ROOT: under a Turkish locale "diamond_helmet".toUpperCase() becomes "DİAMOND_HELMET"
            Material material = Material.getMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                getLogger().warning("Invalid material in blocked-armor: " + name);
            } else {
                blockedArmor.add(material);
            }
        }
        if (blockSpecific && blockedArmor.isEmpty()) {
            getLogger().warning("block-specific is on but blocked-armor has no valid materials, so nothing is blocked.");
        }

        exemptNbtKey = null;
        if (config.getBoolean("nbt-exempt.enabled", false)) {
            String key = config.getString("nbt-exempt.key", "armor_exempt");
            try {
                exemptNbtKey = new NamespacedKey(this, key);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid nbt-exempt.key '" + key
                        + "' (only letters, digits and . _ - / are allowed). NBT exemption disabled.");
            }
        }
        exemptNbtValue = config.getString("nbt-exempt.value", "true");

        exemptLore = new HashSet<>();
        if (config.getBoolean("lore-exempt.enabled", false)) {
            for (String line : config.getStringList("lore-exempt.lines")) {
                String normalized = line.trim().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    // A blank entry would exempt every item that has an empty lore line
                    getLogger().warning("Ignoring blank entry in lore-exempt.lines.");
                } else {
                    exemptLore.add(normalized);
                }
            }
        }
    }

    private void startArmorTask() {
        // Cancel first, otherwise every reload would stack another task on top of the old one
        if (armorTask != null) {
            armorTask.cancel();
        }
        armorTask = getServer().getScheduler().runTaskTimer(this, this::checkPlayers, 0L, checkInterval);
    }

    private void checkPlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            // Neither mode can take damage, so punishing them would only strip their armor for nothing
            GameMode mode = player.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
                continue;
            }
            checkPlayer(player);
        }
    }

    private void checkPlayer(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean violation = false;
        boolean armorRemoved = false;

        for (int i = 0; i < armor.length; i++) {
            ItemStack item = armor[i];
            if (item == null || item.getType() == Material.AIR || !isBlocked(item) || isExempt(item)) {
                continue;
            }

            violation = true;
            if (removeArmor) {
                player.getWorld().dropItemNaturally(player.getLocation(), item.clone());
                armor[i] = null;
                armorRemoved = true;
            }
        }

        // Inventory#remove(ItemStack) never looks at armor slots, so write the edited array back instead
        if (armorRemoved) {
            player.getInventory().setArmorContents(armor);
        }
        if (violation) {
            applyPenalty(player);
        }
    }

    private boolean isBlocked(ItemStack item) {
        // With block-specific off, ANYTHING in an armor slot counts
        // (elytra, carved pumpkins, mob heads...), not just real armor.
        return !blockSpecific || blockedArmor.contains(item.getType());
    }

    private void applyPenalty(Player player) {
        if (damageAmount > 0) {
            if (penaltySource != null) {
                player.damage(damageAmount, penaltySource);
            } else {
                player.damage(damageAmount);
            }
        }
        player.sendActionBar(warningMessage);
    }

    private boolean isExempt(ItemStack item) {
        if (exemptNbtKey == null && exemptLore.isEmpty()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && (hasExemptTag(meta) || hasExemptLore(meta));
    }

    private boolean hasExemptTag(ItemMeta meta) {
        if (exemptNbtKey == null) {
            return false;
        }
        // The tag lives under this plugin's namespace, so on the item it is "preventarmor:<key>".
        // has() before get(): get() throws if the tag exists but isn't a string.
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (!data.has(exemptNbtKey, PersistentDataType.STRING)) {
            return false;
        }
        String value = data.get(exemptNbtKey, PersistentDataType.STRING);
        return value != null && value.equalsIgnoreCase(exemptNbtValue);
    }

    private boolean hasExemptLore(ItemMeta meta) {
        List<Component> lore = meta.lore();
        if (exemptLore.isEmpty() || lore == null) {
            return false;
        }
        for (Component line : lore) {
            // Plain text drops all colors and formatting, so a colored lore line still matches
            String text = PlainTextComponentSerializer.plainText().serialize(line).trim().toLowerCase(Locale.ROOT);
            if (exemptLore.contains(text)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /preventarmor reload", NamedTextColor.YELLOW));
            return true;
        }
        if (!args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(Component.text("Unknown subcommand. Use: /preventarmor reload", NamedTextColor.RED));
            return true;
        }

        reloadConfig();
        loadConfigValues();
        // check-interval is fixed when the timer is scheduled, so the task has to be restarted too
        startArmorTask();
        sender.sendMessage(Component.text("PreventArmor config reloaded!", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission(RELOAD_PERMISSION)
                && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("reload");
        }
        return List.of();
    }
}
