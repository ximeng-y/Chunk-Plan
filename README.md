# Chunk Plan

一款主要由服务端驱动的模组，旨在通过为玩家的探索进行配额，控制服务器的硬件、流量资源消耗。

## 简介

如果你是服主，你是否曾为这些情况烦恼：

- 服务器CPU资源有限，单个玩家无尽的高速跑图，影响了其他玩家的游戏体验；

- 租赁服有流量上限，部分玩家十分“热爱”跑图，导致流量常常超支；

- 服务器玩家很多，难以量化追踪到底是谁一直在无穷无尽地探索新区块，谁是无辜的受害者；

- 不想过分地限制玩家的自由，但是又难以将有限的服务器资源相对公平地分配给玩家。

如果你曾因上述问题而苦恼，那么本模组给出了一个解决方案。

本模组的设计灵感源自一类AI订阅产品——Token Plan。它通过**并列**的不同计费窗口，达到对用户的限制，在提供较多总资源的同时，避免用户短时间内消耗大量资源。



### 本模组做了什么

#### 区块探索额度

以“区块探索额度”作为计费单位，以量化的方式展示、控制每个玩家的探索



#### 分层计费

本模组的计费触发时机为玩家**踏入**下一个区块

将计费分为三层：

- **新区块：**一名玩家从未踏足过的区块，按**默认费用**计费，默认1\.0额度/区块

- **旧区块：**一名玩家曾经踏足过的区块，按**低费用**计费，默认0\.05额度/区块

- **高速移动：**叠加在以上两种计费之上，当玩家**高速移动**时，计费会在**所触发的计费类型基础上**按倍率提升，倍率默认2\.00x

> 此处对于高速移动的定义：当玩家的速度达到原版、无任何药水及其它效果时的创造模式飞行速度，视为高速移动
> 
> 

以上提到的三层计费，它们的每区块额度消耗、高速移动倍率都可自定义配置



#### 并列计费窗口

本模组默认开启2个，最高支持开启4个计费窗口：

- **tier1（基础）：**刷新最快，配额最低的窗口，用于限制短时间内爆发消耗；

- **tier2（中级）：**刷新较慢，配额中等的窗口，用于控制半日/单日等单元型消耗；

- **tier3（高级）：**默认不开启，刷新很慢，配额较高的窗口，用于周/月级别的限制；

- **tier4（顶层）：**默认不开启，刷新最慢，配额最高的窗口，用于月/季度/年级别的限制。



计费窗口机制：

- **并列计费：**玩家的消耗会**同时作用于**所有窗口；

- **独立刷新：**每个计费窗口会根据其设置的刷新时间进行清零——从该窗口刷新后的首次消耗起计时，到点整窗清零（等价于管理员重置），而不是按消耗时间逐笔滚动过期；

- **单独配置：**可自定义配置每个计费窗口的开关、刷新时限、探索配额。



#### 额度查询与提醒

- 在玩家进入服务器时，该玩家的聊天框会弹出一条消息，展示他的额度情况；

- 提供非管理员玩家也能执行的查询命令`/chunkplan check`；

- 当玩家额度触发某窗口的百分比阈值时，会自动在聊天框弹出提醒。额度剩余百分比越少，提醒越严重；

- 玩家可通过`/chunkplan rules`命令查询计费规则，规则中的具体数值会被**实时替换**为服务器配置的数值。



#### 额度达限惩罚

- 当一名玩家的**任意一个计费窗口**达到了配置的上限，那么他将被**立即踢出游戏，被暂时封禁**并向他声明理由、解禁时间；
- 在暂时封禁期间，若此玩家尝试进入服务器，会再次收到封禁理由、解禁时间等信息；
- 当额度限制刷新，该玩家的封禁会被服务器**自动解除。**

![中文踢出公告](https://raw.githubusercontent.com/ximeng-y/Chunk-Plan/main/photos/中文踢出.png)



#### 管理员控制

- 本模组允许，且默认开启**管理员计费豁免**机制——开启后，管理员的区块探索不再计费；
- 本模组的自定义功能均可通过`/chunkplan config`命令进行实时控制；
- 管理员可使用`/chunkplan reset`命令为某名玩家/全部玩家**重置探索额度**，且可选择重置所有/重置单个窗口。



#### 客户端GUI

- 当玩家在客户端安装此mod时，按K键（默认）即可开启GUI界面，查看自己的用量情况，等效于`/chunkplan check`命令
- 当管理员打开GUI界面时，还可以使用可视化的管理界面，等效于`/chunkplan config`系列命令



## 授权与支持

### 版本支持

本模组目前的支持情况：

||Forge|NeoForge|Fabric|
|---|---|---|---|
|1\.20\.1|✓|——|——|
|1\.21\.1|——|✓|✓|
|1\.21\.11|——|——|✓|
|26\.1\.2|——|——|✓|
|26\.2|——|——|✓|

原则上本模组**不计划：**

- 迁移到Forge除1\.20\.1外的版本；

- 迁移到Fabric早于1\.21\.1的版本；

- 迁移到NeoForge早于1\.21\.1的版本。

本模组**计划：**

- Fabric版本跟进Minecraft版本更新；

- 迁移到社区认为稳定且生态良好的NeoForge版本。

如果您有对Fabric 1\.21\.2\~1\.21\.10版本，以及其它版本的迁移需求，请[在这里提交issue](https://github.com/ximeng-y/Chunk-Plan/issues)。

出现在评论区的需求也可能被采纳，但issue被注意到的概率和采纳优先级都会更高。

若您有对于不在计划内的版本的迁移需求，也可以提交issue。我们将视需求情况考虑迁移。



### 已知限制

- **单人模式：**单人游戏的主机玩家恒为管理员，且默认受豁免——默认配置下您的额度消耗恒为0；若想在单人模式下体验计费，可执行`/chunkplan config exemptByDefault false`关闭管理员豁免。**请注意，若您在单人模式下触发封禁，那么在封禁期间您将无法进入此存档！我们没有尝试过解决此问题的方法，暂时无法给出解决方案。但已知：到达刷新时间时，您的封禁会被正常解除（如果您仍安装着此模组的话）。我们不建议您在单人模式下关闭管理员豁免！！！**；

- **离线模式：**离线服务器的玩家UUID由其游戏名派生，冒用豁免名单中的玩家名即可获得相同的UUID，从而绕过计费与封禁（与原版OP/白名单机制同源的问题）；在线模式不受此影响；使用离线模式时，建议配合登录插件等其它防护手段。



### 功能计划

- 计划新增虚拟“额度重置卡”功能，管理员可向玩家发放重置卡，玩家可自由控制自己的额度重置时机。



**本模组允许且鼓励使用在您的任何整合包、服务器中，不必申请许可**

**如果您喜欢此模组，请为我们**[**点个Star**](https://github.com/ximeng-y/Chunk-Plan)**\~**

本模组基于 [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) 协议开源

---

# Chunk Plan(English)

A mod that runs mainly on the server, which aims to control server hardware and bandwidth consumption by limiting players' exploration.

## Introduction

If you are managing a Minecraft server, maybe you have met these problems:

- CPU of the server cannot support a crazy player who explores the world endlessly, leaving other players with a poor gaming experience;

- The server has a network traffic limit, but a part of players like exploring, which always causes the traffic to go over budget;

- There are lots of players in the server, so we cannot track who is the crazy explorer in a measurable way;

- You don't want to imprison the players, but wish to allocate the server resources fairly.

If you have been troubled by these, this mod provides a solution.

This Mod is inspired by a kind of product — Token Plan, which is an AI subscription product with parallel billing windows. It can provide more total resources and limit those who want to use up a lot of resources in a short time.

### What This Mod Does

#### Chunk Exploration Quota

It uses chunk exploration quota as the billing unit, shows and controls everyone's exploring in a measurable way.



#### Layered Billing

This mod charges when a player steps into the next chunk.

Billing is divided into three layers:

- **New chunk:** a chunk that a player has never stepped into is billed at the **default fee**, 1.0 quota per chunk by default;

- **Old chunk:** a chunk that a player has stepped into before is billed at a **low fee**, 0.05 quota per chunk by default;

- **High-speed movement:** stacked on top of the two types above. When a player moves at high speed, the billing is multiplied based on **the triggered billing type**, with a default multiplier of 2.00x.

> The definition of high-speed movement here: when a player's speed reaches the vanilla creative-mode flight speed (without any potions or other effects), it is considered high-speed movement.

The per-chunk quota consumption and the high-speed multiplier of the three billing layers above can all be customized.



#### Parallel Billing Windows

This mod enables 2 billing windows by default, and supports up to 4:

- **tier1 (Basic):** the window with the fastest refresh and the lowest quota, used to limit burst consumption in a short time;

- **tier2 (Intermediate):** a window with slower refresh and medium quota, used to control unit consumption such as half-day/single-day;

- **tier3 (Advanced):** disabled by default, a window with very slow refresh and higher quota, used for weekly/monthly limits;

- **tier4 (Top):** disabled by default, the window with the slowest refresh and highest quota, used for monthly/quarterly/yearly limits.

Billing window mechanism:

- **Parallel billing:** a player's consumption applies to **all windows at the same time**;

- **Independent refresh:** each billing window clears according to its configured refresh time — timing starts from the window's first consumption after the refresh, and when the time arrives, the whole window resets to zero (equivalent to an admin reset), instead of individual charges expiring on a rolling basis;

- **Individual configuration:** the on/off state, refresh time, and exploration quota of each billing window can be customized.



#### Quota Query and Reminders

- When a player joins the server, a message pops up in their chat box showing their quota status;

- A query command `/chunkplan check` is provided, which non-admin players can also execute;

- When a player's quota triggers the percentage threshold of a window, a reminder automatically pops up in the chat box. The less quota remaining, the more severe the reminder;

- Players can query the billing rules via the `/chunkplan rules` command. The specific values in the rules are **replaced in real time** with the server's configured values.



#### Quota Limit Penalty

- When **any billing window** of a player reaches the configured limit, they will be **kicked out immediately and temporarily banned**, with the reason and unban time announced to them;

- During the temporary ban, if the player tries to enter the server, they will receive the ban reason, unban time, and other information again;

- When the quota limit refreshes, the player's ban will be **automatically lifted** by the server.

![英文踢出公告](https://raw.githubusercontent.com/ximeng-y/Chunk-Plan/main/photos/英文踢出.png)



#### Administrator Control

- This mod allows, and enables by default, the **admin billing exemption** mechanism — when enabled, admins' chunk exploration is no longer billed;
- All customization features of this mod can be controlled in real time via the `/chunkplan config` command;
- Admins can use the `/chunkplan reset` command to **reset the exploration quota** of a player or all players, and can choose to reset all windows or a single window.



#### Client GUI

- When this mod is installed on the client, players can press K (default) to open the GUI and view their quota usage, equivalent to the `/chunkplan check` command;
- When an admin opens the GUI, they also get access to a visual admin interface, equivalent to the `/chunkplan config` command set.



## License and Support

### Version Support

The current support status of this mod:

| Version | Forge | NeoForge | Fabric |
| --- | --- | --- | --- |
| 1.20.1 | ✓ | —— | —— |
| 1.21.1 | —— | ✓ | ✓ |
| 1.21.11 | —— | —— | ✓ |
| 26.1.2 | —— | —— | ✓ |
| 26.2 | —— | —— | ✓ |

In principle, this mod **does not plan** to:

- migrate to Forge versions other than 1.20.1;

- migrate to Fabric versions earlier than 1.21.1;

- migrate to NeoForge versions earlier than 1.21.1.

This mod **plans** to:

- keep the Fabric version up to date with Minecraft updates;

- migrate to NeoForge versions that the community considers stable with a good ecosystem.

If you need migration support for Fabric 1.21.2~1.21.10 or other versions, please [submit an issue here](https://github.com/ximeng-y/Chunk-Plan/issues).

Requests in the comment section may also be adopted, but issues are more likely to be noticed and have higher adoption priority.

If you need migration to a version not in the plan, you can also submit an issue. We will consider the migration based on the demand.



### Known Limitations

- **Singleplayer mode:** The host player in singleplayer is always an operator and is exempt by default — your quota consumption will always be 0 with the default config; to experience billing in singleplayer, run `/chunkplan config exemptByDefault false` to disable admin exemption. **Please note: if you trigger a ban in singleplayer mode, you will not be able to enter this world while the ban lasts! We have not tried any workaround for this ourselves, so we cannot provide a solution for now. However, what we do know is that your ban will be automatically lifted once the refresh time arrives (as long as you still have this mod installed). We do NOT recommend disabling admin exemption in singleplayer!!!**;

- **Offline mode:** In offline-mode servers, player UUIDs are derived from their in-game names, so impersonating a name on the exemption list yields the same UUID and can bypass billing and bans (the same inherent issue as vanilla OP/whitelist mechanics); online mode is unaffected; when using offline mode, we recommend pairing it with login plugins or other protective measures.



### Feature Plans

- We plan to add a virtual "quota reset card" feature. Admins can issue reset cards to players, and players can freely control when their quota is reset.



**This mod is allowed and encouraged to be used in any of your modpacks and servers, no permission required.**

**If you like this mod, please [give us a Star](https://github.com/ximeng-y/Chunk-Plan)~**

This mod is open source under the [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) license.

