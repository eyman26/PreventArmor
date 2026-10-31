# PreventArmor

A lightweight Paper plugin that discourages players from wearing armor. Anyone caught wearing it takes periodic damage and gets a warning in their action bar. Armor can optionally be stripped off them as well. (heavily inspired by naked and afraid)

## Download

 [Modrinth](https://modrinth.com/plugin/preventarmor)

## Requirements

| Platform | Versions | Jar |
|---|---|---|
| Paper (and forks) | 1.21.1 – 26.3 | `PreventArmor-paper-<version>.jar` |
| Folia | 1.21.4 – 26.3 | same jar as Paper |
| Fabric (server or singleplayer) | 1.21.11 | `PreventArmor-fabric-1.21.11-<version>.jar` + Fabric API |
| Fabric | 26.3 | `PreventArmor-fabric-26.3-<version>.jar` + Fabric API |

Paper 26.x and every Fabric 26.x server need Java 25; 1.21.x needs Java 21. On Fabric, permissions go through
[fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) (bundled), so LuckPerms works as usual;
without a permissions mod, nodes that default to op fall back to permission level 2.

## Installation

1. Download the latest `PreventArmor-<version>.jar` (or [build it yourself](#building)).
2. Drop it into your server's `plugins/` folder.
3. Start the server. `plugins/PreventArmor/config.yml` is generated automatically.
4. Edit the config to taste and run `/preventarmor reload`.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/preventarmor` | Shows the command usage. | `preventarmor.reload` |
| `/preventarmor reload` | Reloads `config.yml` and restarts the armor check with the new settings. | `preventarmor.reload` |

## Permissions

| Permission | Description | Default |
|---|---|---|
| `preventarmor.reload` | Allows use of `/preventarmor` and `/preventarmor reload`. | Operators |
| `preventarmor.bypass` | May wear any armor, ignoring the whitelist and per-piece rules. | Nobody |
| `preventarmor.wear.<slot>` | `head`, `chest`, `legs` or `feet`: may wear blocked items in that slot (needs `permissions.enabled`). | Nobody |
| `preventarmor.wear.<item>` | e.g. `preventarmor.wear.netherite_chestplate` (needs `permissions.enabled`). | Nobody |
| `preventarmor.whitelisted` | Default whitelist node (see `whitelist.permissions`). | Nobody |

### Who may wear blocked armor

For a blocked, non-exempt piece the checks run in this order:

1. `preventarmor.bypass` → allowed.
2. `whitelist.enabled: true` and the player is not listed in `whitelist.players` and has none of `whitelist.permissions` → refused.
3. `permissions.enabled: true` → allowed only with `preventarmor.wear.<slot>` or `preventarmor.wear.<item>`.
4. Otherwise → allowed if the player passed the whitelist, refused if the whitelist is off.

## Configuration

`plugins/PreventArmor/config.yml`

| Option | Default | Description |
|---|---|---|
| `damage-amount` | `2.0` | Damage dealt per check, in half-hearts (`2.0` = 1 heart). `0` means no damage (message/removal only); negative values are treated as `0`. |
| `warning-message` | `"&cArmor is forbidden!"` | Action bar message. Supports `&` color codes. |
| `remove-armor` | `false` | If `true`, offending armor is also dropped at the player's feet. |
| `block-equip` | `true` | Refuse unauthorized armor instead of punishing it: it goes straight back into the inventory (or on the ground if full). No damage in this mode. |
| `equip-denied-message` | `"&cYou are not allowed to wear that."` | Action bar message when `block-equip` takes a piece off. |
| `check-interval` | `20` | Ticks between checks (`20` = 1 second). Values below 1 are raised to 1. |
| `block-specific` | `false` | `false` = every non-exempt item in an armor slot is blocked. `true` = only items in `blocked-armor`. |
| `blocked-armor` | `DIAMOND_HELMET`, `NETHERITE_CHESTPLATE` | Materials to block when `block-specific` is `true`. Uses [Bukkit material names](https://jd.papermc.io/paper/org/bukkit/Material.html). |
| `nbt-exempt.enabled` | `true` | Enable the NBT exemption. |
| `nbt-exempt.key` | `"armor_exempt"` | Name of the tag to look for. Only letters, digits and `. _ - /` are allowed. |
| `nbt-exempt.value` | `"true"` | Value the tag must have (case-insensitive). |
| `lore-exempt.enabled` | `true` | Enable the lore exemption. |
| `lore-exempt.lines` | `"ONLY NETHERITE ARMOR"` | Lore lines that mark an item as exempt. Blank entries are ignored. |
| `permissions.enabled` | `false` | Per-piece `preventarmor.wear.*` permissions. |
| `whitelist.enabled` | `false` | Only whitelisted players may wear blocked armor at all. |
| `whitelist.players` | `[]` | Names or UUIDs. |
| `whitelist.permissions` | `preventarmor.whitelisted` | Holding any of these nodes counts as whitelisted. |

On Fabric the same options live in `config/preventarmor.json` (camelCase keys, e.g. `blockEquip`, `whitelistPlayers`) and are reloaded with `/preventarmor reload`.

> **Note:** with `block-specific: false`, *anything* in an armor slot counts, including elytra, carved pumpkins, and mob heads. Use `block-specific: true` with a `blocked-armor` list if you only want to restrict real armor pieces.

## Exempting items

Exempt items are ignored completely: no damage, no warning, no removal.

### NBT exemption

The item must have a **string** tag stored in the plugin's persistent data under the `preventarmor` namespace. With the default config, that means `preventarmor:armor_exempt` set to `"true"`.

Example with the item component syntax (1.21+):

```
/give @s diamond_chestplate[custom_data={PublicBukkitValues:{"preventarmor:armor_exempt":"true"}}]
```

### Lore exemption

Give the item a lore line matching one of the entries in `lore-exempt.lines`. Matching is done on the whole line, ignores capitalization and color codes, and trims surrounding spaces. With the default config, a line reading `ONLY NETHERITE ARMOR` (in any color) makes the item exempt.

## How it works

Every `check-interval` ticks each survival/adventure player's four armor slots are checked. On Paper and Folia
this runs on the player's own entity scheduler (their region thread on Folia); on Fabric it runs at the end of
the server tick.

* `block-equip: true` - unauthorized pieces are moved back into the inventory as soon as they are equipped
  (and on every check, which also catches armor put on by commands or worn while logging in).
* `block-equip: false` - if any piece is unauthorized the player takes `damage-amount` damage **once** and sees
  the warning; with `remove-armor` the offending pieces are also dropped at their feet.

The damage is dealt as *starvation* damage, which Minecraft treats as bypassing armor points, enchantments and the Resistance effect. The side effect is that a player killed by the penalty sees the death message "starved to death".

## Building

Requires JDK 21 and JDK 25 (Gradle toolchains pick them up, or downloads them via foojay).

```bash
git clone https://github.com/eyman26/PreventArmor.git
cd PreventArmor
./gradlew buildAll
```

All jars end up in `build/dist/`. `./gradlew :paper:check` also re-compiles the Paper sources against the newest
Paper API to catch removed API early, and `./gradlew :paper:runServer` / `:paper:runFolia` start a dev server.

## License

Released under the [GNU General Public License v3.0](LICENSE).

Made by **Eyman**.
