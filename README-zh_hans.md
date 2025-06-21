![MinerTrack Anti-XRay](./Images/MinerTrack.png)

## MinerTrack Anti-XRay


[![Modrinth Downloads](https://img.shields.io/modrinth/dt/minertrack?style=flat&label=Modrinth%20Downloads&color=%234bd965)](https://modrinth.com/plugin/minertrack) [![SpigotMC Downloads](https://img.shields.io/spiget/downloads/120562?label=SpigotMC%20Downloads&color=yellow)](https://www.spigotmc.org/resources/120562/) [![Hangar Downloads](https://img.shields.io/hangar/dt/MinerTrack?label=Hangar%20Downloads&color=blue)](https://hangar.papermc.io/Author87668/MinerTrack) [![CurseForge Downloads](https://img.shields.io/curseforge/dt/1159157?label=CurseForge%20Downloads&color=orange)](https://www.curseforge.com/minecraft/bukkit-plugins/minertrack)

[![Dev Build CI](https://img.shields.io/github/actions/workflow/status/At87668/MinerTrack/AutoBuild_CI_dev.yml) ![Latest Version](https://img.shields.io/github/release/At87668/MinerTrack.svg?style=flat&label=Latest%20Version)](https://gitHub.com/At87668/MinerTrack/releases/) [![GitHub issues](https://img.shields.io/github/issues/At87668/MinerTrack.svg?style=flat&label=Github%20Issue)](https://gitHub.com/At87668/MinerTrack/issues/) [![GitHub latest commit](https://badgen.net/github/last-commit/At87668/MinerTrack?style=flat&label=Last%20Commit)](https://gitHub.com/At87668/MinerTrack/commit/) [![GPLv3 license](https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat&label=Open%20Source%20License)](https://github.com/At87668/MinerTrack/blob/main/LICENSE)

![Support Versiopn](https://img.shields.io/badge/Support_Version-1.21.x_%7C_1.20.x_%7C_1.19.x_%7C_1.18.x-&?color=069F00)

[![Support Server](https://img.shields.io/discord/1302190990639235122.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/MzTea2W9cb)

[English](./README.md) | [简体中文](./README-zh_hans.md)

![Image](./Images/Overview.png)

**MinerTrack** 是一款全新的反矿透插件，它使用挖矿行为分析而不是隐藏矿石的反矿透方式

*本插件的工作方式?*

为了更精准的判断矿透行为, MinerTrack 使用了 **多维度数据** 进行分析, 例如挖掘矿石稀有度、玩家一段时间内挖矿数量、玩家前往矿石的路径

*那么, 玩家还能使用矿透进行作弊吗?*

能, 但并不完全, 使用矿透的玩家面临着两个选择:
1. 开着矿透, 但挖掘与正常玩家相同数量的矿石
2. 卸载矿透, 不然被抓

*为什么要选择 MinerTrack?*

与 Paper 的 Anti-Xray 和 Orebfuscator 相比:
- MinerTrack 更为轻量
- 使用算法检测, 更加先进
- 可以检测 Xray 使用者，如何处理由你决定

与其他的反矿透插件相比:
- 更新迅速, 支持最新游戏版本
- 已在 20 人在线服务器中测试，几乎没有误判（默认配置）
- 完全免费

![Image](./Images/Features.png)

* 检测使用 Xray 的玩家
* 当玩家违规等级达到阈值时自动处理
* 当玩家行为正常时自动降低违规等级
* 分析玩家挖矿路径判断是否使用 Xray
* 玩家在洞穴中正常挖矿不会被误判
* 高度可配置的检测配置文件

---

![Image](./Images/Commands.png)

* `/mtrack notify <消息>` - 向管理发送通知
* `/mtrack verbose` - 开启详细模式，玩家违规等级变动时提示管理
* `/mtrack check <玩家>` - 查看某个玩家的违规记录
* `/mtrack reset <玩家>` - 重置某个玩家的违规记录
* `/mtrack help` - 查看插件帮助
* `/mtrack kick <玩家> <理由>` - 踢出玩家并说明原因
* `/mtrack reload` - 重新加载插件配置
* `/mtrack update` - 检查插件更新

---

![Image](./Images/Permissions.png)

* `minertrack.bypass` - 绕过 Xray 检测
* `minertrack.notify` - 接收警告与详细信息
* `minertrack.checkupdate` - 接收更新信息并使用 `/mtrack update`
* `minertrack.use` - 使用 `/mtrack` 根命令
* `minertrack.check` - 使用 `/mtrack check`
* `minertrack.kick` - 使用 `/mtrack kick`
* `minertrack.help` - 使用 `/mtrack help`
* `minertrack.reset` - 使用 `/mtrack reset`
* `minertrack.sendnotify` - 使用 `/mtrack notify`
* `minertrack.verbose` - 使用 `/mtrack verbose`
* `minertrack.reload` - 使用 `/mtrack reload`

---

![Image](./Images/Installation.png)

1. 从 SpigotMC 下载最新版 **MinerTrack**
2. 把 `.jar` 文件放入 `plugins` 文件夹
3. 重启服务器生成配置文件与相关数据

---

![Image](./Images/Requirements.png)

* Java 17 或更高版本
* 仅支持 Paper、Purpur、Folia 或兼容分支（支持 1.18 及以上）*请注意, 本插件不支持 Spigot!*

---

![Image](./Images/Support.png)

如果你遇到任何问题，或者有建议功能，可以通过 SpigotMC 联系我们，或在 GitHub 提交 issue

---

* **查看源码**: [https://github.com/At87668/MinerTrack](https://github.com/At87668/MinerTrack)
* **提交问题**: [https://github.com/At87668/MinerTrack/issues](https://github.com/At87668/MinerTrack/issues)
* **提交Pull Request**: [https://github.com/At87668/MinerTrack/pulls](https://github.com/At87668/MinerTrack/pulls)
* **访问 Wiki**: [https://minertrack.pages.dev/wiki/](https://minertrack.pages.dev/wiki/)
* **加入 Discord**: [https://discord.gg/MzTea2W9cb](https://discord.gg/MzTea2W9cb)

[![bStats](https://bstats.org/signatures/bukkit/MinerTrack.svg)](https://bstats.org/plugin/bukkit/MinerTrack/23790)