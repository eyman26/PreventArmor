package org.eyman_;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

public class preventarmor extends JavaPlugin {
   private double damageAmount;
   private String warningMessage;
   private boolean removeArmor;
   private long checkInterval;
   private boolean blockSpecific;
   private Set<Material> blockedArmor;
   private boolean enableNBTExempt;
   private String exemptNBTKey;
   private String exemptNBTValue;
   private boolean enableLoreExempt;
   private List<String> exemptLoreList;
   private BukkitRunnable armorTask;

   public void onEnable() {
      this.saveDefaultConfig();
      this.loadConfigValues();
      this.startArmorTask();
      this.getLogger().info("PreventArmor enabled! - made by Eyman");
   }

   public void onDisable() {
      if (this.armorTask != null) {
         this.armorTask.cancel();
      }

      this.getLogger().info("PreventArmor disabled!");
   }

   private void loadConfigValues() {
      this.damageAmount = this.getConfig().getDouble("damage-amount", (double)2.0F);
      this.warningMessage = this.getConfig().getString("warning-message", "&cArmor is forbidden!");
      this.removeArmor = this.getConfig().getBoolean("remove-armor", false);
      this.checkInterval = this.getConfig().getLong("check-interval", 20L);
      this.blockSpecific = this.getConfig().getBoolean("block-specific", false);
      this.blockedArmor = new HashSet();

      for(String s : this.getConfig().getStringList("blocked-armor")) {
         try {
            Material mat = Material.valueOf(s.toUpperCase());
            this.blockedArmor.add(mat);
         } catch (IllegalArgumentException var5) {
            this.getLogger().warning("Invalid material in blocked-armor: " + s);
         }
      }

      this.enableNBTExempt = this.getConfig().getBoolean("nbt-exempt.enabled", false);
      this.exemptNBTKey = this.getConfig().getString("nbt-exempt.key", "armor_exempt");
      this.exemptNBTValue = this.getConfig().getString("nbt-exempt.value", "true");
      this.enableLoreExempt = this.getConfig().getBoolean("lore-exempt.enabled", false);
      this.exemptLoreList = this.getConfig().getStringList("lore-exempt.lines");
   }

   private void startArmorTask() {
      if (this.armorTask != null) {
         this.armorTask.cancel();
      }

      this.armorTask = new BukkitRunnable() {
         public void run() {
            for(Player player : Bukkit.getOnlinePlayers()) {
               boolean wearingBlockedArmor = false;
               ItemStack[] armorContents = player.getInventory().getArmorContents();

               for(ItemStack item : armorContents) {
                  if (item != null && item.getType() != Material.AIR && !preventarmor.this.isExempt(item)) {
                     boolean isBlocked = !preventarmor.this.blockSpecific || preventarmor.this.blockedArmor.contains(item.getType());
                     if (isBlocked) {
                        wearingBlockedArmor = true;
                        if (preventarmor.this.removeArmor) {
                           player.getInventory().remove(item);
                           player.getWorld().dropItemNaturally(player.getLocation(), item);
                        }
                     }
                  }
               }

               if (wearingBlockedArmor) {
                  player.damage(preventarmor.this.damageAmount);
                  player.sendActionBar(Component.text(preventarmor.this.warningMessage.replace("&", "§")));
               }
            }

         }
      };
      this.armorTask.runTaskTimer(this, 0L, this.checkInterval);
   }

   private boolean isExempt(ItemStack item) {
      return this.isNBTExempt(item) || this.isLoreExempt(item);
   }

   private boolean isNBTExempt(ItemStack item) {
      if (!this.enableNBTExempt) {
         return false;
      } else {
         ItemMeta meta = item.getItemMeta();
         if (meta == null) {
            return false;
         } else {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            NamespacedKey key = new NamespacedKey(this, this.exemptNBTKey);
            String value = (String)data.get(key, PersistentDataType.STRING);
            return value != null && value.equalsIgnoreCase(this.exemptNBTValue);
         }
      }
   }

   private boolean isLoreExempt(ItemStack item) {
      if (!this.enableLoreExempt) {
         return false;
      } else if (!item.hasItemMeta()) {
         return false;
      } else {
         ItemMeta meta = item.getItemMeta();
         if (!meta.hasLore()) {
            return false;
         } else {
            List<String> lore = meta.getLore();
            if (lore == null) {
               return false;
            } else {
               for(String line : lore) {
                  for(String exempt : this.exemptLoreList) {
                     String cleanLine = line.replaceAll("§.", "").trim();
                     if (cleanLine.equalsIgnoreCase(exempt.trim())) {
                        return true;
                     }
                  }
               }

               return false;
            }
         }
      }
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
      if (!command.getName().equalsIgnoreCase("preventarmor")) {
         return false;
      } else if (args.length == 0) {
         sender.sendMessage("§eUsage: /preventarmor reload");
         return true;
      } else if (args[0].equalsIgnoreCase("reload")) {
         if (!sender.hasPermission("preventarmor.reload")) {
            sender.sendMessage("§cYou don’t have permission to use this command.");
            return true;
         } else {
            this.reloadConfig();
            this.loadConfigValues();
            this.startArmorTask();
            sender.sendMessage("§aPreventArmor config reloaded!");
            return true;
         }
      } else {
         sender.sendMessage("§cUnknown subcommand. Use: /preventarmor reload");
         return true;
      }
   }
}
