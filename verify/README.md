# 验证器（组合单位 / combineunit）

改动**必须**跑这里的东西再下结论，别只靠读代码。三层，从便宜到贵：

| 层 | 命令 | 用时 | 能验什么 |
|---|---|---|---|
| 编译 | `./gradlew --offline deploy` | ~4s | 语法/编译（产物含 classes.dex，安卓可装） |
| headless 逻辑测试 | `verify/run-headless.sh mx /tmp/mp_unit/data combineunit.dbg.MegaEnvTest` | ~10s | 合体/巨兽属性/同步/存档/承伤/火力 —— 在**真实游戏类**里跑 |
| 真客户端截图 | `verify/run-client.sh mx /tmp/mp_unit/data mega` | ~1.5min | 巨兽画不画、血条/盾条、面板图标、腿/机甲腿、水阻 |

## 0. 数据目录：**只装 combineunit.jar**

```bash
verify/make-dataset.sh /tmp/mp_unit/data      # 产物 build/libs/combineunit.jar 会装进去
```

组合单位测试必须在"只有 combineunit"的数据目录里跑：装了 combine 的话，单位侧机制会来自
combine 的那一份，本仓库的改动根本没被验到。`run-headless.sh` / `run-client.sh` 只要看到
`data/mods/combine.jar` 就直接拒绝（exit 4）。

## 1. headless 逻辑测试（12 项）

```bash
for t in SanityCheck MegaEnvTest MegaFieldTest MegaWaterTest MegaMiningTest MegaPayloadTest \
         MegaStatSumTest MegaSurviveTest MegaSyncTest MegaGhostMemberTest MegaClipSizeTest \
         MegaGhostTest ComboFireSupportTest; do
  verify/run-headless.sh mx /tmp/mp_unit/data combineunit.dbg.$t
done
```

| 测试 | 验什么 |
|---|---|
| `SanityCheck` | 防呆：模组真的加载、三种巨兽类型建出来了、巨兽实体类登记在**固定槽 250**、原版单位实体被换成本仓库的镜像类（`dagger` → `combineunit.units.entities.CMechUnit`）。每个 run-headless 前自动跑 |
| `MegaEnvTest` | 埃里克尔地图（`Env.scorching\|terrestrial`）上合体不环境死亡：占位类型支持该环境、派生类型按成员推导（`envDisabled` 不含 scorching）、2 秒后仍存活 |
| `MegaFieldTest` | 力场合并成 1 份（力墙条不超 100%）、引擎按体型缩放、`flyingLayer`/`clipSize` 不是 -1、船的水阻/速度、poly 建造速率 2×、指挥类型按 `type.id` 查回来是巨兽 |
| `MegaWaterTest` | 用户报的"ElevationMoveUnit 的实体（elude）合体后淹死"：原版溺水判定一半看**类型**（`UnitEntity.canDrown() = isGrounded() && type.canDrown`），海军与悬浮单位（`ElevationMoveUnit`，elude：`flying=false`、`canDrown=false`）本来就不进溺水分支；巨兽实体是普通 `UnitEntity`、派生类型默认 `canDrown=true`，于是成员不淹、合体后开始淹（修前实测：合体 2 秒 `drownTime` 0.005→0.31，按 deep-water 的 `drownTime=200` 约 6 秒就该掉血/淹死）。现在派生类型的 `canDrown` 按成员**取交集**（有一台不淹就不淹）。四条断言：①单只 elude 在深水上不淹（前置）；②两只 elude 合体后 8 秒不死、不掉血、`drownTime` 一直是 0；③纯陆地编组（dagger×2）照旧会淹（没被一刀切成全体免淹）；④混合编组（elude + dagger）照样不淹 |
| `MegaMiningTest` | 矿工巨兽：物品容量=成员之和（90=3×30）、`drawMineBeam`、光束起点不是 -Inf、真的挖得到东西、成员表丢了也不退回占位类型 |
| `MegaPayloadTest` | 巨兽能被原版载具装进载荷黑洞销毁；成员丢了的巨兽不能退化成占位类型（图标不变） |
| `MegaStatSumTest` | 建造/挖矿速率按成员**量行为**累加（1/2/3 台 = 1×/2×/3×）；客户端按 `EntityMapping + readSync` 造出来的副本也一样；力场实例/展开状态不被快照重建 |
| `MegaSurviveTest` | 地图内合体 2 秒后仍存活（机甲/机甲+海军/爬虫三种构成）；地图外合体被原版清理（老剧本的坑） |
| `MegaSyncTest` | 探雾/小地图/同步/存档；别的模组注册自定义实体后巨兽 id 不变（固定槽 250）；成员按名字读回；新旧三种成员格式都能读 |
| `MegaGhostMemberTest` | 客户端幽灵单位两路一起验：①"巨兽已出现、成员还没被通知移除"的竞态 —— 幽灵成员摘干净、巨兽不误删、同 id 不同类不误删；②**id 复用**（用户报的"客户端生成幽灵单位"）：实体 id 就是 `EntityGroup` 的空位下标，成员被收进巨兽后这些 id 立刻被新单位复用（LIFO，常常正好是刚合掉那两个、类型也一样）—— 只按 (id, 类型) 摘人会把刚造出来的正经单位删掉（删了又建、建了又删 = 一闪一闪的幽灵单位）；现在真幽灵靠**位置指纹**判定，玩家操控的单位一律不碰 |
| `ComboFireSupportTest`（+ combine 仓库的 `combine.dbg.MegaRepairFireTest`） | 借火只许打敌方目标：维修/建造武器跟踪的是**己方建筑**、玩家长按维修时瞄准点也压在自家房子上，照着打就会把同组其他成员的武器引到己方建筑上（用户报的"mega 武器去修复建筑的时候，其他武器的开火会损坏己方建筑"）。实测修前己方半血墙被借火打 14 发，修后己方目标/瞄准点 0 发、敌方目标照常 14 发；巨兽自己 `groupable=false`（不参与借火） |
| `MegaClipSizeTest` | 给巨兽排建造计划后 `clipSize` 不崩（视口裁剪用） |
| `MegaGhostTest` | 合体后不是本地幽灵、控制器/指令表/姿态齐全、移动指令生效、解体后成员回世界、编组入口（comboId）行为 |
| `ComboFireSupportTest` | 组合火力共享不借治疗类武器代打（带治疗的弹体必须为 0），自己的武器照常开火 |

约定：`combineunit.dbg.*` 的测试类放在 `verify/tests/`，`run-headless.sh` 会一起编译；
测试通过反射访问模组类（`Vars.mods.getMod("combineunit").main.getClass().getClassLoader()`）。

## 2. 真客户端截图（绘制/面板类改动必跑）

```bash
verify/run-client.sh mx /tmp/mp_unit/data mega       # mace + 2×oct + poly 融合
verify/run-client.sh mx /tmp/mp_unit/data legs       # spiroct + arkyid（腿）
verify/run-client.sh mx /tmp/mp_unit/data mech       # dagger + fortress（机甲腿）
verify/run-client.sh mx /tmp/mp_unit/data duo        # dagger + vela（碰撞箱/武器/治疗光束）
verify/run-client.sh mx /tmp/mp_unit/data shipmega   # 两艘 risso 在深水里融合（水阻）
```

截图落在 `~/sd/shots/`（脚本自动建目录、文件名带跨次运行的连续序号 `001_` `002_`…），
必须用看图工具确认，别只看日志：

| 模式 | 图 | 看什么 |
|---|---|---|
| mega | `*_mega_before/world/after.png` | 融合前后世界：巨兽身体画出来没有、**fullIcon 用的是代表成员的整图**（`unit-oct-full`） |
| mega | `*_mega_hud.png` | 操控巨兽时的 HUD（单位图标是不是代表成员的） |
| mega | `*_mega_panel.png` | 信息面板：血/盾、力场条不超上限（1.0） |
| mega | `*_mega_command.png` | 指挥模式图标核对：左=面板会用的图标（=代表成员），右=dagger（旧 bug 对照）；命令按钮是成员指令的并集 |
| legs/mech | `*_legs_mega.png` / `*_mech_mega.png` | 腿/机甲腿按体型放大（参照单位同图对比：腿展 46~48 vs 参照 15） |
| duo | `*_duo_fight*.png` | 碰撞箱、治疗光束、武器开火 |
| shipmega | `*_ship_mega.png` | 巨兽浮在深水上、地形速度系数和原版船一致（1.3） |

驱动 mod（`verify/client/Driver.java`）的模式场景是从 combine 仓库搬过来的（拆仓后单位侧只在本仓库）；
建筑侧那些模式（设置列表/CoopPanel/电网/科技树…）留在 combine 仓库的 Driver 里。

## 3. 换 jar / 换版本

```bash
MINDX_JAR=/path/to/other.jar  verify/run-headless.sh mx /tmp/mp_unit/data combineunit.dbg.MegaEnvTest
MINDUSTRY_JAR=/path/to/Mindustry.jar verify/run-client.sh vanilla /tmp/mp_unit/data mega
```

官方 160.1 的客户端/服务端 jar 可以这样取（headless 用的官方后端就在服务端 jar 里）：

```bash
curl -L -o /tmp/server160.jar https://github.com/Anuken/Mindustry/releases/download/v160.1/server-release.jar
curl -L -o /tmp/mind160.jar   https://github.com/Anuken/Mindustry/releases/download/v160.1/Mindustry.jar
```

## 4. 交付

```bash
verify/deliver.sh        # = 兼容安卓编译 + 检查调试残留 + 只把 jar 放到 ~/sd/combineunit.jar
```

## 5. 这台机器是 proot：长跑工具不许每事件起线程

软渲染客户端一帧很慢（~5fps），别用 0.5 秒小间隔下结论。联机/长跑工具（代理、看门狗）必须
"线程数与事件数无关"，超上限就主动退出 —— 否则会把 proot（单线程 ptrace 事件循环）拖成活锁，
整个会话（含 codex 自己）一起冻死。细节见 combine 仓库 `AGENTS.md` 与 `/root/.codex/AGENTS.md`。
