![MinerTrack Anti-XRay](./Images/MinerTrack.png)

## MinerTrack Anti-XRay

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/minertrack?style=flat&label=Modrinth%20Downloads&color=%234bd965)](https://modrinth.com/plugin/minertrack) [![SpigotMC Downloads](https://img.shields.io/spiget/downloads/120562?label=SpigotMC%20Downloads&color=yellow)](https://www.spigotmc.org/resources/120562/) [![Hangar Downloads](https://img.shields.io/hangar/dt/MinerTrack?label=Hangar%20Downloads&color=blue)](https://hangar.papermc.io/Author87668/MinerTrack) [![CurseForge Downloads](https://img.shields.io/curseforge/dt/1159157?label=CurseForge%20Downloads&color=orange)](https://www.curseforge.com/minecraft/bukkit-plugins/minertrack)

[![Dev Build CI](https://img.shields.io/github/actions/workflow/status/At87668/MinerTrack/AutoBuild_CI_dev.yml) ![Latest Version](https://img.shields.io/github/release/At87668/MinerTrack.svg?style=flat&label=Latest%20Version)](https://gitHub.com/At87668/MinerTrack/releases/) [![GitHub issues](https://img.shields.io/github/issues/At87668/MinerTrack.svg?style=flat&label=Github%20Issue)](https://gitHub.com/At87668/MinerTrack/issues/) [![GitHub latest commit](https://badgen.net/github/last-commit/At87668/MinerTrack?style=flat&label=Last%20Commit)](https://gitHub.com/At87668/MinerTrack/commit/) [![GPLv3 license](https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat&label=Open%20Source%20License)](https://github.com/At87668/MinerTrack/blob/main/LICENSE)

![Support Versiopn](https://img.shields.io/badge/Support_Version-1.21.x_%7C_1.20.x_%7C_1.19.x_%7C_1.18.x-&?color=069F00)

[![Support Server](https://img.shields.io/discord/1302190990639235122.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/MzTea2W9cb)

[English](./README.md) | [简体中文](./README-zh_hans.md)

![Image](./Images/Overview.png)

**MinerTrack** is a plugin that will really help you catch those naughty players using Xray on your server in a **different way** from other Anti-Xray plugins. This plugin doesn't hide ores, as hiding ores requires a lot of resources.

*So how does it work?*

To catch Xray, MinerTrack uses an **advanced algorithm** that combines **several factors** like ore scarcity, the amount of ores mined in a certain period, the player’s path to ores, and many other aspects.

*But can't people still use Xray?*

Yes and no. Indeed, a player using Xray has two options:
1. Keep using Xray but behave like a normal player to avoid being caught. They can't get more ores than a normal player or they’ll be caught by MinerTrack.
2. Uninstall their Xray or be caught.

*Why choose MinerTrack over other AntiXray options?*

Compared to Paper Anti-XRay and Orebfuscator:
- MinerTrack is lightweight.
- MinerTrack's engine is innovative.
- MinerTrack detects Xray users, leaving it to you to decide on sanctions.

Compared to other AntiXray solutions:
- MinerTrack supports the latest Minecraft version.
- MinerTrack has been tested on servers with about 20 simultaneous players, with very few false positives (*default config*).
- MinerTrack is free.

![Image](./Images/Features.png)

- Detect XRayer
- Automatically handle cases when a player's X-Ray violation level reaches a threshold
- Automatically reduce the violation level when the player's behavior normalizes
- Analyze player mining paths to detect X-Ray usage
- When the player is mining in a cave, they will not be detected incorrectly
- Highly configurable profiles

![Image](./Images/Commands.png)

- `/mtrack notify <message>` - Send alerts to staff
- `/mtrack verbose` - Enable Detailed Mode and notify staff with it enabled whenever a player’s violation level increases
- `/mtrack check <player>` - Check a player’s violation history
- `/mtrack reset <player>` - Reset a player's violation record
- `/mtrack help` - Get plugin help
- `/mtrack kick <player> <reason>` - Kick a player with a specific reason
- `/mtrack reload` - Reload the plugin’s configuration
- `/mtrack update` - Check for plugin updates

![Image](./Images/Permissions.png)

- `minertrack.bypass` - Bypass X-Ray detection
- `minertrack.notify` - Receive notifications and verbose information
- `minertrack.checkupdate` - Receive update information and use `/mtrack update`
- `minertrack.use` - Access the root command `/mtrack`
- `minertrack.check` - Use `/mtrack check`
- `minertrack.kick` - Use `/mtrack kick`
- `minertrack.help` - Use `/mtrack help`
- `minertrack.reset` - Use `/mtrack reset`
- `minertrack.sendnotify` - Use `/mtrack notify`
- `minertrack.verbose` - Use `/mtrack verbose`
- `minertrack.reload` - Use `/mtrack reload`

![Image](./Images/Installation.png)

1. Download the latest version of **MinerTrack** from SpigotMC.
2. Place the .jar file into the plugins folder.
3. Restart the server to generate the configuration and necessary files.

![Image](./Images/Requirements.png)

- Java 17 or higher
- Paper, Purpur, Folia or compatible forks (1.18 or newer) *Not Spigot!*

![Image](./Images/Support.png)

If you encounter any issues or have suggestions for new features, feel free to reach out via SpigotMC or open an issue on the plugin’s GitHub repository.



**View Source: https://github.com/At87668/MinerTrack**

**Report Issue: https://github.com/At87668/MinerTrack/issues**

**Pull Requset: https://github.com/At87668/MinerTrack/pulls**

**Visit Wiki: https://minertrack.pages.dev/wiki/**

**Join Discord: https://discord.gg/MzTea2W9cb**



[![bStats](https://bstats.org/signatures/bukkit/MinerTrack.svg)](https://bstats.org/plugin/bukkit/MinerTrack/23790)

---

### *If you like this plugin, don’t forget to give a 5-star rating!*
