# AGENTS.md — ChunkPlan（探索额度）

## 项目简介

ChunkPlan（modid=`chunkplan`，中文名「探索额度」）是**纯服务端** Minecraft mod：按**玩家实体踏入区块**计费，通过多条滚动窗口额度线限制玩家的探索消耗，额度耗尽后临时封禁（窗口滑出自动恢复）。用于控制服务器区块加载成本。

- 三加载器：**NeoForge 1.21.1** + **Fabric 1.21.1** + **Forge 1.20.1**（独立 mod，不依赖任何功能型上游 mod；Fabric 端依赖 `fabric-api` 生态基础设施——生命周期事件/命令注册/连接事件，`fabric.mod.json` 的 `depends` 已声明，NeoForge 与 Forge 端无额外依赖）
- 一期纯服务端；客户端 HUD / 配置 GUI 为二期
- 零 mixin，纯事件；Java 21（`D:\Games\ABOUT_MINECRAFT\JAVA\zulu21.44.17-ca-jdk21.0.8-win_x64`），**forge 模块为 Java 17**（1.20.1 官方要求，`zulu17.62.17-ca-jdk17.0.17-win_x64`，坑 #38）

## 架构（实现必须遵循）

```
common/     纯 Java 记账引擎，零 Minecraft/加载器 import（仅 Gson + slf4j，双端内置）
neoforge/   NeoForge 21.1.176 薄壳：事件 -> QuotaEngine
fabric/     Fabric 1.21.1 薄壳（官方 mojmap，与 neoforge 代码对齐）
forge/      Forge 1.20.1 薄壳（ForgeGradle 6 + 官方 mojmap，Java 17，坑 #38）
fabric-multi/ Fabric 多版本独立构建（1.21.11/26.1.2/26.2，坑 #33）：shared/ 共享壳层源码 + 每版本薄模块
```

- **壳→core 单向依赖**：壳每 tick 调 `engine.onPlayerTick(uuid, exempt, dimKey, x, y, z)`，返回动作由壳执行（disconnect + UserBanList）
- **common 源码并入 neoforge 模块编译**（sourceSets 的 java.srcDir），不用 jarJar —— 原因见"已知坑 #1"
- fabric 1.21.1 用 `implementation project(':common')` + sourceSets 源码并入；fabric-multi 各版本用相对路径 srcDir 并入（不用 `include()`，坑 #33）；**forge 只并入 srcDir，绝不加 `project(':common')` 依赖**（common jar 由 JDK 21 产出，class 版本 65 无法被 Java 17 运行时读取，坑 #38）
- 版本矩阵以 `gradle.properties` 为准（Gradle 8.14.2 是 NeoGradle 7 与 Loom 1.13 的兼容交集；ForgeGradle 6.0.54 实测与之共存，故 forge 留在根构建，不像 fabric-multi 那样独立）；fabric-multi 为**独立构建**（Gradle 9.5.1 + Loom 1.17.19 + JDK 21/25 toolchain，坑 #33）

## 产品规格（与用户逐条确认，勿改）

| 项 | 决策 |
| --- | --- |
| 计费事件 | 玩家实体踏入区块（chunkPos 变化）即计费，与区块加载状态无关 |
| 费率 | 集合外 `1.0` / 集合内 `0.05`；高速（>0.5 格/tick，严格大于 = 创造模式飞行速度 ~0.54，地面疾跑 ~0.28 不触发）再 ×2 |
| 挂机 | 零计费；登录首 tick 只记基准不扣费；边界踱步重复计费**接受，不加防抖** |
| 额度线 | 四档固定周期线（每档独立开关 + 窗口预设校验，坑 #24；首消锚定周期、到点整窗清零，坑 #40），**任一窗口满即拒**（坑 #25）；默认仅开第一档 `5h≤500` + 第二档 `24h≤2000` |
| 耗尽处理 | 临时 ban：游玩中当场踢出；纯窗口滑出自动恢复，不设最短 ban 时长 |
| 豁免 | 默认 OP + 配置名单豁免，`exemptByDefault=false` 时全员受限 |
| 集合 | 个人已探索集合**终身保留**、按维度分区；传送/瞬移只计落点 |
| 持久化 | `world/chunkplan/players/<uuid>.json` 每玩家一文件，定期（5min）+ 离线 + 关服落盘，原子写 |
| 配置 | 数值全部可配；**不做指令配置**（唯一例外：`/chunkplan config exemptByDefault [true|false]`，gamerule 风格，无参查询/带参设置并原子写回配置文件，反馈不显示文件路径）；`/chunkplan check|reset <player>|confirm|reload`（reset/confirm/reload 权限 2；reset 需 confirm 二次确认） |
| 登录欢迎 | 进服（未被额度拦截）自动发送一次 check 状态 + `查询额度请使用 /chunkplan check 命令` 提示，按玩家客户端语言；客户端可视化入口行二期再加 |
| 日志 | 扣费事件写独立 `logs/chunkplan.log`，不污染默认日志 |

## 计费与额度线算法（common/QuotaEngine，防实现偏差）

1. 首 tick（prevChunk==null）只记录基准；否则每 tick：speed = 与上 tick 的三维位移
2. 区块或维度变化 → 计费：先查集合定费率（不在集合**先加入集合**），× 高速倍率，累加进各启用档位的固定周期（首消锚定周期起点、对齐整分钟，坑 #40）
3. **先记账后判踢**：任一额度线满（`spent > limit`）→ 返回 BAN（恢复时间；文案由壳层按玩家语言渲染，坑 #22）
4. 恢复时间 = 各满线 `周期起点 + 窗口长` 的**最晚者**；到点整窗清零（等价 reset），承诺精确兑现（坑 #40）
5. 配置校验：线数 1~4、窗口/上限为正、费率非负，非法回退默认并告警

## 构建与验证

```bash
export JAVA_HOME="D:\Games\ABOUT_MINECRAFT\JAVA\zulu21.44.17-ca-jdk21.0.8-win_x64"
.XMTEMP/gradle-8.14.2/bin/gradle :common:test        # 引擎单测（115 个，全绿为验收前提）
.XMTEMP/gradle-8.14.2/bin/gradle build               # 四模块编译 + 打包
.XMTEMP/gradle-8.14.2/bin/gradle :neoforge:runServer # dev 服务器（工作目录 neoforge/runs/server/）
.XMTEMP/gradle-8.14.2/bin/gradle :fabric:runServer   # dev 服务器（工作目录 fabric/run/）
.XMTEMP/gradle-8.14.2/bin/gradle :forge:runServer    # dev 服务器（工作目录 forge/run/server/，坑 #38）

# fabric 多版本（独立构建，坑 #33；run 目录 fabric-multi/fabric-<版本>/run/）
.XMTEMP/gradle-9.5.1/bin/gradle -p fabric-multi build                        # 三版本编译 + 打包
.XMTEMP/gradle-9.5.1/bin/gradle -p fabric-multi :fabric-1.21.11:runServer
.XMTEMP/gradle-9.5.1/bin/gradle -p fabric-multi :fabric-26.1.2:runServer
.XMTEMP/gradle-9.5.1/bin/gradle -p fabric-multi :fabric-26.2:runServer
```

- 首次启动需 `eula.txt`（`eula=true`）；run 目录已被 gitignore 排除
- forge 模块用 Java 17 toolchain（JDK 17 路径已加入 `org.gradle.java.installations.paths`）；Gradle 守护进程仍跑 JDK 21，toolchain 自动切换，**不需要**改 `JAVA_HOME`
- **NeoGradle/Loom/ForgeGradle 均不转发 stdin 给服务器控制台** → 命令验证用 rcon：dev 环境改 `server.properties` 开 rcon，用 `.XMTEMP/rcon.py` 发命令（脚本不入库）
- 玩家行为实测（踏入计费/踢出/ban 全链路）需要游戏客户端，服务端启动与命令链路可无客户端验证
- **1.21.11+/26.x dev 服务器必须在 `server.properties` 设 `pause-when-empty-seconds=0`**（1.21.2 快照 24w33a 引入、默认 60：无真实玩家即暂停全部 tick，mock 不计入 PlayerList 会被暂停；1.21.1 无此属性，坑 #33）

## 已知坑（已踩过，勿重蹈）

1. **NeoGradle userdev dev 运行时**：mod 类在独立模块层（JPMS），classpath 上的普通 jar（含 mavenLocal 坐标依赖）对 mod **不可见**（`NoClassDefFoundError`）；`modSource` 会把 common 当 mod 扫描（报"not a valid mod file"）。解法：common 源码并入 neoforge 的 sourceSet 编译
2. **NightConfig TOML 写入器不支持 Map 作为列表元素**（`Unsupported value type`）→ 额度线配置曾用平行数组 `lines`/`lineLimits`（四期起已废弃，改为四档标量字段 `tierNEnabled/tierNWindow/tierNLimit`，坑 #24）
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
10. **分钟桶粒度（已被坑 #40 取代）**：v2 及以前消费记入 `epochMinute` 桶按桶滑出，ban 实际持续 ≈ 消费所在分钟结束 + 窗口长（比精确窗口短最多 60 秒）；坑 #40 改固定周期语义后不再存在，仅存历史价值
11. **协议级真实客户端实测**（GUI 渲染被环境阻断后的 6.2 验证方案，比 mock 更接近真实链路）：
   - node `mineflayer`/`minecraft-protocol`（PrismarineJS）可完整走 1.21.1 登录→加入世界→位置/命令→踢出/ban 拦截链路（dev 服务器 offline-mode）
   - **原版移动校验**：非鞘翅位移平方 > `100×包数` 即拒（`moved too quickly`，约 10 格/tick 上限）→ 客户端位置包无法大步瞬移；高速 2x 实测用 `/tp` 命令（OP 权限）驱动
   - mineflayer 物理引擎在目标区块未加载时**不发位置包**（`blockAt()==null` 提前 return）→ 手动 `_client.write('position')` 也不可靠（与物理发包冲突）；真实移动验证以 `/tp` 命令为准
   - 登录拦截的客户端证据：原版 `multiplayer.disconnect.banned.reason` 踢出消息（含恢复时间）
12. **NeoForge /chunkplan reload 必须读文件**：NeoForge 21.1 虽有运行时 watcher（改文件自动重载 spec 内存值），但**引擎持有的 QuotaConfig 副本不会随之更新** → `NeoForgeConfig.toQuotaConfigFromFile()`（NightConfig FileConfig 直接解析，失败回退 spec 并告警）。另两个陷阱：
    - NightConfig 读 TOML 整数返回 `Integer`（非 Double/Long）→ **全部数值字段**（含 firstEntryFee 等 Double 字段）都需 `Number` 转换，否则单字段写整数即 ClassCastException 整次回退
    - 运行中用编辑器**非原子保存** TOML 会触发 NeoForge watcher 解析失败 → 文件被改名 `.bak` 并**静默用默认值重新生成**（用户编辑丢失）→ 改配置应原子写入或停服修改
13. **豁免期间必须清除 tracking**（QA 实测 P1）：exempt 分支若不清除位移基准，解除豁免后首个 tick 会把豁免期间累积位移当区块变化计费（OP 被取消后第一次移动多扣一次）→ `tracking.remove(uuid)` 已修，单测 `exemptThenUnexemptFirstTickNotCharged` 回归
14. **ban 来源区分**：`applyBan` 不覆盖已存在的非 ChunkPlan 手动 ban（避免手动永久 ban 被临时 ban 替换后随额度恢复被误解除）；`scanBans` 只解除来源为 `"ChunkPlan"` 的条目（`UserBanListEntry.getSource()`），管理员手动 ban 保留。mock 玩家 ban 时无登出事件 → `applyBan` 内显式 `engine.onPlayerDisconnect(uuid)` 清理 tracking，否则重 spawn 首 tick 误计费
15. **reload 的存档级覆盖层语义**：`world/serverconfig/chunkplan-server.toml` 存在时整体覆盖 `config/` 下同名配置（NeoForge 启动语义），reload 同样优先读覆盖层并在命令反馈中显示实际读取路径
16. **ChunkPosPacker 位序与 vanilla `ChunkPos.asLong` 一致**（x 低 32 位、z 高 32 位）：explored 持久化数据绑定此位序，**不得再改**（曾为反序且注释谎称与 MC 一致，已修；未来也不要用其他编码"优化"）
17. **模拟玩家与性能**：mock 只在 dev 环境注册；Fabric 壳每 tick 的全图实体兜底扫描仅 dev 执行（生产 PlayerList 全覆盖，避免每 tick 全实体开销）；`logFeeEvents` 热切换（false→true）需 `engine.setFeeLogger()` 重建日志（启动时 flag 决定，reload 只改配置不生效）
18. **离线模式固有弱点**：offline 服务器 UUID 由名字派生，攻击者可用豁免名单中的名字冒名获得同 UUID（绕过计费/封禁），与原版 OP/白名单同源；在线模式不受影响。提示服主：离线模式建议配合其他防护（如登录插件）
19. **扣费日志轮转严格仿原版**（已从 1.21.1 官方 server.jar 内 log4j2.xml 核实）：触发仅两类——启动时文件非空（OnStartupTriggeringPolicy）+ 跨天（TimeBasedTriggeringPolicy）；旧文件 gzip 为 `chunkplan-YYYY-MM-dd-N.log.gz`（同日多次启动轮转序号递增）；**无大小阈值、不限份数、不删除旧 gz**（原版行为，旧 gz 无限累积是设计语义非 bug）
20. **explored 按行区间压缩**（v1 格式即区间，无历史格式包袱不做迁移）：内部 `维度 -> z 行号 -> [startX,endX] 区间列表`，增量合并（踏入时与左/右邻相邻即扩展、接住两侧三合一，填平凹口自动合拢，无事后重排）；查询行内二分；序列化 `{"10":[[5,8],[12,15]],...}` 行号排序保证确定性；**不再暴露 Set 视图**（`isExplored(dim, chunkKey)` 替代）；位序依赖 `ChunkPosPacker`（坑 #16 冻结）。改结构须同步 `Dto.explored` 类型与 `markExplored` 不变量
21. **豁免是设计语义，不是故障**：`exemptByDefault=true`（默认）+ 玩家是 OP → 完全豁免，从不计费、`chunkplan.log` 惰性创建（有扣费事件才建文件）所以豁免环境下文件可能永远不出现、玩家数据目录也不创建（实测排查结论）。**单人模式主机恒为权限 4**：原版 `MinecraftServer.getProfilePermissions` 对 `isSingleplayerOwner`（世界创建者）直接返回 4，**与开不开作弊/有无 OP 记录无关**（1.21.1 字节码实证）→ 单人测试想看到计费只能把 `exemptByDefault` 改 false 再 reload；专用服务器才看 ops.json。`/chunkplan check` 已显示豁免状态提示（"当前是管理员/在豁免名单中，不受额度限制"）；离线玩家 OP 状态不可查，仅判豁免名单。豁免不清空已计额度，旧分钟桶随窗口自然滑出（与未豁免玩家一致），只是不再增长
22. **玩家可见文案渲染在壳层，按玩家客户端语言逐玩家选择中/英文**（`player.getLanguage()` 登录时上报，`zh_` 前缀判中文；控制台/rcon 默认英文；玩家改语言需重登录生效）。引擎（common）只返回结构化数据，不拼用户可见文案（ban 消息等一律经壳层 `ChunkPlanMessages` 渲染）；双端各有一份 `ChunkPlanMessages`，改文案必须双端同步
23. **`/chunkplan config exemptByDefault` 用原子写配置文件**（NeoForge 文本替换 + Fabric Gson JsonObject 改字段，均经 `AtomicFile` .tmp+rename）：不用 NightConfig 写器（其非原子保存可能被 NeoForge watcher 半读 → `.bak` + 静默重置，坑 #12）；写文件后走与 reload 相同的"读文件 → setConfig → feeLogger 热切换"链路（`loadAndApplyConfig` 双端共用），命令设置持久化到配置文件（重启保留）；豁免提示行金色 `§6` 为强调色
24. **四档额度线 + 登录欢迎 + 阈值默认 0.5**（四期改造）：
    - 额度线以四档呈现（每档 `enabled`/`window`/`limit` 三个标量字段，TOML 与 JSON 各 12 键），窗口受预设约束（第一档 30m~12h、第二档 12h~7d、第三档 7d~30d、第四档 30d~365d；预设外/非法窗口、limit≤0 → 回退该档默认并告警；**全部档显式禁用 → 零线（无额度限制，坑 #31）**；至少一档启用但全部启用档非法 → 回退默认两条 5h/500 + 24h/2000——注意启用档非法时每档已回退该档默认产线，`toLines` 的 `lines.isEmpty()` 只可能由全禁触发，anyEnabled 防御分支实际不可达）。档位→额度线组装唯一实现于 common `QuotaTiers.toLines`（壳层只做格式转换，避免双端漂移）；引擎仍只消费 `List<Line>`
    - 高速阈值默认 **0.5 格/tick**（=10 格/秒，略低于创造模式飞行 ~10.8 格/秒 ≈0.54 格/tick，达到创造飞行即触发 ×2；地面疾跑 ~0.28 不触发）。注意引擎速度是**三维欧氏距离（含 Y 分量）**，高处坠落跨区块也计高速
    - 登录欢迎：未被额度拦截的玩家进服自动收到一次 check 状态 + `查询额度请使用 /chunkplan check 命令` 提示（按玩家语言）；渲染复用 `ChunkPlanMessages.checkStatusText`（与 `/chunkplan check` 同一实现，`/chunkplan check` 输出格式不可漂移）；客户端可视化入口行留待二期。**欢迎须延迟到玩家首个 tick 发送**：原版客户端在配置阶段上报 client_information（含 locale），服务端 `ServerConfigurationPacketListenerImpl.handleClientInformation` 在 `getPlayerForLogin`/JOIN 前已更新（1.21.1 源码实证）——JOIN 时语言本就正确，但延迟到首 tick 对"配置阶段未上报、进 play 立即上报"的客户端也更稳健；壳层用 `WELCOME_PENDING`（登录时登记、首个 tick 移除并发送、登出清理）
    - mineflayer 库缺陷：其 `settings` 包在进 play 后才发（晚于服务端 JOIN），不在配置阶段上报语言 → 用它验证登录欢迎**必须**在 `state=configuration` 时手动 `write('settings', {locale})` 模拟原版（`.XMTEMP/real-client/verify-welcome.js` 已固化）；原生 `minecraft-protocol` createClient 在 NeoForge 1.21.1 配置阶段会卡住（不做额外处理时服务端不发 finish_configuration），勿用
    - **聊天框禁止显示配置文件路径**：reload 与 config 命令反馈均不含路径（路径只进服务端日志），`loadAndApplyConfig` 返回 `List<String>` 告警列表
    - 旧配置迁移：四档改造后旧 `lines`/`lineLimits` 键成为孤儿键（NeoForge 文件里残留但不生效），`highSpeedThreshold` 旧值（如 1.0）持久化覆盖新默认 → 升级需删除旧配置文件（`config/chunkplan-server.toml` / `config/chunkplan.json`）重新生成，否则新默认不生效
25. **任一窗口满即拒（规格变更）**：原设计"全部额度线同时超限才拒"（AND 语义），用户实测发现单线超限（如 5h 500.9/500.0）仍显示"未满，可正常探索"，确认是设计失误 → 改为**任一窗口满即拒**（OR 语义，`isAllLinesExceeded` 与 `quotaStatus.allExceeded` 的循环条件由"存在未满线→false"反转为"存在满线→true"）。`recoveryMillis` 不变（只遍历满线取最早桶+窗口的最晚者，恢复时刻保证满线滑出；未满线不参与）。字段名 `allExceeded` 保留（壳层双端引用，注释注明"任一满"语义）。恢复时间在坑 #40 固定周期语义下精确兑现（到点整窗清零）；玩家恢复后再次探索可能再次触发该线 → 属正常行为。check/ban 文案不变，各线状态全部列出可看出哪条满
26. **ban 公告排版（坑 #26）**：踢出消息按用户模板排版——标题（§c 红，禁止警示）+ 原因行（§c，满线中**窗口最长者**，如 5h 与 1d 同时满显示"1天内 探索额度上限 已耗尽"）+ §7 分割线（44 个 '-'，适中防自动换行）+ "您的探索额度情况："（§e 黄小标题）+ 各线状态（§f 白；满线"（§c已满，下次重置时间：X）"、未满线"（§7下次重置时间：X）"）+ 分割线 + "您最早可于：X 再次进入服务器"（§a 绿）+ 结尾感谢/咨询（§7 灰）。**每线显示各自独立的下次重置时间**（= 该线窗口内最早消费桶 + 窗口长，未满线也有——额度线互相独立、可能跨天不同步，如 5h 满 08-13 21:24 而 1d 未满次日才重置）：引擎 `QuotaStatus.LineStatus` 增加 `nextResetMillis` 字段（`quotaStatus` 逐线计算，无消费为 -1），`recoveryMillis` 保持只算满线。标签列按显示宽度对齐（CJK/全角 2 格、ASCII 1 格，`displayWidth`）；窗口显示名用"5小时内/1天内/30天内"（`windowName`，区别于 check 的 `formatWindow` "5h/1d" 简写）。双端 `ChunkPlanMessages.banMessage` 重写，改文案必须双端同步
27. **JSON 损坏 .bak 备份兜底（坑 #27）**：所有 JSON（玩家数据 `players/<uuid>.json`、管理名单 `chunkplan-managed-bans.json`、Fabric 配置 `chunkplan.json`）写入统一走 `AtomicFile.write`——**写前备份**：目标存在时先把现有文件复制为 `<file>.bak`（覆盖式，同一文件 .bak 最多一个，恒为上一份完好数据）；读取经 `AtomicFile.readJson` 兜底：主文件 parse/IO 失败时尝试 `.bak`，成功则从 .bak 恢复并立即用 `writeNoBackup` 写回主文件修复现场——**恢复写回必须跳过写前备份**，否则损坏的主文件会覆盖唯一的好 .bak；主与 .bak 均失败才走原降级（重建/空名单/回退默认+告警）。**版本不符不尝试 .bak**（.bak 同版本也会不符，恢复无意义，保持重建+告警）。NeoForge 的 TOML 配置由 NightConfig 生态自管（坑 #12），不纳入本机制。Fabric 配置 IO 失败从"中断启动"改为"告警回退默认"（与 NeoForge reload 语义对齐）。实现位置：`AtomicFile.write/writeNoBackup/readJson`、`QuotaEngine.loadOrCreate`、`ManagedBanStore.load`、`FabricConfig.load`；测试 55 → 64（新增 `AtomicFileTest` 6 个 + 引擎/管理名单恢复测试 3 个）
28. **额度百分比阈值提示（坑 #28）**：额度达到窗口上限百分比时提示玩家（**每窗口独立计算**，仅瞬态内存不落盘）。触发档位严格为 15/30/50/65/75/80/85/90/95/98（引擎 `ALERT_PERCENTS` 表，改档须用户确认）；严重度 15~30 低（§a 浅绿）/50~75 中（§e 黄）/80~98 高（§c 红）。**触发语义**：每窗口每档只提示一次；档位上升跨过新档时**逐档**生成（一次 +33% 会发 15、30 两条）；额度重置/滑出后档位回落，重新涨回再触发；**首见（登录/重连/服务器重启后首个 tick）只初始化当前档位不触发**，避免补发历史档位刷屏（`AlertState.initialized`）；豁免玩家不提示且清状态（`alertStates.remove`）。架构：引擎 `TickResult` 增加 `List<WindowAlert>` 字段（`none(List<WindowAlert>)` 工厂，`ban` 保持无 alerts——BAN tick 不发提示，ban 消息已充分说明），壳层 `handlePlayerTick` 逐条 `sendSystemMessage`。消息：差异内容整体按严重度色、窗口名与百分比字段 §b 浅蓝覆盖；固定尾部白色含 `/chunkplan check`（§a 浅绿）与**超链接"点击此处查看详细计费规则"**——点击事件 `RUN_COMMAND "/chunkplan rules"`（本项目首个 ClickEvent），`/chunkplan rules` 子命令无权限要求，渲染 5 条计费规则 + 结尾注意，数值取管理员配置（`String.valueOf` 保留 1.0 / 0.05 / 2.0x 原样）。双端 `ChunkPlanMessages.quotaAlertMessage`（返回 Component）/`rulesMessage` 逐字同步；测试 64 → 72（`QuotaEngineTest` 增 8 个：逐档触发/严重度映射/窗口独立/首见不刷屏/reset 重触发/跨档逐条/豁免清状态/BAN 无提示）
29. **check 未满行档位化（坑 #29）**：`/chunkplan check`（与登录欢迎共用）未满时不再固定显示"未满，可正常探索"，改为**档位词 + 微调句子**：**充足（0≤pct<50，§a）/ 中等（50≤pct<75，§e）/ 不足（≥75，§c）**——用户三轮修正定稿的**连续百分比区间**（达到 50 即中等、达到 75 即不足；75 档归不足区，与坑 #28 alert 严重度分段[75 为中]无关，check 档位词按 percent 区间独立判断）。档位判定：**跨窗口取当前百分比最高档位**，现算跟随当前状态（额度滑出/重置自动回落显示"充足"，不保留触发历史，与 alertStates 解耦）。架构：`QuotaStatus` record 增加 `worstAlert` 字段（`quotaStatus()` 唯一构造点复用 `currentLevel()` 逐线算档位取最高；无档 null），双端 `ChunkPlanMessages.checkStatusText` 未满分支按 `worstAlert().percent()` 区间渲染（签名不变，调用点零改动，欢迎消息同样显示档位词）；满线"已耗尽"分支优先。测试 72 → 76（`QuotaEngineTest` 增 4 个：无档 null/档位-百分边界 15-30-50-62.5-75-87.5-100/跨窗口取最高/滑出回落）
30. **指令控制具体配置（坑 #30，纯英文命令族）**：
    - **按档位分桶记账（数据模型 v2，坑 #40 起升级为 v3 固定周期）**：`QuotaConfig.Line` 增加 `tier`（1~4，分桶键，窗口时长跨档重叠不能反推）；`PlayerQuotaData` 由单一共享 `minuteBuckets` 改为 `tierBuckets`（tier -> epochMinute -> 点数），每次消费计入所有启用档位。`resetSpend(UUID, Set<Integer>)` 单档重置；`clearTierSpendForAll(tier)` 关闭窗口时清空该档所有玩家记录（在线内存 + 离线文件逐个改写，重开从 0 起）。**Dto v2 迁移**：v1 共享桶无法无损拆分，升级保留 explored、丢弃消费桶（日志告警）；`VERSION=1→2`
    - **命令（双端 QuotaCommands 逐字同步）**：`config window <tier1..tier4|all> <on|off>`（off 需 confirm，清空该窗口记录）/ `config windowTime <tier1..tier4> <预置时长>`（tab 补全，未启用报错，时间差不补偿，离线在线一致滑动推导）/ `config windowLimit <tier1..tier4> <数值>`（1.00~999999999.99，调低需 confirm）/ `config highSpeedMultiplier <数值>`（1.00~1000.00）；数值允许整数或 ≤2 位小数（common `NumericParser`，FORMAT/RANGE 分错误文案）；`help`（权限 2，教学 config 与 reset）；`reset <目标> [tier1..tier4|all]`（@a/@e 全部在线、@s 自身、@p 最近、@r 随机、名字/UUID 离线可用；选未启用档位报错，all 忽略；提示"将重置 N 名玩家/玩家名 的 X 额度限制"，X 用 `windowName`"5小时内"或"全部"）
    - **confirm 泛化（单槽 + 60s + 超链接）**：`PendingReset` 泛化为 sealed `PendingAction`（Reset/DisableWindow tier0=全部/LowerLimit），单槽新动作覆盖旧动作（无队列），过期即失效；提示尾部追加 `ChunkPlanMessages.confirmLink` 超链接（ClickEvent RUN_COMMAND "/chunkplan confirm"）
    - **每 tick 判满**：`isAllLinesExceeded` 从"仅区块变化路径"扩展到无变化路径——降额度/改窗口/启新线后 1 tick 内在线超限玩家被踢（原地不动也生效）
    - **`setConfig` 档位集合变化清 `alertStates`**：启用/禁用档位改变 lines 数后 `AlertState.lastLevels`（按下标对齐）会越界/错位，比较新旧档位序列不同则清空（清后首见只重基线不刷屏）
    - **踩坑**：(a) NeoForge `writeKey` 幂等写 bug——`replaceFirst` 后 `replaced.equals(text)` 不能判断"键不存在"（写同值时误判追加重复键 → NightConfig 解析失败 → watcher 用默认值重建整个文件，丢失全部运行期修改）；必须显式 `Matcher.find()` 按匹配区间替换。(b) Brigadier `StringArgumentType.word()` 的 unquoted 字符集**不含 `@`**（@a 解析为空串），且 1.21.1 自定义 ArgumentType 未注册会抛 `Unrecognized argument type` 导致**登录即踢**（命令树网络同步需要注册表）→ reset 目标参数用内置 `greedyString()` 同串解析"<目标> [层级]"（建议补全分两段：玩家名+选择器 / 层级词）。(c) 原版 `op` 命令写 ops.json 时名字小写、离线 UUID 由名字派生——机器人用混合大小写名会导致 UUID 不匹配查不到 OP 权限（dev 冒烟踩坑，与坑 #9 usercache 污染同源）。(d) **`config window <tier> on` 的 enable 分支 try 内必须有 `return 1`**（批量改 catch 时曾误删导致 enable 成功后**继续执行 off 分支**——同一命令既开启又注册关闭待确认，dev 冒烟实测暴露；双端同步修复）
31. **零线（彻底关闭全部窗口）+ 解封（坑 #31，用户拍板）**：
    - **语义**：`config window all off`（或逐档全关）confirm 后 → **零线**：引擎完全停止计费/判踢/百分比提示（`onPlayerTick` 在加载玩家数据前早退，`tracking` 不建立 → 重开窗口后首个 tick 走首 tick 分支只记基准不扣费，从 0 起）；`isAllLinesExceeded` 空循环恒 false（登录 gate 放行）；`check`/登录欢迎显示"所有探索窗口均已关闭，当前无额度限制"（`checkStatusText` 空 lines 分支，双端同步）
    - **零线持久化**：配置文件 4 个 `tierNEnabled=false`，重启后加载即零线——**不因"全禁回退默认"复活**。`QuotaTiers.toLines` 与 `QuotaConfig.normalizeLines` 语义：显式空列表 = 零线（合法不告警）；`raw==null`/越界/非法线丢弃后空 → 回退默认两条（防配置损坏）。disabled 档不校验字段
    - **解封**：confirm all off 后壳层**立即 `scanBans`**（双端同包调用）——只解来源 `"ChunkPlan"` 的 UserBanList 条目与 ManagedBanStore 记录（坑 #14 语义，服主手动 ban 保留）；零线期间 isAllLinesExceeded 恒 false，30s 定时 scanBans 也会自动解除。单档 off 不解封（其余档仍计费，未满线玩家由周期 scanBans 自然解封）。反馈如实明示："已关闭全部窗口，额度限制已停止（ChunkPlan 临时封禁已解除，所有玩家记录已清空）"
    - **`clearTierSpendForAll` 立即落盘（QA P1）**：scanBans 的 `isAllLinesExceeded(uuid)` 会 `computeIfAbsent` 把被 ban 过的离线玩家 load 回内存并滞留 → 清档时内存玩家必须立即 `savePlayer`（原实现只清内存等 5 分钟周期保存，崩溃丢失清除且 .bak 残留旧数据）；离线文件遍历加 `playerDataDir` 存在检查（新世界非错误）与版本门禁（未知版本保留原样）
    - **Fabric 写路径损坏兜底**：`FabricConfig.readRoot` 原直接 `Gson.fromJson` 抛非受检 JsonSyntaxException 穿透命令层 catch(IOException) → 改为复用 `AtomicFile.readJson`（.bak 兜底恢复，主与 .bak 均坏抛 IOException 拒绝写入）
    - **写失败反馈去路径（Security LOW）**：config 命令族全部 catch 分支反馈固定文案"写入配置失败，详见服务端日志"（坑 #24 禁路径不变量），异常详情只进服务端日志；失败返回码统一 0
    - 测试 88 → 96（`QuotaTiersTest` 全禁改零线 + 全非法回退该档默认 + disabled 不校验；`QuotaConfigTest` 空列表零线 + 全非法丢弃回退默认；`QuotaEngineTest` 零线不记账不加载/永不判满/状态空/重开首 tick 不扣费/清档立即落盘）
32. **链接样式 + 反馈窗口名化 + help/rules 措辞配色（坑 #32，纯壳层文案，测试不变）**：
    - **链接样式统一**：confirmLink（点击此处进行确认）与 alert 尾部规则链接（点击此处查看详细计费规则）同为——黄 §e + `[]` 包裹 + hover 悬停（`HoverEvent.Action.SHOW_TEXT`，悬停文本=链接自身文字，提示管理员可点击）。**1.21.1 无 `HoverEvent.ShowText` 包装类**（编译踩坑后 javap 实证），`new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(...))` 直接收 Component；样式经 `withStyle(s -> s.withColor(ChatFormatting.YELLOW).withClickEvent(...).withHoverEvent(...))`
    - **help 措辞（双端 helpMessage 同步）**：标签更名——调整计费窗口开关 / 调整计费窗口刷新时长 / 调整额度上限 / 高速移动倍率 / 开关管理员计费（exemptByDefault，语义"开关管理员计费"）；**所有管道参数加空格**（`<true | false>`、`<on | off>`、`<玩家 | @a>`、`[tier1 | tier2 | tier3 | tier4 | all]`，纯展示不影响实际输入）
    - **反馈窗口名化（QuotaCommands 同步）**：reset confirm 反馈按范围显示窗口名（"已重置 X 的 1天内 额度限制（已探索集合保留）"，全部时"全部"；与确认提示同款 windowName，findLine 空判防御）；windowLimit 前置提示与调高/调低反馈（"将把 5小时内 额度从 …调低至…" / "已调整 5小时内 额度为 …"）；windowTime 反馈（"已调整计费窗口刷新时长为 5小时内"）。**错误提示保留 tierX 字样**（未启用/未知层级/非法预设，管理员排错需要）
    - **rules 配色层级**：标题与编号 `§b` 浅蓝、正文 `§f` 白、指令（/chunkplan check）`§a` 绿、数值（1.0/0.05/2.0x）与结尾"请注意…"整段 `§e` 黄（数值取管理员配置 String.valueOf 原样）
    - **实测方法**：rcon 是纯文本通道（样式对象与链接 JSON 不可见）→ 链接样式用 mineflayer 实机验证：`bot._client.on('systemChat')` 事件参数为 `{positionId, formattedMessage}`（非原始包），component 为 NBT 解析对象，直接断言 `color:'yellow'`、`clickEvent.run_command`、`hoverEvent.show_text`；文案断言用 `messagestr` 纯文本（§ 代码已剥离）
33. **fabric 多版本适配（坑 #33，独立构建 + 1.21.11 API 断层，2026-08-14）**：
    - **结构**：`fabric-multi/` 是**独立 Gradle 构建**（自带 settings.gradle，命令加 `-p fabric-multi`），根构建（NeoGradle 7 钉在 Gradle 8.14.2）与 26.x（Gradle 9.5 + JDK 25）无法共存。`shared/src/main/java/` 一份壳层源码（5 类，从 fabric 1.21.1 拷贝后迁移）供三版本 srcDir 共用；跨版本编译不过的类移出放各版本自己的 `src/main/java`（客户端 GUI 一期起，`ChunkPlanNetwork`/`ChunkPlanGuiScreen`/`ChunkPlanFabricClient` 因渲染/输入/网络 API 分叉为三版本各自的覆盖类，其余壳层仍在 shared）；common 纯 Java 源码同样相对路径 srcDir 并入（**不用 `include()`**——Loom 26.2 的 include() 有未闭合 issue #1588）。产物按 `archivesName` 区分：`chunkplan-0.1.0-fabric-<版本>.jar`（命名格式 `<modid>-<版本>-<加载器>-<MC版本>`，六端统一；archivesName 拼入 mod_version、模块 version 置空防尾部重复后缀）
    - **工具链矩阵**（fabric-multi/gradle.properties）：Loom **1.17.19**（同一 jar 提供两个插件 id）、loader **0.19.3**。1.21.11（最后一代混淆版）用 `net.fabricmc.fabric-loom-remap` + `mappings loom.officialMojangMappings()` + `modImplementation` + **JDK 21**；26.x（非混淆：官方名内嵌、无 yarn、intermediary 为占位符；版本 JSON 自 1.21.11 起**两代均无** downloads.mappings 条目，映射另行发布）用 `net.fabricmc.fabric-loom` + **无 mappings** + `implementation`（官方 26.1 迁移博客：modImplementation→implementation、remapJar→jar）+ **JDK 25**。fabric-api：0.141.6+1.21.11 / 0.155.2+26.1.2 / 0.157.0+26.2。fabric.mod.json 依赖下限：1.21.11 `fabricloader>=0.18.0`（对齐官方 example-mod 模板，2026-08-15 审查修正，原 0.16.6 过低）、`java>=21`；26.x `fabricloader>=0.18.2`（0.18.0 改进非混淆支持、0.18.2 起支持日期式版本号）、`java>=25`；minecraft 严格等号 pin。JDK 25 = `D:\Games\ABOUT_MINECRAFT\JAVA\zulu25.30.17-ca-jdk25.0.1-win_x64`（fabric-multi/gradle.properties 的 installations.paths 同时列 JDK 21 与 25）
    - **1.21.11 API 断层**（26.x 同代，全部 javap 实证；fabric-multi/shared 用新 API，neoforge 与 fabric 1.21.1 用旧 API，**两代永久分叉**）：
      - `GameProfile` 变 **Record**（authlib 7.0.61+）：`getName()/getId()` → `name()/id()`
      - 权限：`CommandSourceStack.hasPermission(int)` 与 `ServerPlayer.hasPermissions(int)` 删除 → `permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(n)))`（PermissionLevel 枚举 ALL/MODERATORS/GAMEMASTERS/ADMINS/OWNERS = 0~4 级）；`DevCommands` 新增 `hasPermission(CommandSourceStack/ServerPlayer, int)` 双重载，QuotaCommands requires 与 ChunkPlanFabric 豁免判定复用
      - profile 解析：`MinecraftServer.getProfileCache()` 删除（GameProfileCache 类移除）→ `server.services().profileResolver().fetchByName/fetchById`（ProfileResolver 接口，底层 UserNameToIdResolver + NameAndId）；离线模式 fetchByName 可能产生标题化名称的缓存条目（authlib 行为，坑 #9 usercache 污染同源），resolvePlayer 顺序不变：在线实体 > UUID 直解 > resolver
      - 封禁/OP 列表：`UserBanList.get/remove` 与 `ServerOpListEntry` 改收 **`NameAndId`**（`new NameAndId(profile)` 转换；`getKeyForUser`=uuid 字符串）；`UserBanListEntry` 5 参构造收 NameAndId；`ServerOpListEntry` 第 2 参改 `LevelBasedPermissionSet.forLevel(PermissionLevel.GAMEMASTERS)`（原 int 2）
      - 文本事件：`ClickEvent`/`HoverEvent` 变**接口** → `new ClickEvent.RunCommand("/...")`、`new HoverEvent.ShowText(Component)`；注意 1.21.1 反而**无** ShowText 包装类（坑 #32），两代写法相反
      - 出生点：`ServerLevel.getSharedSpawnPos()` 删除 → `level.getRespawnData().pos()`（数据驱动世界 RespawnData record）；**26.2 又删 `BlockPos.getCenter()`** → 统一 `Vec3.atCenterOf(pos)`（1.21.11/26.2 都有）
      - Entity：`kill()` → `kill((ServerLevel) player.level())`；`moveTo(BlockPos,float,float)` 全删 → `teleportTo(double,double,double)`（mock 生成定位）；`Entity/ServerPlayer.getServer()` 删除、`ServerPlayer.server` 字段变 private → `player.level().getServer()`
      - `ResourceKey.location()` → `identifier()`（dimension().identifier().toString()）
    - **dev 服务器自动暂停（实测大坑）**：server.properties 的 `pause-when-empty-seconds`（1.21.2 快照 24w33a 引入、默认 60；1.21.1 无此属性）——无真实玩家（**mock 不进 PlayerList 不计入**）60 秒后暂停**全部 tick** → 壳层每 tick 计费/扫描停摆（曾把"调高上限后不解封"误判为 scanBans 失效，实为服务器暂停）。dev 冒烟必须设 `pause-when-empty-seconds=0`（已写进三个 run 目录的 server.properties）；且 run/config 的配置跨会话持久化（如 tier1Limit 遗留），冒烟前后注意恢复默认
    - **镜像纪律扩展**：改文案/命令仍须三处同步（neoforge、fabric 1.21.1、fabric-multi/shared）；**API 差异行不属于同步范围**（neoforge/fabric 1.21.1 旧 API vs fabric-multi 新 API），同步只针对业务逻辑与文案
    - **验证**：common 96 测试不变；三版本 `build` + dev 服务器 rcon 冒烟全链路通过（计费 2.0=1.0×2 高速、调低上限当场踢出、ban 记录 source=ChunkPlan、恢复上限后 scanBans 自动解封）。**fabric 1.21.1 未经实机验证（仅 NeoForge 实机验证过），fabric 实机验收以 1.21.11 为准**（用户 2026-08-14 声明）；26.x 实机待用户意愿
34. **fabric `environment=server` 单人静默不加载（坑 #34，2026-08-15 用户 1.21.11 实机暴露）**：
    - **症状**：jar 确认在版本专属 mods 目录，游戏中无 /chunkplan 指令，日志**零 chunkplan 痕迹**（连入口日志 `[ChunkPlan] ChunkPlan Fabric 壳已注册` 都没有），也无任何报错/拒绝提示——用户最初无法判断 mod 是否运行。
    - **根因**：fabric.mod.json `"environment": "server"`。Fabric Loader 在客户端进程启动时（`Env=CLIENT`）按 environment 过滤 mod 集合，server-only mod 在打印 "Loading N mods" 名单**之前**被**静默剔除**（该过滤只写 debug 级日志）；单人的 integrated server 是客户端进程内的逻辑服务器，**不补加载** server mod → mod 等于不存在。dev 冒烟 `runServer` 是 dedicated 场景（main 入口正常调用）**测不出此过滤**——冒烟全绿 ≠ 单人可用，盲区。
    - **修复**：四个 fabric.mod.json（根 fabric 1.21.1 + fabric-multi 1.21.11/26.1.2/26.2）`environment` 一律改 `"*"`（2026-08-15 提交）；entrypoints 保持 `main` + `ModInitializer` 不动——`*` 时 main 入口在客户端与 dedicated 双端都会调用，mod 无任何客户端类引用，客户端加载无害。NeoForge 无 environment 过滤机制（单人是内嵌服务器），不受影响
    - **`*` ≠ 客户端必装**：Fabric 无 Forge 式强制 mod 列表同步；ChunkPlan 不注册网络包/注册表条目（无协议级差异）→ 客户端不装可正常连装有本 mod 的服务器（dedicated 冒烟即裸 mineflayer 客户端验证），客户端装了连未装服务器也正常（回调仅在服务器存在时触发）
    - **验证方法**：打包后 unzip 静态检查 jar 内 fabric.mod.json 的 environment（此后 fabric 冒烟阶段应加此检查）；实机判断——日志出现 `[ChunkPlan] ChunkPlan Fabric 壳已注册`、Loading N mods 数量 +1、游戏内有 /chunkplan。单人复测注意坑 #21：单人主机恒权限 4，默认豁免下计费恒 0，须先 `/chunkplan config exemptByDefault false`
35. **reset 补全套娃（坑 #35，2026-08-15 用户 1.21.11 实机暴露）**：`suggestResetTarget` 原实现只看剩余文本有无空格——只要有空格就无条件建议层级词（tier1~tier4|all），且 greedy 参数建议需含完整剩余文本 → 用户选完层级词后再打空格，补全继续建议 `all all`/`all tier1`… **每选一次建议多一个词，无限套娃**（用户截图实证输入到 `reset ximeng_y tier4 all all`），而 `reset()` 只接受 `<目标> [层级]` 两词（`parts.length>2` 报参数格式错）——补全把用户引导进死路。修复：**按已输入词数分阶段**——第 1 词输入中（零词或一词无尾随空格）补玩家名+选择器；第 2 词（一词尾随空格或两词无尾随空格）补层级词；第 3 词起（两词尾随空格或更多词）**返回空建议**堵死套娃链。三处（neoforge/fabric 1.21.1/fabric-multi shared）同步，API 差异行（getName()/name()）除外；纯壳层，common 测试不变。**补丁 2（2026-08-15）**：完整两词（`@a tier1`、无尾随空格）时第 2 词分支仍会建议与输入完全相同的 `@a tier1`——**平台兜底实证**：Brigadier 1.1.8（1.20.1）/1.3.10（1.21.1）的 `SuggestionsBuilder.suggest(String)` 首行即 `text.equals(remaining)` 丢弃同文本建议（javap 字节码实证，mineflayer 协议级实测 `@a tier1` 修复前后均 0 条）——该残留实际用户不可见，套娃真实根因是坑 #35 原始场景（尾随空格后继续选、建议多一个词与输入不同、平台不滤），坑 #35 已根治，用户 1.21.1 复测所见为旧 jar 行为。补丁 2 仍保留为**防御性修复**（候选 `v.equalsIgnoreCase(words.get(1))` 跳过同文本，防未来 Brigadier 移除过滤；`@a t` 前缀输入不受影响），四处（含 forge/1.20.1）逐字同步
36. **reset 在线玩家通知窗口名化（坑 #36，2026-08-15 用户实机要求）**：坑 #32 只把「确认提示 + 管理员反馈」窗口名化了，**被重置玩家的在线通知漏改**（"您的ChunkPlan探索额度已被管理员重置"无窗口信息）——用户实机截图指出（反馈已显示"1天内"而通知没有）。修复：`confirm` Reset 分支把 `zhScope`/`enScope` 计算提前到通知循环前，玩家通知按重置范围显示窗口名——中文嵌入式 `您的1天内探索额度已被管理员重置`（全部时 `您的全部探索额度已被管理员重置`，与坑 #32 反馈同款 windowName）；英文括号式 `Your exploration quota (within 1 day) has been reset by an administrator.`（全部时 `(all windows)`，windowName 英文 toLowerCase）。三处同步；纯壳层，common 测试不变
38. **Forge 1.20.1 移植（坑 #38，2026-08-15）**：`forge/` 模块，ForgeGradle **6.0.54** + Forge **1.20.1-47.4.22** + 官方 mojmap，**Java 17**（1.20.1 官方要求，非 21）。（编号跳过 37：坑 #37 是 ultra review 修复，只落在 commit `ca9ff4a` 未建本表条目）
    - **构建结构**：ForgeGradle 6.0.54 与根构建的 Gradle 8.14.2 实测兼容（与 NeoGradle 7 共存无冲突）→ forge **留在根构建**，不需要像 fabric-multi 那样独立。`settings.gradle` 的 pluginManagement 需加 `https://maven.minecraftforge.net/`；forge 专用版本键在根 `gradle.properties` 以 `forge_` 前缀独立成组（`forge_java_version=17` 等），不与 1.21.1 的 `minecraft_version`/`java_version` 混用。JDK 17 路径须加入 `org.gradle.java.installations.paths`（toolchain 自动切换，守护进程仍可跑 JDK 21）
    - **common 只并入 srcDir，禁止 `implementation project(':common')`**：common jar 由 JDK 21 toolchain 产出（class 版本 65），Java 17 运行时读不了；并入源码后由 forge 模块以 17 重编（实测 common 无任何 Java 21 专属特性，`sealed`/`record`/switch 表达式均为 17 已有）
    - **`mods.toml` 依赖块 schema 与 NeoForge 不同**：Forge 1.20.1 用 `mandatory=<bool>`，NeoForge 用 `type="required"`。照抄 `neoforge.mods.toml` 会以 `InvalidModFileException: Missing required field mandatory in dependency (main)` **令整个 mod 文件失效**，并连带 `Failed to find system mod: minecraft` 启动崩溃——报错指向 MinecraftLocator，极易误判为环境问题（实际 `(main)` 就是本 mod 的 dev 源集）。文件名也不同：`META-INF/mods.toml`（非 `neoforge.mods.toml`），`loaderVersion="[47,)"`，依赖 modId 为 `forge`
    - **SERVER 配置路径与 NeoForge 相反**（坑 #5 的镜像）：Forge 1.20.1 的 SERVER 配置是**存档级唯一位置** `<world>/serverconfig/chunkplan-server.toml`（`ServerLifecycleHooks.SERVERCONFIG` 私有 LevelResource，字节码实证；实机启动即在此生成），没有 NeoForge 那种 `config/` 主位置 + serverconfig 覆盖层的两级语义 → `resolveConfigFile` 优先级反转（serverconfig 优先，config/ 仅作异常时序兜底）
    - **API 断层清单**（全部 javap 实证于 `forge-1.20.1-47.4.22_mapped_official_1.20.1.jar`；neoforge/fabric 1.21.1 用新 API，forge 用旧 API，**两代永久分叉**，同步只针对业务逻辑与文案）：
      - `MinecraftServer.getServerDirectory()` 返回 **`java.io.File`**（1.21.1 起才是 Path）→ 全部调用点 `.toPath()`
      - **无 `ClientInformation`**（1.20.2 才引入）：`player.clientInformation().language()` → **`player.getLanguage()`**
      - `new ServerPlayer(server, level, profile)` **三参**；`new ServerGamePacketListenerImpl(server, conn, player)` **三参**（`CommonListenerCookie` 1.20.2 才有）
      - tick 事件：`PlayerTickEvent.Post`/`ServerTickEvent.Post` → **单一事件类 + `phase` 字段**（`TickEvent.PlayerTickEvent`/`TickEvent.ServerTickEvent`，判 `phase == TickEvent.Phase.END`）；玩家取 **`event.player` 公有字段**（非 `getEntity()`，该方法只在 `PlayerEvent` 系有）；`ServerTickEvent.getServer()` 存在可用
      - 事件总线：`@EventBusSubscriber(bus = Bus.GAME)` → `@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)`；`net.neoforged.bus.api.SubscribeEvent` → `net.minecraftforge.eventbus.api.SubscribeEvent`
      - 配置：`ModConfigSpec` → `ForgeConfigSpec`（`net.minecraftforge.common`，Builder 的 comment/define/defineInRange/defineList/build 逐项签名一致，映射纯改类名）
      - 配置注册：**构造器注入 `FMLJavaModLoadingContext`**（`FMLModContainer.constructMod` 按 `getParameterCount()` 分派 0 参/1 参，字节码实证）——不要用 `ModLoadingContext.get()`，Forge 47.4 已 `@Deprecated(forRemoval)`
      - **与 1.21.1 一致、无需改动**的：`ClickEvent(Action,String)` / `HoverEvent(Action<T>,T)`（同样**无** ShowText 包装类，与坑 #32 同）、`UserBanList`/`UserBanListEntry` 5 参构造/`getSource()`、`ServerOpListEntry(GameProfile,int,boolean)`、`GameProfileCache.get(String|UUID)`、`CommandSourceStack.sendSuccess(Supplier<Component>,boolean)`/`getPlayer()`(可空)/`hasPermission(int)`、`Entity.hasPermissions(int)`/`kill()`/`moveTo(BlockPos,float,float)`/`level()`、`Level.getSharedSpawnPos()`、`BlockPos.getCenter()`、`FMLLoader.isProduction()`
    - **`@Mod.EventBusSubscriber` 已自动注册，不得再 `MinecraftForge.EVENT_BUS.register()`**：双注册会让每个事件处理器触发两次（计费翻倍、提示重复）
    - **登录欢迎语言时序是 Forge 端的逻辑差异（非文案漂移，不纳入镜像同步）**：1.20.1 **无配置阶段**，原版客户端在收到 login 包后才发 `ServerboundClientInformationPacket` → 语言必然晚于 `PlayerLoggedInEvent`，**首个 tick 未必已收到**（高延迟连接可能落到第 3~4 tick，坑 #24 的"延迟到首 tick"在此不够）。壳层改为 `WELCOME_PENDING: UUID -> 兜底截止 tick`，满足"语言已上报"或"超过 20 tick 宽限"其一即发送；"已上报"的判据是 `ServerPlayer.language` 不再等于初值 `"en_us"`（字节码实证初始化即 `en_us`，由 `updateOptions` 更新），纯英文客户端不变化则由兜底 tick 正常发英文
    - **mineflayer 实测方法（1.20.1 与 1.21.1 相反）**：1.21.1 需在 `state=configuration` 手动补发 settings（坑 #24）；**1.20.1 不需要**——mineflayer 的 settings 插件正是在 `bot._client.on('login')` 发包，与原版 1.20.1 时序一致。只需设 `bot.settings.locale`（插件默认 `en_US`，且**不读** `createBot` 的 `options.locale`），且 `bot.settings` 由插件注入阶段创建、createBot 后并非立即可用 → 用 `bot.once('inject_allowed')` + `bot._client.prependListener('login')` 双兜底。另：1.20.1 的 `system_chat` content 是 **JSON 字符串**（1.20.3+ 才是 NBT），直接 `JSON.parse` 即可断言 `color`/`clickEvent`/`hoverEvent`
    - 1.20.1 **无** `pause-when-empty-seconds`（1.21.2 才引入），不受坑 #33 的 dev 自动暂停影响
    - **验证**：common 96 测试不变；`build` 四模块打包（jar 内含 common 类 + 壳层类，class 版本 61）；`:forge:runServer` rcon 冒烟全链路通过——计费 6.0=3×(1.0×2.0 高速)、调低上限当场踢出、`banned-players.json` source=ChunkPlan、恢复上限后 scanBans 30s 内自动解封、单档 reset 保留他档、零线（window all off）+ 解封、windowTime/windowLimit/highSpeedMultiplier 原子写入且无重复键、reload 热生效、扣费日志仿原版 gzip 轮转。**mineflayer 1.20.1 实机**：中/英登录欢迎按 locale 正确渲染、游玩中踢出收到完整 ban 公告、被 ban 后重登被原版 `multiplayer.disconnect.banned.reason` 拦截、15% 阈值提示的规则链接样式（黄 + `run_command` + `show_text` 悬停）正确
39. **Forge 配置重启回退（坑 #39，2026-08-15 用户 Forge 实机暴露）**：Forge 1.20.1 端 `/chunkplan config` 修改（exemptByDefault/window/windowTime/windowLimit/highSpeedMultiplier）在关服重启后**恢复默认**。
    - **根因（fmlcore 47.4.22 字节码实证）**：Forge 平台层在**关服**（`ServerLifecycleHooks.handleServerStopped` → `ConfigTracker.unloadConfigs` → `ModConfig.save()`）与**启动加载后**（`ConfigTracker.openConfig` 内 save）都会把 **spec 内存值无条件写回** `<world>/serverconfig/chunkplan-server.toml`；mod 命令只做「原子改文件 + 重读文件进引擎（`loadAndApplyConfig`）」，从未同步 spec 内存（全项目零 `ConfigValue.set()`）→ 关服用启动默认值覆盖文件 → 重启回退。Forge 的配置 watcher（`ConfigWatcher`，约 500ms 防抖异步重载文件新值）若抢在关服 save 前完成则碰巧保留——竞态，非稳定生效
    - **修复**：`ForgeConfig.syncSpecFromFile`（FileConfig 重读文件、21 个 spec 键逐键 `ForgeConfigSpec.ConfigValue.set` 回 spec 内存——javap 实证 1.20.1 的 set 为 public 且只改内存）挂在 `loadAndApplyConfig`（config 命令族全部写路径 + reload 的唯一汇合点），平台任何写回都与文件一致
    - **平台差异（不四端同步）**：NeoForge 21.1 `unloadConfig` 只移除 watcher 从不写回、`ModConfig` 无 save()（文件即事实源），Fabric 为 mod 自有 JSON 无平台配置层——同款命令代码在其它三端重启保留（用户实测一致），故本修复**仅 Forge 端**；NeoForge 21.1 的 `ConfigValue.set` 语义也不同（不落盘），勿照搬
    - **「单窗口刷新连坐全窗口」排查结论**：common 逐行核查（tier 键控分桶 / 窗口无状态现算 / 清理按 tier 独立）确认**不存在**该代码路径；Forge 端观察到的「所有窗口一起变」是坑 #39 的表现形式（整个文件被关服写回默认，4 档同时回退默认值），修复后应消失
    - 坑 #12 提示扩展：Forge 端「运行中手改文件不 reload 即关服」仍会被关服 save 覆盖（spec 未同步到文件新值），需停服修改或改后 reload

40. **额度线改为固定周期重置（坑 #40，2026-08-21，数据格式 v3）**：用户实测暴露旧滚动窗口缺陷——恢复时间只保证"最早消费桶滑出"，若该桶金额小于超出量，玩家到提示时刻重进仍被拒、恢复时间逐分钟后移（22:06 满 → 提示 22:07 → 到点只重置个位数又被踢 → 提示 22:08）。用户拍板改为**固定周期**：
    - **语义**：每档 tier(1~4) 独立维护 `{cycleStartMillis, spent}`；首次消费锚定周期起点（`now/60000*60000` 对齐整分钟——恢复时间恒落整分，与文案 HH:mm 精确一致）；周期终点 = 起点 + **当前配置**窗口长（现算，`config windowTime` 改动立即影响进行中周期：缩短即提前清零解封，由 scanBans 30s 内自动执行；与坑 #30"当场生效"哲学一致）；到点**整窗清零**（等价 `/chunkplan reset`），与累计多少无关。判满仍 OR 语义（任一满即拒，坑 #25）；恢复时间 = 各满线周期终点的最晚者，"您最早可于 X 再次进入服务器"精确兑现
    - **实现**：`PlayerQuotaData` v3（`tiers: Map<Integer,TierCycle>` 替代 v2 分钟桶；API `recordSpend`/`expireIfNeeded`/`effectiveSpent`/`cycleStartMillis`——读路径纯读不清、记账前惰性过期重锚，过期数据残留文件无害因所有读取按时间现算）；`QuotaEngine.recoveryMillis(data)` 删 firstKey/firstBucketAtOrAfter 逻辑；`cleanupExpiredBuckets` 删除（每档 O(1) 无需清理）。**壳层四端 + fabric-multi shared + GuiStatus 协议零改动**（公共接口 `clearTierSpendForAll`/`saveAll` 签名不变，`LineStatus` 字段形状不变）
    - **迁移**：`VERSION=3`；v1/v2 玩家数据升级保留 explored、丢弃消费记录（滚动窗口分钟桶无法映射为固定周期，与 v1→v2 先例一致，加载时告警）；`clearTierSpendForAll` 离线改写门禁只处理 v3（旧版留待玩家下次加载时迁移）；升级瞬间正在封禁的玩家随消费清零被 scanBans 自动解封
    - **测试**：common 107 → 115（新增 `partialSlideDoesNotShiftRecovery` 用户场景回归 / `windowTimeShortenAffectsOngoingCycle` / `resetClearsAnchorAndReAnchors` / v2 迁移；`banClearsWhenWindowSlides`→`banClearsFullyAtCycleEnd`、`recoveryConsidersEarliestBucketAcrossMinutes`→`recoveryUsesLatestFullLineCycleEnd` 改写）。**测试写时钟注意**：TestClock 起点位于某分钟的第 40 秒，锚点对齐整分后短窗口（60s）的剩余周期只有 20s——跨分钟推进时钟需用长窗口配置，否则提前过期重锚

41. **GUI 背景模糊后处理重复调用（坑 #41，2026-08-22 用户实机暴露）**：ChunkPlanGuiScreen 曾既显式调用背景又调 `super.*`——分代差异（javap 实证）：
    - **1.21.1 世代（neoforge/fabric 1.21.1）**：`Screen.render` 本身 = `renderBackground`（含 `processBlurEffect` 模糊后处理）+ renderables 循环；旧代码显式 `renderBackground` 后再 `super.render` 等于**每帧执行两次模糊**，第二次采样到当前帧刚画的内容 → 用量页整页模糊、管理页左列标签以幽灵重影残留背景（用户截图实证）。修复：`super.render` 前置（背景+控件先、页面内容后——布局经核查无重叠），**内容画在控件之上**
    - **1.21.11/26.x**：`Screen.render`/`extractRenderState` 只剩 renderables 循环，背景移到**入口 wrapper**（`renderWithTooltipAndSubtitles`/`extractRenderStateWithTooltipAndSubtitles`，先背景后 render）+ 分层（`nextStratum`）——**screen 内显式调用背景即重复**，vanilla 屏幕（如 ChatScreen）均不自调；修复=删除显式调用
    - **forge 1.20.1**：Screen.render 只遍历 renderables、无背景 wrapper（1.20.1 无模糊，renderBackground 只是渐变暗幕+Forge 事件），显式调用唯一正确，**保持原样**
    - 自查口诀：javap `Screen.render` 开头是 `invokevirtual renderBackground`（1.21.1 世）还是直接遍历 `renderables`（1.20.1/1.21.11/26.x）；带 `renderWithTooltip[AndSubtitles]` 的 wrapper 仅 1.21.11/26.x 存在
    - 测试与模拟验证均无法覆盖（GUI 渲染被环境阻断，坑 #11）→ 该 bug 六端 GUI 首次实机才暴露；`renderables` 在原版是 private（NeoForge 的 public 来自其 AccessTransformer）

- 代码注释默认中文；common 不 import 任何 MC/加载器类（单测在 common 模块）
- 壳层薄：业务逻辑全部在 common，壳只做事件接线 / 配置映射 / ban 执行
- 各端配置结构保持一致（TOML 与 JSON 字段一一对应；forge 与 neoforge 的 TOML 键完全相同）
- 各端 `DevCommands`/`QuotaCommands`/`applyBan`/`scanBans` 逐字重复（架构决定无法下沉 common）→ **改一处必须同步其余各处**（四处：neoforge、fabric/1.21.1、fabric-multi/shared、forge/1.20.1，坑 #33/#38；API 差异行除外）。`ChunkPlanMessages` 在 forge 端与 neoforge 端**除 package 行外逐字一致**（无版本相关 API），改文案后可用 `diff <(tail -n +2 …neoforge/ChunkPlanMessages.java) <(tail -n +2 …forge/ChunkPlanMessages.java)` 自检
- MC 版本范围：neoforge 与 fabric 1.21.1 仅 1.21.1；forge 仅 1.20.1（坑 #38）；fabric-multi 额外支持 1.21.11/26.1.2/26.2（坑 #33），不得再放宽到未测试版本
- 不做：mixin、客户端内容、指令式配置；26.x 迁移只适配壳层（fabric 侧已落地为 fabric-multi，neoforge 与 forge 各自单版本）
