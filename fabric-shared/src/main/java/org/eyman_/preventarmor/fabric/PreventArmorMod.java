package org.eyman_.preventarmor.fabric;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PreventArmorMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("PreventArmor");

    private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("preventarmor.json");
    private final Set<ServerPlayer> pendingStrip = new LinkedHashSet<>();
    private ArmorPolicy policy;
    private int ticks;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerEntityEvents.EQUIPMENT_CHANGE.register((entity, slot, previous, current) -> {
            // Fires mid entity-tick; touching equipment here would re-enter the update, so defer to end of tick.
            if (policy != null && policy.blockEquip && slot.isArmor() && !current.isEmpty() && entity instanceof ServerPlayer player) {
                pendingStrip.add(player);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                Commands.literal("preventarmor")
                        .requires(Permissions.require("preventarmor.reload", PermissionLevel.GAMEMASTERS))
                        .then(Commands.literal("reload").executes(ctx -> {
                            try {
                                reload();
                                ctx.getSource().sendSuccess(() -> ArmorPolicy.colored("&aPreventArmor config reloaded!"), true);
                                return 1;
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(ArmorPolicy.colored("&cReload failed: " + e.getMessage()));
                                return 0;
                            }
                        }))));
        // Item ids are only resolvable once registries are frozen, so the policy is built at server start.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                reload();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not load " + configFile, e);
            }
        });
    }

    private void reload() throws IOException {
        policy = ArmorPolicy.load(configFile, LOGGER);
    }

    private void tick(MinecraftServer server) {
        if (policy == null) return;

        if (!pendingStrip.isEmpty()) {
            for (ServerPlayer player : pendingStrip) {
                if (!player.hasDisconnected()) strip(player);
            }
            pendingStrip.clear();
        }

        if (++ticks < policy.interval) return;
        ticks = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (policy.blockEquip) strip(player);
            else punish(player);
        }
    }

    private void punish(ServerPlayer player) {
        if (isImmune(player)) return;

        boolean violation = false;
        for (EquipmentSlot slot : ArmorPolicy.ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || policy.allows(player, stack, slot)) continue;

            violation = true;
            if (policy.removeArmor) {
                player.setItemSlot(slot, ItemStack.EMPTY);
                player.spawnAtLocation(player.level(), stack);
            }
        }
        if (!violation) return;

        if (policy.damage > 0) player.hurtServer(player.level(), player.damageSources().starve(), policy.damage);
        player.sendSystemMessage(policy.warning, true);
    }

    private void strip(ServerPlayer player) {
        if (isImmune(player)) return;

        boolean stripped = false;
        for (EquipmentSlot slot : ArmorPolicy.ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || policy.allows(player, stack, slot)) continue;

            player.setItemSlot(slot, ItemStack.EMPTY);
            if (!player.getInventory().add(stack)) player.spawnAtLocation(player.level(), stack);
            stripped = true;
        }
        if (stripped) player.sendSystemMessage(policy.equipDenied, true);
    }

    private static boolean isImmune(ServerPlayer player) {
        return player.isCreative() || player.isSpectator();
    }
}
