# PreventArmor

A lightweight Paper plugin that discourages players from wearing armor. Anyone caught wearing it takes periodic damage and gets a warning in their action bar. Armor can optionally be stripped off them as well. (heavily inspired by naked and afraid)
## Requirements

- [Paper](https://papermc.io/) **1.21 or newer** (or a Paper fork such as Purpur). Plain Spigot/CraftBukkit is not supported because the plugin uses Paper's Adventure API.
- The Java version your Paper server already requires. The plugin itself is compiled for Java 21.

### Compatibility

| Server | Status |
|---|---|
| Paper 1.21 – 1.21.11 | Supported (Java 21) |
| Paper 26.1 and newer (new Mojang version scheme) | Should work. The plugin only uses long-stable Bukkit/Paper API and no server internals (Paper 26.x needs Java 25 on the server). |
| Paper older than 1.21 | Not supported (`api-version: '1.21'`) |
| Spigot / CraftBukkit | Not supported |
| Folia | Not supported (uses the global Bukkit scheduler and is not marked `folia-supported`) |

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

> There is no "bypass" permission. To let specific players or items off the hook, use the [exemptions](#exempting-items) below.

## Configuration

`plugins/PreventArmor/config.yml`

| Option | Default | Description |
|---|---|---|
| `damage-amount` | `2.0` | Damage dealt per check, in half-hearts (`2.0` = 1 heart). `0` means no damage (message/removal only); negative values are treated as `0`. |
| `warning-message` | `"&cArmor is forbidden!"` | Action bar message. Supports `&` color codes. |
| `remove-armor` | `false` | If `true`, offending armor is also dropped at the player's feet. |
| `check-interval` | `20` | Ticks between checks (`20` = 1 second). Values below 1 are raised to 1. |
| `block-specific` | `false` | `false` = every non-exempt item in an armor slot is blocked. `true` = only items in `blocked-armor`. |
| `blocked-armor` | `DIAMOND_HELMET`, `NETHERITE_CHESTPLATE` | Materials to block when `block-specific` is `true`. Uses [Bukkit material names](https://jd.papermc.io/paper/org/bukkit/Material.html). |
| `nbt-exempt.enabled` | `true` | Enable the NBT exemption. |
| `nbt-exempt.key` | `"armor_exempt"` | Name of the tag to look for. Only letters, digits and `. _ - /` are allowed. |
| `nbt-exempt.value` | `"true"` | Value the tag must have (case-insensitive). |
| `lore-exempt.enabled` | `true` | Enable the lore exemption. |
| `lore-exempt.lines` | `"ONLY NETHERITE ARMOR"` | Lore lines that mark an item as exempt. Blank entries are ignored. |

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

A repeating task runs every `check-interval` ticks. For each online survival/adventure player it looks at the four armor slots. If any item there is blocked and not exempt, the player takes `damage-amount` damage **once** (regardless of how many pieces they wear) and sees the warning in their action bar. If `remove-armor` is on, each offending piece is also removed from the armor slot and dropped at their feet.

The damage is dealt as *starvation* damage, which Minecraft treats as bypassing armor points, enchantments and the Resistance effect. The side effect is that a player killed by the penalty sees the death message "starved to death".

## Building

Requires JDK 21 or newer. The Gradle wrapper is included, so no separate Gradle install is needed.

```bash
git clone https://github.com/eyman26/PreventArmor.git
cd PreventArmor
./gradlew build
```

The jar is written to `build/libs/PreventArmor-<version>.jar` (use `gradlew.bat build` on Windows).

## License

Released under the [GNU General Public License v3.0](LICENSE).

Made by **Eyman**.
