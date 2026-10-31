package org.eyman_.preventarmor.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Parsed config/preventarmor.json. Same rules and permission nodes as the Paper plugin. */
final class ArmorPolicy {

    static final String BYPASS = "preventarmor.bypass";
    static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<Item, String> ITEM_NODES = new HashMap<>();

    /** The on-disk shape. Field names are the JSON keys. */
    static final class Settings {
        double damageAmount = 2.0;
        String warningMessage = "&cArmor is forbidden!";
        boolean removeArmor = false;
        boolean blockEquip = true;
        String equipDeniedMessage = "&cYou are not allowed to wear that.";
        int checkInterval = 20;
        boolean blockSpecific = false;
        List<String> blockedArmor = List.of("diamond_helmet", "netherite_chestplate");
        boolean nbtExemptEnabled = true;
        String nbtExemptKey = "armor_exempt";
        String nbtExemptValue = "true";
        boolean loreExemptEnabled = true;
        List<String> loreExemptLines = List.of("ONLY NETHERITE ARMOR");
        boolean permissionsEnabled = false;
        boolean whitelistEnabled = false;
        List<String> whitelistPlayers = List.of();
        List<String> whitelistPermissions = List.of("preventarmor.whitelisted");
    }

    final float damage;
    final Component warning;
    final Component equipDenied;
    final boolean removeArmor;
    final boolean blockEquip;
    final int interval;
    private final boolean blockSpecific;
    private final Set<Item> blocked = new HashSet<>();
    private final String exemptKey;
    private final String exemptValue;
    private final Set<String> exemptLore = new HashSet<>();
    private final boolean perPiecePermissions;
    private final boolean whitelistEnabled;
    private final Set<UUID> whitelistIds = new HashSet<>();
    private final Set<String> whitelistNames = new HashSet<>();
    private final List<String> whitelistNodes = new ArrayList<>();

    private ArmorPolicy(Settings s, Logger log) {
        damage = (float) Math.max(0, s.damageAmount);
        warning = colored(s.warningMessage);
        equipDenied = colored(s.equipDeniedMessage);
        removeArmor = s.removeArmor;
        blockEquip = s.blockEquip;
        interval = Math.max(1, s.checkInterval);
        blockSpecific = s.blockSpecific;

        for (String name : s.blockedArmor) {
            Identifier id = Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT));
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) blocked.add(BuiltInRegistries.ITEM.getValue(id));
            else log.warn("Invalid item in blockedArmor: {}", name);
        }

        // Same tag the Paper plugin writes, so exempt items keep working after moving between platforms.
        exemptKey = s.nbtExemptEnabled ? "preventarmor:" + s.nbtExemptKey : null;
        exemptValue = s.nbtExemptValue;
        if (s.loreExemptEnabled) {
            for (String line : s.loreExemptLines) {
                String normalized = line.trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty()) exemptLore.add(normalized);
            }
        }

        perPiecePermissions = s.permissionsEnabled;
        whitelistEnabled = s.whitelistEnabled;
        for (String entry : s.whitelistPlayers) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            try {
                whitelistIds.add(UUID.fromString(trimmed));
            } catch (IllegalArgumentException notUuid) {
                whitelistNames.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        s.whitelistPermissions.stream().map(String::trim).filter(node -> !node.isEmpty()).forEach(whitelistNodes::add);
    }

    static ArmorPolicy load(Path file, Logger log) throws IOException {
        Settings settings = new Settings();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                Settings read = GSON.fromJson(reader, Settings.class);
                if (read != null) settings = read;
            } catch (JsonParseException e) {
                throw new IOException("Malformed " + file.getFileName(), e);
            }
        }
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(settings, writer);
        }
        return new ArmorPolicy(settings, log);
    }

    boolean allows(ServerPlayer player, ItemStack stack, EquipmentSlot slot) {
        if (blockSpecific && !blocked.contains(stack.getItem())) return true;
        if (isExempt(stack)) return true;
        if (Permissions.check(player, BYPASS, false)) return true;
        if (whitelistEnabled && !isWhitelisted(player)) return false;
        if (perPiecePermissions) {
            return Permissions.check(player, "preventarmor.wear." + slot.getName(), false)
                    || Permissions.check(player, itemNode(stack.getItem()), false);
        }
        return whitelistEnabled;
    }

    private boolean isWhitelisted(ServerPlayer player) {
        if (whitelistIds.contains(player.getUUID())) return true;
        if (whitelistNames.contains(player.nameAndId().name().toLowerCase(Locale.ROOT))) return true;
        for (String node : whitelistNodes) {
            if (Permissions.check(player, node, false)) return true;
        }
        return false;
    }

    private boolean isExempt(ItemStack stack) {
        if (exemptKey != null) {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            if (data != null) {
                CompoundTag tag = data.copyTag();
                String value = tag.getCompoundOrEmpty("PublicBukkitValues").getStringOr(exemptKey, tag.getStringOr(exemptKey, ""));
                if (value.equalsIgnoreCase(exemptValue)) return true;
            }
        }
        if (!exemptLore.isEmpty()) {
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) {
                for (Component line : lore.lines()) {
                    if (exemptLore.contains(line.getString().trim().toLowerCase(Locale.ROOT))) return true;
                }
            }
        }
        return false;
    }

    private static String itemNode(Item item) {
        return ITEM_NODES.computeIfAbsent(item, i -> "preventarmor.wear." + BuiltInRegistries.ITEM.getKey(i).getPath());
    }

    static Component colored(String legacy) {
        return Component.literal(legacy.replace('&', '§'));
    }
}
