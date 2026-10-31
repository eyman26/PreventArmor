package org.eyman_.preventarmor;

import io.papermc.paper.persistence.PersistentDataContainerView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Immutable snapshot of config.yml plus the "may this player wear this piece" decision.
 * A reload swaps the whole object, so checks running on other region threads never see a half-loaded config.
 */
record ArmorPolicy(
        double damage,
        Component warning,
        Component equipDenied,
        boolean removeArmor,
        boolean blockEquip,
        long interval,
        boolean blockSpecific,
        Set<Material> blockedArmor,
        NamespacedKey exemptKey,
        String exemptValue,
        Set<String> exemptLore,
        boolean perPiecePermissions,
        boolean whitelistEnabled,
        Set<UUID> whitelistIds,
        Set<String> whitelistNames,
        List<String> whitelistNodes
) {

    static final String BYPASS = "preventarmor.bypass";
    static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static final Map<EquipmentSlot, String> SLOT_NODES = new EnumMap<>(Map.of(
            EquipmentSlot.HEAD, "preventarmor.wear.head",
            EquipmentSlot.CHEST, "preventarmor.wear.chest",
            EquipmentSlot.LEGS, "preventarmor.wear.legs",
            EquipmentSlot.FEET, "preventarmor.wear.feet"
    ));
    private static final Map<Material, String> MATERIAL_NODES = new ConcurrentHashMap<>();

    boolean allows(Player player, ItemStack item, EquipmentSlot slot) {
        if (!isBlocked(item) || isExempt(item)) return true;
        if (player.hasPermission(BYPASS)) return true;
        if (whitelistEnabled && !isWhitelisted(player)) return false;
        if (perPiecePermissions) {
            return player.hasPermission(SLOT_NODES.get(slot)) || player.hasPermission(materialNode(item.getType()));
        }
        // Whitelisted (or the whitelist is off and no permission rule granted it).
        return whitelistEnabled;
    }

    private boolean isBlocked(ItemStack item) {
        // With block-specific off, ANYTHING in an armor slot counts (elytra, pumpkins, heads...).
        return !blockSpecific || blockedArmor.contains(item.getType());
    }

    private boolean isWhitelisted(Player player) {
        if (whitelistIds.contains(player.getUniqueId())) return true;
        if (!whitelistNames.isEmpty() && whitelistNames.contains(player.getName().toLowerCase(Locale.ROOT))) return true;
        for (String node : whitelistNodes) {
            if (player.hasPermission(node)) return true;
        }
        return false;
    }

    private boolean isExempt(ItemStack item) {
        if (exemptKey != null) {
            // Read-only view: no ItemMeta clone per check. has() first since get() throws on a non-string tag.
            PersistentDataContainerView data = item.getPersistentDataContainer();
            if (data.has(exemptKey, PersistentDataType.STRING) && exemptValue.equalsIgnoreCase(data.get(exemptKey, PersistentDataType.STRING))) {
                return true;
            }
        }
        if (!exemptLore.isEmpty()) {
            List<Component> lore = item.lore();
            if (lore != null) {
                for (Component line : lore) {
                    String text = PlainTextComponentSerializer.plainText().serialize(line).trim().toLowerCase(Locale.ROOT);
                    if (exemptLore.contains(text)) return true;
                }
            }
        }
        return false;
    }

    private static String materialNode(Material material) {
        return MATERIAL_NODES.computeIfAbsent(material, m -> "preventarmor.wear." + m.getKey().getKey());
    }

    static ArmorPolicy load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        Logger log = plugin.getLogger();
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

        double damage = config.getDouble("damage-amount", 2.0);
        if (damage < 0) {
            log.warning("damage-amount can't be negative, using 0 (no damage).");
            damage = 0;
        }
        long interval = config.getLong("check-interval", 20L);
        if (interval < 1) {
            log.warning("check-interval must be at least 1 tick, using 1.");
            interval = 1;
        }

        boolean blockSpecific = config.getBoolean("block-specific", false);
        Set<Material> blocked = EnumSet.noneOf(Material.class);
        for (String name : config.getStringList("blocked-armor")) {
            // Locale.ROOT: under a Turkish locale "diamond_helmet".toUpperCase() becomes "DİAMOND_HELMET"
            Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material == null) log.warning("Invalid material in blocked-armor: " + name);
            else blocked.add(material);
        }
        if (blockSpecific && blocked.isEmpty()) {
            log.warning("block-specific is on but blocked-armor has no valid materials, so nothing is blocked.");
        }

        NamespacedKey exemptKey = null;
        if (config.getBoolean("nbt-exempt.enabled", false)) {
            String key = config.getString("nbt-exempt.key", "armor_exempt");
            try {
                exemptKey = new NamespacedKey(plugin, key);
            } catch (IllegalArgumentException e) {
                log.warning("Invalid nbt-exempt.key '" + key + "' (only letters, digits and . _ - / are allowed). NBT exemption disabled.");
            }
        }

        Set<String> exemptLore = new HashSet<>();
        if (config.getBoolean("lore-exempt.enabled", false)) {
            for (String line : config.getStringList("lore-exempt.lines")) {
                String normalized = line.trim().toLowerCase(Locale.ROOT);
                // A blank entry would exempt every item that has an empty lore line
                if (normalized.isEmpty()) log.warning("Ignoring blank entry in lore-exempt.lines.");
                else exemptLore.add(normalized);
            }
        }

        Set<UUID> whitelistIds = new HashSet<>();
        Set<String> whitelistNames = new HashSet<>();
        for (String entry : config.getStringList("whitelist.players")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            try {
                whitelistIds.add(UUID.fromString(trimmed));
            } catch (IllegalArgumentException notUuid) {
                whitelistNames.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        List<String> whitelistNodes = config.getStringList("whitelist.permissions").stream()
                .map(String::trim)
                .filter(node -> !node.isEmpty())
                .toList();

        boolean whitelistEnabled = config.getBoolean("whitelist.enabled", false);
        if (whitelistEnabled && whitelistIds.isEmpty() && whitelistNames.isEmpty() && whitelistNodes.isEmpty()) {
            log.warning("whitelist is enabled but empty, so only players with " + BYPASS + " may wear blocked armor.");
        }

        return new ArmorPolicy(
                damage,
                legacy.deserialize(config.getString("warning-message", "&cArmor is forbidden!")),
                legacy.deserialize(config.getString("equip-denied-message", "&cYou are not allowed to wear that.")),
                config.getBoolean("remove-armor", false),
                config.getBoolean("block-equip", false),
                interval,
                blockSpecific,
                Collections.unmodifiableSet(blocked),
                exemptKey,
                config.getString("nbt-exempt.value", "true"),
                Set.copyOf(exemptLore),
                config.getBoolean("permissions.enabled", false),
                whitelistEnabled,
                Set.copyOf(whitelistIds),
                Set.copyOf(whitelistNames),
                whitelistNodes
        );
    }
}
