# AGENTS.md — ChunkPlan（探索额度）

## 项目简介

ChunkPlan（modid=`chunkplan`，中文名「探索额度」）是**纯服务端** Minecraft mod：按**玩家实体踏入区块**计费，通过多条滚动窗口额度线限制玩家的探索消耗，额度耗尽后临时封禁（窗口滑出自动恢复）。用于控制服务器区块加载成本。

- 双加载器：**NeoForge 1.21.1** + **Fabric 1.21.1**（独立 mod，不依赖任何功能型上游 mod；Fabric 端依赖 `fabric-api` 生态基础设施——生命周期事件/命令注册/连接事件，`fabric.mod.json` 的 `depends` 已声明，NeoForge 端无额外依赖）
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
| 费率 | 集合外 `1.0` / 集合内 `0.05`；高速（>0.5 格/tick，严格大于 = 创造模式飞行速度 ~0.54，地面疾跑 ~0.28 不触发）再 ×2 |
| 挂机 | 零计费；登录首 tick 只记基准不扣费；边界踱步重复计费**接受，不加防抖** |
| 额度线 | 四档滚动窗口线（每档独立开关 + 窗口预设校验，坑 #24），**任一窗口满即拒**（坑 #25）；默认仅开第一档 `5h≤500` + 第二档 `24h≤2000` |
| 耗尽处理 | 临时 ban：游玩中当场踢出；纯窗口滑出自动恢复，不设最短 ban 时长 |
| 豁免 | 默认 OP + 配置名单豁免，`exemptByDefault=false` 时全员受限 |
| 集合 | 个人已探索集合**终身保留**、按维度分区；传送/瞬移只计落点 |
| 持久化 | `world/chunkplan/players/<uuid>.json` 每玩家一文件，定期（5min）+ 离线 + 关服落盘，原子写 |
| 配置 | 数值全部可配；**不做指令配置**（唯一例外：`/chunkplan config exemptByDefault [true|false]`，gamerule 风格，无参查询/带参设置并原子写回配置文件，反馈不显示文件路径）；`/chunkplan check|reset <player>|confirm|reload`（reset/confirm/reload 权限 2；reset 需 confirm 二次确认） |
| 登录欢迎 | 进服（未被额度拦截）自动发送一次 check 状态 + `查询额度请使用 /chunkplan check 命令` 提示，按玩家客户端语言；客户端可视化入口行二期再加 |
| 日志 | 扣费事件写独立 `logs/chunkplan.log`，不污染默认日志 |

## 计费与额度线算法（common/QuotaEngine，防实现偏差）

1. 首 tick（prevChunk==null）只记录基准；否则每 tick：speed = 与上 tick 的三维位移
2. 区块或维度变化 → 计费：先查集合定费率（不在集合**先加入集合**），× 高速倍率，累加进分钟桶（`epochMinute -> 点数`）
3. **先记账后判踢**：任一额度线满（`spent > limit`，窗口 `(now-window, now]`）→ 返回 BAN（恢复时间；文案由壳层按玩家语言渲染，坑 #22）
4. 恢复时间 = 各满线 `窗口内最早消费桶 + 窗口长` 的**最晚者**
5. 过期桶清理：早于最长窗口线 2 倍的桶删除
6. 配置校验：线数 1~4、窗口/上限为正、费率非负，非法回退默认并告警

## 构建与验证

```bash
export JAVA_HOME="D:\Games\ABOUT_MINECRAFT\JAVA\zulu21.44.17-ca-jdk21.0.8-win_x64"
.XMTEMP/gradle-8.14.2/bin/gradle :common:test        # 引擎单测（76 个，全绿为验收前提）
.XMTEMP/gradle-8.14.2/bin/gradle build               # 三模块编译 + 打包
.XMTEMP/gradle-8.14.2/bin/gradle :neoforge:runServer # dev 服务器（工作目录 neoforge/runs/server/）
.XMTEMP/gradle-8.14.2/bin/gradle :fabric:runServer   # dev 服务器（工作目录 fabric/run/）
```

- 首次启动需 `eula.txt`（`eula=true`）；run 目录已被 gitignore 排除
- **NeoGradle/Loom 均不转发 stdin 给服务器控制台** → 命令验证用 rcon：dev 环境改 `server.properties` 开 rcon，用 `.XMTEMP/rcon.py` 发命令（脚本不入库）
- 玩家行为实测（踏入计费/踢出/ban 全链路）需要游戏客户端，服务端启动与命令链路可无客户端验证

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
10. **分钟桶粒度**：消费记入 `epochMinute` 桶，窗口按桶滑出 → ban 实际持续 ≈ 消费所在分钟结束 + 窗口长（比精确窗口**短最多 60 秒**，双端实测一致，是设计语义非 bug）
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
    - 额度线以四档呈现（每档 `enabled`/`window`/`limit` 三个标量字段，TOML 与 JSON 各 12 键），窗口受预设约束（第一档 30m~12h、第二档 12h~7d、第三档 7d~30d、第四档 30d~365d；预设外/非法窗口、limit≤0 → 回退该档默认并告警；全部档禁用或非法 → 回退默认两条 5h/500 + 24h/2000）。档位→额度线组装唯一实现于 common `QuotaTiers.toLines`（壳层只做格式转换，避免双端漂移）；引擎仍只消费 `List<Line>`
    - 高速阈值默认 **0.5 格/tick**（=10 格/秒，略低于创造模式飞行 ~10.8 格/秒 ≈0.54 格/tick，达到创造飞行即触发 ×2；地面疾跑 ~0.28 不触发）。注意引擎速度是**三维欧氏距离（含 Y 分量）**，高处坠落跨区块也计高速
    - 登录欢迎：未被额度拦截的玩家进服自动收到一次 check 状态 + `查询额度请使用 /chunkplan check 命令` 提示（按玩家语言）；渲染复用 `ChunkPlanMessages.checkStatusText`（与 `/chunkplan check` 同一实现，`/chunkplan check` 输出格式不可漂移）；客户端可视化入口行留待二期。**欢迎须延迟到玩家首个 tick 发送**：原版客户端在配置阶段上报 client_information（含 locale），服务端 `ServerConfigurationPacketListenerImpl.handleClientInformation` 在 `getPlayerForLogin`/JOIN 前已更新（1.21.1 源码实证）——JOIN 时语言本就正确，但延迟到首 tick 对"配置阶段未上报、进 play 立即上报"的客户端也更稳健；壳层用 `WELCOME_PENDING`（登录时登记、首个 tick 移除并发送、登出清理）
    - mineflayer 库缺陷：其 `settings` 包在进 play 后才发（晚于服务端 JOIN），不在配置阶段上报语言 → 用它验证登录欢迎**必须**在 `state=configuration` 时手动 `write('settings', {locale})` 模拟原版（`.XMTEMP/real-client/verify-welcome.js` 已固化）；原生 `minecraft-protocol` createClient 在 NeoForge 1.21.1 配置阶段会卡住（不做额外处理时服务端不发 finish_configuration），勿用
    - **聊天框禁止显示配置文件路径**：reload 与 config 命令反馈均不含路径（路径只进服务端日志），`loadAndApplyConfig` 返回 `List<String>` 告警列表
    - 旧配置迁移：四档改造后旧 `lines`/`lineLimits` 键成为孤儿键（NeoForge 文件里残留但不生效），`highSpeedThreshold` 旧值（如 1.0）持久化覆盖新默认 → 升级需删除旧配置文件（`config/chunkplan-server.toml` / `config/chunkplan.json`）重新生成，否则新默认不生效
25. **任一窗口满即拒（规格变更）**：原设计"全部额度线同时超限才拒"（AND 语义），用户实测发现单线超限（如 5h 500.9/500.0）仍显示"未满，可正常探索"，确认是设计失误 → 改为**任一窗口满即拒**（OR 语义，`isAllLinesExceeded` 与 `quotaStatus.allExceeded` 的循环条件由"存在未满线→false"反转为"存在满线→true"）。`recoveryMillis` 不变（只遍历满线取最早桶+窗口的最晚者，恢复时刻保证满线滑出；未满线不参与）。字段名 `allExceeded` 保留（壳层双端引用，注释注明"任一满"语义）。恢复时间只保证"满的那条线滑出"，玩家恢复后再次探索可能再次触发该线 → 属正常行为。check/ban 文案不变，各线状态全部列出可看出哪条满
26. **ban 公告排版（坑 #26）**：踢出消息按用户模板排版——标题（§c 红，禁止警示）+ 原因行（§c，满线中**窗口最长者**，如 5h 与 1d 同时满显示"1天内 探索额度上限 已耗尽"）+ §7 分割线（44 个 '-'，适中防自动换行）+ "您的探索额度情况："（§e 黄小标题）+ 各线状态（§f 白；满线"（§c已满，下次重置时间：X）"、未满线"（§7下次重置时间：X）"）+ 分割线 + "您最早可于：X 再次进入服务器"（§a 绿）+ 结尾感谢/咨询（§7 灰）。**每线显示各自独立的下次重置时间**（= 该线窗口内最早消费桶 + 窗口长，未满线也有——额度线互相独立、可能跨天不同步，如 5h 满 08-13 21:24 而 1d 未满次日才重置）：引擎 `QuotaStatus.LineStatus` 增加 `nextResetMillis` 字段（`quotaStatus` 逐线计算，无消费为 -1），`recoveryMillis` 保持只算满线。标签列按显示宽度对齐（CJK/全角 2 格、ASCII 1 格，`displayWidth`）；窗口显示名用"5小时内/1天内/30天内"（`windowName`，区别于 check 的 `formatWindow` "5h/1d" 简写）。双端 `ChunkPlanMessages.banMessage` 重写，改文案必须双端同步
27. **JSON 损坏 .bak 备份兜底（坑 #27）**：所有 JSON（玩家数据 `players/<uuid>.json`、管理名单 `chunkplan-managed-bans.json`、Fabric 配置 `chunkplan.json`）写入统一走 `AtomicFile.write`——**写前备份**：目标存在时先把现有文件复制为 `<file>.bak`（覆盖式，同一文件 .bak 最多一个，恒为上一份完好数据）；读取经 `AtomicFile.readJson` 兜底：主文件 parse/IO 失败时尝试 `.bak`，成功则从 .bak 恢复并立即用 `writeNoBackup` 写回主文件修复现场——**恢复写回必须跳过写前备份**，否则损坏的主文件会覆盖唯一的好 .bak；主与 .bak 均失败才走原降级（重建/空名单/回退默认+告警）。**版本不符不尝试 .bak**（.bak 同版本也会不符，恢复无意义，保持重建+告警）。NeoForge 的 TOML 配置由 NightConfig 生态自管（坑 #12），不纳入本机制。Fabric 配置 IO 失败从"中断启动"改为"告警回退默认"（与 NeoForge reload 语义对齐）。实现位置：`AtomicFile.write/writeNoBackup/readJson`、`QuotaEngine.loadOrCreate`、`ManagedBanStore.load`、`FabricConfig.load`；测试 55 → 64（新增 `AtomicFileTest` 6 个 + 引擎/管理名单恢复测试 3 个）
28. **额度百分比阈值提示（坑 #28）**：额度达到窗口上限百分比时提示玩家（**每窗口独立计算**，仅瞬态内存不落盘）。触发档位严格为 15/30/50/65/75/80/85/90/95/98（引擎 `ALERT_PERCENTS` 表，改档须用户确认）；严重度 15~30 低（§a 浅绿）/50~75 中（§e 黄）/80~98 高（§c 红）。**触发语义**：每窗口每档只提示一次；档位上升跨过新档时**逐档**生成（一次 +33% 会发 15、30 两条）；额度重置/滑出后档位回落，重新涨回再触发；**首见（登录/重连/服务器重启后首个 tick）只初始化当前档位不触发**，避免补发历史档位刷屏（`AlertState.initialized`）；豁免玩家不提示且清状态（`alertStates.remove`）。架构：引擎 `TickResult` 增加 `List<WindowAlert>` 字段（`none(List<WindowAlert>)` 工厂，`ban` 保持无 alerts——BAN tick 不发提示，ban 消息已充分说明），壳层 `handlePlayerTick` 逐条 `sendSystemMessage`。消息：差异内容整体按严重度色、窗口名与百分比字段 §b 浅蓝覆盖；固定尾部白色含 `/chunkplan check`（§a 浅绿）与**超链接"点击此处查看详细计费规则"**——点击事件 `RUN_COMMAND "/chunkplan rules"`（本项目首个 ClickEvent），`/chunkplan rules` 子命令无权限要求，渲染 5 条计费规则 + 结尾注意，数值取管理员配置（`String.valueOf` 保留 1.0 / 0.05 / 2.0x 原样）。双端 `ChunkPlanMessages.quotaAlertMessage`（返回 Component）/`rulesMessage` 逐字同步；测试 64 → 72（`QuotaEngineTest` 增 8 个：逐档触发/严重度映射/窗口独立/首见不刷屏/reset 重触发/跨档逐条/豁免清状态/BAN 无提示）
29. **check 未满行档位化（坑 #29）**：`/chunkplan check`（与登录欢迎共用）未满时不再固定显示"未满，可正常探索"，改为**档位词 + 微调句子**：**充足（0≤pct<50，§a）/ 中等（50≤pct<75，§e）/ 不足（≥75，§c）**——用户三轮修正定稿的**连续百分比区间**（达到 50 即中等、达到 75 即不足；75 档归不足区，与坑 #28 alert 严重度分段[75 为中]无关，check 档位词按 percent 区间独立判断）。档位判定：**跨窗口取当前百分比最高档位**，现算跟随当前状态（额度滑出/重置自动回落显示"充足"，不保留触发历史，与 alertStates 解耦）。架构：`QuotaStatus` record 增加 `worstAlert` 字段（`quotaStatus()` 唯一构造点复用 `currentLevel()` 逐线算档位取最高；无档 null），双端 `ChunkPlanMessages.checkStatusText` 未满分支按 `worstAlert().percent()` 区间渲染（签名不变，调用点零改动，欢迎消息同样显示档位词）；满线"已耗尽"分支优先。测试 72 → 76（`QuotaEngineTest` 增 4 个：无档 null/档位-百分边界 15-30-50-62.5-75-87.5-100/跨窗口取最高/滑出回落）

## 约定

- 代码注释默认中文；common 不 import 任何 MC/加载器类（单测在 common 模块）
- 壳层薄：业务逻辑全部在 common，壳只做事件接线 / 配置映射 / ban 执行
- 双端配置结构保持一致（TOML 与 JSON 字段一一对应）
- 双端 `DevCommands`/`QuotaCommands`/`applyBan`/`scanBans` 逐字重复（架构决定无法下沉 common）→ **改一处必须同步另一端**
- MC 版本范围已收紧为仅 1.21.1（双端元数据），不得放宽到未测试版本
- 不做：mixin、客户端内容、指令式配置、多 MC 版本；26.x 迁移只适配壳层
