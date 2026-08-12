# AGENTS.md — ChunkPlan（探索额度）

## 项目简介

ChunkPlan（modid=`chunkplan`，中文名「探索额度」）是**纯服务端** Minecraft mod：按**玩家实体踏入区块**计费，通过多条滚动窗口额度线限制玩家的探索消耗，额度耗尽后临时封禁（窗口滑出自动恢复）。用于控制服务器区块加载成本。

- 双加载器：**NeoForge 1.21.1** + **Fabric 1.21.1**（独立 mod，不依赖任何上游 mod）
- 一期纯服务端；客户端 HUD / 配置 GUI 为二期
- 零 mixin，纯事件；Java 21（`D:\Games\ABOUT_MINECRAFT\JAVA\zulu21.44.17-ca-jdk21.0.8-win_x64`）

## 架构（实现必须遵循）

```
common/     纯 Java 记账引擎，零 Minecraft/加载器 import（仅 Gson + slf4j，双端内置）
neoforge/   NeoForge 21.1.176 薄壳：事件 -> QuotaEngine
fabric/     Fabric 1.21.1 薄壳（官方 mojmap，与 neoforge 代码对齐）
```

- **壳→core 单向依赖**：壳每 tick 调 `engine.onPlayerTick(uuid, exempt, dimKey, x, y, z)`，返回动作由壳执行（disconnect + UserBanList）
- **common 源码并入 neoforge 模块编译**（sourceSets 的 java.srcDir），不用 jarJar —— 原因见"已知坑 #1"
- fabric 用 Loom `include project(':common')`
- 版本矩阵以 `gradle.properties` 为准（Gradle 8.14.2 是 NeoGradle 7 与 Loom 1.13 的兼容交集）

## 产品规格（与用户逐条确认，勿改）

| 项 | 决策 |
| --- | --- |
| 计费事件 | 玩家实体踏入区块（chunkPos 变化）即计费，与区块加载状态无关 |
| 费率 | 集合外 `1.0` / 集合内 `0.05`；高速（>1.0 格/tick，严格大于）再 ×2 |
| 挂机 | 零计费；登录首 tick 只记基准不扣费；边界踱步重复计费**接受，不加防抖** |
| 额度线 | 可配置 1~4 条滚动窗口线，**全部满才拒**；默认 `5h≤500` + `24h≤2000` |
| 耗尽处理 | 临时 ban：游玩中当场踢出；纯窗口滑出自动恢复，不设最短 ban 时长 |
| 豁免 | 默认 OP + 配置名单豁免，`exemptByDefault=false` 时全员受限 |
| 集合 | 个人已探索集合**终身保留**、按维度分区；传送/瞬移只计落点 |
| 持久化 | `world/chunkplan/players/<uuid>.json` 每玩家一文件，定期（5min）+ 离线 + 关服落盘，原子写 |
| 配置 | 数值全部可配；**不做指令配置**；`/quota check|reset <player>|reload`（reset/reload 权限 2） |
| 日志 | 扣费事件写独立 `logs/chunkplan.log`，不污染默认日志 |

## 计费与额度线算法（common/QuotaEngine，防实现偏差）

1. 首 tick（prevChunk==null）只记录基准；否则每 tick：speed = 与上 tick 的三维位移
2. 区块或维度变化 → 计费：先查集合定费率（不在集合**先加入集合**），× 高速倍率，累加进分钟桶（`epochMinute -> 点数`）
3. **先记账后判踢**：所有额度线均满（`spent > limit`，窗口 `(now-window, now]`）→ 返回 BAN（消息含各满线状态与恢复时间）
4. 恢复时间 = 各满线 `窗口内最早消费桶 + 窗口长` 的**最晚者**
5. 过期桶清理：早于最长窗口线 2 倍的桶删除
6. 配置校验：线数 1~4、窗口/上限为正、费率非负，非法回退默认并告警

## 构建与验证

```bash
export JAVA_HOME="D:\Games\ABOUT_MINECRAFT\JAVA\zulu21.44.17-ca-jdk21.0.8-win_x64"
.XMTEMP/gradle-8.14.2/bin/gradle :common:test        # 引擎单测（37 个，全绿为验收前提）
.XMTEMP/gradle-8.14.2/bin/gradle build               # 三模块编译 + 打包
.XMTEMP/gradle-8.14.2/bin/gradle :neoforge:runServer # dev 服务器（工作目录 neoforge/runs/server/）
.XMTEMP/gradle-8.14.2/bin/gradle :fabric:runServer   # dev 服务器（工作目录 fabric/run/）
```

- 首次启动需 `eula.txt`（`eula=true`）；run 目录已被 gitignore 排除
- **NeoGradle/Loom 均不转发 stdin 给服务器控制台** → 命令验证用 rcon：dev 环境改 `server.properties` 开 rcon，用 `.XMTEMP/rcon.py` 发命令（脚本不入库）
- 玩家行为实测（踏入计费/踢出/ban 全链路）需要游戏客户端，服务端启动与命令链路可无客户端验证

## 已知坑（已踩过，勿重蹈）

1. **NeoGradle userdev dev 运行时**：mod 类在独立模块层（JPMS），classpath 上的普通 jar（含 mavenLocal 坐标依赖）对 mod **不可见**（`NoClassDefFoundError`）；`modSource` 会把 common 当 mod 扫描（报"not a valid mod file"）。解法：common 源码并入 neoforge 的 sourceSet 编译
2. **NightConfig TOML 写入器不支持 Map 作为列表元素**（`Unsupported value type`）→ 额度线配置用平行数组 `lines`/`lineLimits`
3. **原版 GameProfileArgument 把 UUID 当玩家名查缓存**（1.21.1 原版；NeoForge patch 过支持 UUID，Fabric 没有）→ 自定义 `resolvePlayer`（UUID 直解 / profile cache 离线名 / 在线名），双端共用
4. **`new GameProfile(uuid, null)` 抛 NPE**（authlib 6.0.54 要求 name 非空）→ 用空字符串
5. **NeoForge 21.1 SERVER 配置默认生成在 `config/`**，`world/serverconfig/` 是可选存档级覆盖层（不是 bug）
6. **映射类名**：mojmap 中是 `UserBanList`/`UserBanListEntry`（不是 GameProfileBanList）
7. **NeoForge 21.1 API**：`PlayerTickEvent` 在 `net.neoforged.neoforge.event.tick` 包；配置注册用 `ModContainer#registerConfig`（构造器注入 ModContainer）
8. **Loom 版本**：1.14+ 要求 Gradle 9.2+，与 NeoGradle 7（Gradle 8.x）冲突 → 固定 Loom 1.13.6
9. **模拟玩家（DevCommands，客户端不可用时的 6.2 验证方案）**：
   - `getEntities(AABB)` 依赖实体 section 可见性，mock 玩家 tp 后 section 状态不可靠会查不到 → 壳层遍历用 `DevCommands.MOCK_PLAYERS` 注册表（dev-only，生产玩家仍走 PlayerList/事件）
   - mock 玩家**不在实体 ticking 列表**（无重力下落）→ NeoForge 的 `PlayerTickEvent.Post` 不触发 → 壳层 `onServerTick` 手动遍历注册表计费（Fabric 本来就是壳层遍历）
   - mock 玩家不进 PlayerList：内置 `/tp <名字>` 解析不到，需用 uuid；`list` 不显示
   - `applyBan` 对 mock 玩家（注册表内）直接 `remove(DISCARDED)` 模拟踢出（虚拟连接 `disconnect` 是 no-op）；**遍历注册表时 applyBan 会 remove → 必须遍历副本否则 CME**
   - usercache 会被同名不同 uuid 的 mock 污染 → `resolvePlayer` 顺序：在线实体（PlayerList + 注册表）> UUID 直解 > usercache
10. **分钟桶粒度**：消费记入 `epochMinute` 桶，窗口按桶滑出 → ban 实际持续 ≈ 消费所在分钟结束 + 窗口长（比精确窗口**短最多 60 秒**，双端实测一致，是设计语义非 bug）
11. **协议级真实客户端实测**（GUI 渲染被环境阻断后的 6.2 验证方案，比 mock 更接近真实链路）：
   - node `mineflayer`/`minecraft-protocol`（PrismarineJS）可完整走 1.21.1 登录→加入世界→位置/命令→踢出/ban 拦截链路（dev 服务器 offline-mode）
   - **原版移动校验**：非鞘翅位移平方 > `100×包数` 即拒（`moved too quickly`，约 10 格/tick 上限）→ 客户端位置包无法大步瞬移；高速 2x 实测用 `/tp` 命令（OP 权限）驱动
   - mineflayer 物理引擎在目标区块未加载时**不发位置包**（`blockAt()==null` 提前 return）→ 手动 `_client.write('position')` 也不可靠（与物理发包冲突）；真实移动验证以 `/tp` 命令为准
   - 登录拦截的客户端证据：原版 `multiplayer.disconnect.banned.reason` 踢出消息（含恢复时间）
12. **NeoForge /quota reload 必须读文件**：`ModConfigSpec` 值只在启动时加载，运行中改 toml 不感知 → `NeoForgeConfig.toQuotaConfigFromFile()`（NightConfig FileConfig 直接解析，失败回退 spec 并告警）；Fabric 的 reload 本来就是文件驱动。另注意 NightConfig 读 TOML 整数返回 `Integer`（非 Long），需 `Number.longValue()` 转换

## 约定

- 代码注释默认中文；common 不 import 任何 MC/加载器类（单测在 common 模块）
- 壳层薄：业务逻辑全部在 common，壳只做事件接线 / 配置映射 / ban 执行
- 双端配置结构保持一致（TOML 与 JSON 字段一一对应）
- 不做：mixin、客户端内容、指令式配置、多 MC 版本；26.x 迁移只适配壳层
