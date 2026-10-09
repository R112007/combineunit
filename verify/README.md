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

## 1. headless 逻辑测试（28 项）

```bash
for t in SanityCheck MegaEnvTest MegaFieldTest MegaWaterTest MegaHoverTest MegaHoverPathTest MegaBigTest \
         MegaLegsTest MegaWeaponLayoutTest MegaPlaceholderTest MegaMiningTest MegaPayloadTest MegaTankTest MegaBoostTest \
         MegaPossessLoadTest ScriptModCompatTest MegaFlightRuleTest \
         MegaStatSumTest MegaSurviveTest MegaSyncTest MegaGhostMemberTest MegaClipSizeTest \
         MegaGhostTest ComboFireSupportTest MegaNullControllerTest MegaNullTypeTest \
         MegaBuilderAiNpeTest CoreUnitSyncTest MegaFlyingPathTest MegaHoverStuckTest \
         MegaTurretFireTest MegaTurretCheatFireTest \
         MegaCampaignWaveTest MegaCampaignSweepTest MegaUserSaveWaveTest; do
  verify/run-headless.sh mx /tmp/mp_unit/data combineunit.dbg.$t
done
```

（`MegaCampaignWaveTest` / `MegaCampaignSweepTest` 要数据目录里同时有 combinec.jar，例如
`verify/make-dataset.sh /tmp/mp_campc/data /root/combinec/build/libs/combinec.jar`；
没有 combinec 时它们打印 SKIP 并 exit 0；`MegaCampaignSweepTest` 默认扫第 1~20 波，
`-Dfrom=` / `-Dto=` 可改范围。）

| 测试 | 验什么 |
|---|---|
| `MegaFlyingPathTest` | 用户报（组合战役 combinec）"**组合敌方波次之后，如果那一波里有一些别的类型的单位，合出来的组合巨兽不会向玩家核心进攻，而是停在原地**（tarFields 第 34 波）"。根因：派生类型的 `flowfieldPathType` 照抄原版 `initPathType()` 的"naval → **allowLegStep** → **flying** → hovering → ground"，把腿类排在飞行前面 —— 原版没问题是因为原版一个类型不可能同时是腿类和飞行，而派生类型是"成员能力的并集"（tarFields 那一波就是 spiroct 给腿 + horizon 给飞机）：巨兽能飞 → 每帧 `elevation` 钉在 1、**永远悬空**（悬空不撞墙，所以它经常就悬在天然岩壁/悬崖那一格上），却拿着腿类的代价表 —— `costLegs` 对天然岩壁返回 impassable，流场在自己脚下就是 -1，`Pathfinder.getTargetTile` 把"当前格"当下一格返回、`AIController.pathfind` 见 `tile == targetTile` 立刻 return：**一步都不走**，而且不离开这格就永远走不了。修法：流场代价类型的优先级里飞行提到腿类前面（原版飞行单位本来就是 `flying → costNone`：全图平坦代价、格子永远可达），腿类只在**不能飞**的编组里生效。判定：①口径钉子 —— 天然岩壁格 `costLegs=-1 / costNone=1`；②能飞+有腿 → `flowfieldPathType=costNone`，纯腿仍 `costLegs`、纯陆地仍 `costGround`（能力没被一起改掉）；③**复现 + 修复**：把能飞+有腿的巨兽摆在天然岩壁上，先按旧行为强制 `costLegs` → 900 tick 位移 1px、速度 0.00（复现用户报的现象），恢复 `costNone` 后 900 tick 从 1846px 逼近到 1015px；④顺带钉住指挥模式（CommandAI + ControlPathfinder）那条路没被改坏：原版 flare 悬在岩壁上被指挥走 1587px、巨兽 657px（速度 1.05 × 600 tick） |
| `MegaHoverStuckTest` | 任意 | 用户补报"**tarFields 第 10 波也出现同样的问题**"。第 10 波是 `nova×3 + crawler×6` —— **nova 带 canBoost**，派生类型继承 canBoost（`ct.canBoost = anyBoostMember && !ct.flying`），原版 `updateBoosting()` 在**实心格**上让它自动升空（`shouldBoost = boost \|\| onSolid() \|\| ...`）→ `isFlying()=true`、`solidity()=null` 所以活着，而它的流场是 costGround（天然岩壁 = impassable）→ 自己脚下这格权重 -1 → `AIController.pathfind` 每帧直接 return = 停在原地。修法：新增 `combineunit.units.mega.MegaGroundAI`（巨兽的 `aiController`）—— 流场给出死路（下一格 == 当前格）时**直接朝最近的敌方核心推进**，脱困后流场照常接管；正常路径完全走原版逻辑（地貌绕行不受影响）。判定是同一只巨兽、同一格岩壁下的 A/B：控制器换上原版 `GroundAI`（=修复前）600 tick 只推进 5px、速度 0.00；换回 `MegaGroundAI` 同一只推进 466px（947→495 距核心）；对照（mace+dagger，不能悬空）落在实心格上被原版当帧清掉（原版行为，不会"停在原地"）|
| `MegaCampaignWaveTest` | **要同时装 combinec + combineunit**（`verify/make-dataset.sh /tmp/mp_campc/data /root/combinec/build/libs/combinec.jar`；数据目录里没有 combinec.jar 就打印 SKIP 并 exit 0） | 上面那条的**端到端**钉子：读真的战役扇区 `SectorPresets.tarFields`，打印第 30~45 波的原版阵容（第 33 波 `mace×8 spiroct×5 horizon×14 atrax×3` = 腿类 + 飞行），用原版 `Logic.runWave()` 刷那一波（**combinec 的 WaveMerger 在 WaveEvent 里合体**），然后看合出来的巨兽有没有朝玩家核心推进：实测巨兽 `horizon×14, mace×8, spiroct×5, atrax×3`、`flying=true`、`flowfieldPathType=costNone`、控制器 GroundAI，1500 tick 距核心 **1776 → 376px**（修前：速度恒为 0.00、距核心几乎不变） |
| `MegaCampaignSweepTest` | **要同时装 combinec**（同上；没有 combinec 时 SKIP） | 逐波扫 tarFields（默认第 1~20 波，`-Dfrom=` / `-Dto=` 可改）：每一波都用原版 `Logic.runWave()` 刷出来（combinec 合体），逐波打印阵容、巨兽构成、`flying`/`allowLegStep`/`flowfieldPathType`、脚下格子、以及 400 tick 里 距核心 的变化，并要求**每一波的巨兽都朝核心推进**。用户报的两波都在里面：第 10 波 `nova×3 crawler×6`（1632→1320）、第 33 波 `mace×8 spiroct×5 horizon×14 atrax×3`。用途是抓"某一波又冒出新的停在原地"|
| `MegaUserSaveWaveTest` | **要用户自己的 tarFields 存档** `<数据目录>/saves/sector-serpulo-99.msav`（+ combineunit + combinec；没有存档就 SKIP） | 用户补报"**第 9 波也是这样**""**第 21 波也不动了**"后，用他在 tarFields（内部名 `焦油田`，260×260、液面比 0.126 → 非海军图）打到第 40 波的那份存档，在**他的图**上复现。**A/B**：第 9 / 33 波（`mace×2 spiroct×1(腿) horizon×2(飞)` / `mace×8 spiroct×5(腿) horizon×14(飞) atrax×3(腿)`）**修复前**（原版 `GroundAI` + 原版 `initPathType` 优先级，腿类排在飞行前面）出生点正好在天然岩壁（`sand-wall` + 深水）上 → 2400 tick **速度恒为 0.00、推进只有 3px / 5px**（用户报的"停在原地"原样复现）；**修复后**（`costNone` + `MegaGroundAI`）同样 2400 tick → 推进 **1708px / 1767px**、到核心跟前。**逐波扫**：同一张图把第 1~45 波全刷一遍（每波 300 tick，`-D` 可改范围），要求**没有任何一波停在原地** —— 实测 42 只巨兽、0 停在原地，含用户报的第 21 波 `mace×5 spiroct×3(腿) horizon×8(飞)`（推进 285px、`cost=costNone`、`MegaGroundAI`）。第 10 / 34 波在用户图这个出生点上两条口径都能走（走到基地火力圈里被打掉），只打印现场；canBoost 悬空那条链由 `MegaHoverStuckTest` 钉 |
| `SanityCheck` | 防呆：模组真的加载、三种巨兽类型建出来了、巨兽实体类登记在**固定槽 250**、原版单位实体被换成本仓库的镜像类（`dagger` → `combineunit.units.entities.CMechUnit`）。每个 run-headless 前自动跑 |
| `CoreUnitSyncTest` | 用户报"**联机时客户端生成不出核心机，一直处于无法建造的状态**"：镜像实体比原版 `writeSync/readSync` 多 8 字节 comboId，而"核心机不换构造器"那次修复只改了构造器、没改 **EntityMapping**（网络重建/存档读回按 classId 走的那张表）—— 服务端按原版格式写、客户端按镜像格式读，整包实体快照 EOF 被丢掉（真联机日志：`EOFException at CUnitEntityLegacyGamma.readSync`，一次跑刷 309 条），核心机所在快照全丢 → 客户端拿不到自己的核心机。判定：**每个单位类型**"服务端 `type.constructor` 造出来的类"必须 == "客户端按 classId 从 EntityMapping 建出来的类"（同一个 classId 只能有一种字节格式）；塞普罗核心机 alpha/beta/gamma 两端都必须是原版类（与作弊模组兼容那条修复的钉子），埃里克尔核心机（evoke/incite/emanate，与 mega/quell/disrupt 共用 `PayloadUnit`）必须与共用该类的普通单位同类且两端一致；另做一次 `writeUnitContainer → readUnitContainer` 的真实字节往返。修前 10 项 FAIL（含 3 次 EOFException），修后 11/11 PASS |
| `MegaEnvTest` | 埃里克尔地图（`Env.scorching\|terrestrial`）上合体不环境死亡：占位类型支持该环境、派生类型按成员推导（`envDisabled` 不含 scorching）、2 秒后仍存活 |
| `MegaFieldTest` | 力场（ForceFieldAbility）合并：**盾容（max）按成员求和**、合并成 1 份（力墙条不超 100%，容差=原版一帧最多冲过 regen 的量）、**半径 = 成员半径最大值 × 体型缩放系数 bodyScale()**（用户要求"范围适配合体后的巨兽单位"；成员 oct 140 × 1.422 = 199.1，比成员自己的 140 大、能罩住放大的身体）；行为验证：在"成员半径之外、巨兽半径之内"（169px）放一发敌方子弹会被力场吸收、半径之外的一发不会被吸收（对照组）；另有引擎按体型缩放、**多引擎成员的整套喷口照抄**（用户报"有多个引擎的单位合体后没画多个引擎，比如 avert"：avert 是 `setEnginesMirror` 摆的 4 个喷口、`engineSize=0`，obviate 是 1 居中 + 1 对镜像 = 3 个；实测 2×avert 巨兽 4 个喷口 = 参考位置 ×1.414 / 半径 ×1.414 / 朝向不变、2×obviate 巨兽 3 个）、`flyingLayer`/`clipSize` 不是 -1、船的水阻/速度、poly 建造速率 2×、指挥类型按 `type.id` 查回来是巨兽 |
| `MegaWaterTest` | 用户报的"ElevationMoveUnit 的实体（elude）合体后淹死"：原版溺水判定一半看**类型**（`UnitEntity.canDrown() = isGrounded() && type.canDrown`），海军与悬浮单位（`ElevationMoveUnit`，elude：`flying=false`、`canDrown=false`）本来就不进溺水分支；巨兽实体是普通 `UnitEntity`、派生类型默认 `canDrown=true`，于是成员不淹、合体后开始淹（修前实测：合体 2 秒 `drownTime` 0.005→0.31，按 deep-water 的 `drownTime=200` 约 6 秒就该掉血/淹死）。现在派生类型的 `canDrown` 按成员**取交集**（有一台不淹就不淹）。四条断言：①单只 elude 在深水上不淹（前置）；②两只 elude 合体后 8 秒不死、不掉血、`drownTime` 一直是 0；③纯陆地编组（dagger×2）照旧会淹（没被一刀切成全体免淹）；④混合编组（elude + dagger）照样不淹 |
| `MegaHoverTest` | 悬浮成员（ElevationMoveUnit / elude）与 cell 贴图四件事：①**cell**：原版 `UnitType.draw()` 的顺序是 `drawBody → if(drawCell) drawCell(unit) → drawWeapons`，巨兽派生类型把 `drawCell` 关了（绘制全在自定义 draw 里），自定义绘制里又没接这一段 → 成员有 cell、合体后没了（用户报的"组合巨兽没画 cell"；`cellRegionFor(dom)` 抽成了绘制与验证共用的判断）；②**液体状态**：原版 `UnitEntity.update()` 是 `if(isGrounded() && !type.hovering) apply(floor.status, …)` —— 悬浮单位靠 `type.hovering` 免掉液体 buff（wet/tarred…），巨兽派生类型从没设过这个字段 → 成员不吃、合体后全吃（用户报的"ElevationMoveUnit 在液体上不受液体 buff"）。现在按成员推导：全是悬浮/飞行/海军这类不贴地的编组才给 `hovering=true`，有真正贴地走的成员就按原版吃地形状态；③**武器位置**：x≈0 的武器只有 1 把时留中间那列（**一条直线**）、≥2 把"同成员的同一把"就**成对分到两侧**（完整断言在 `MegaWeaponLayoutTest`；本测试只做「布局自洽」检查：每把武器落在中间直线 x=0 或两侧圆弧 |位置|=colX、镜像搭档左右严格对称、无重叠）④**cell 的低血量闪烁**：`cellColor(unit)` 里的 `absin(Time.time, f*5, 1)*(1-f)` 脉冲（低血量 cell 会闪），单独 crawler 与 crawler 巨兽的脉冲幅度都是 0.542；真客户端把巨兽冻住压到 30% 血连拍两帧（`049/050_hover_flash_*.png`），同一位置 cell 像素最大差 0.243 = 看得见在闪；⑤elude 导弹的子弹类型/瞄准点/转向量本体与巨兽一致 |
| `MegaBigTest` | 用户报的"合体成员 &gt; 18 时非房主客户端完全看不见这只大单位"：服务端每个实体的快照是 `id(4)+classId(1)+writeSync`、按 800 字节分批，而快照走 UDP、arc 客户端写缓冲只有 16384 字节 —— 成员数据全量内联时 18 只就 3.1KB、24 只 4.1KB，连同别的实体一超整包就被丢。现在**快照只发紧凑构成**（版本 3：每成员 `id(4)+typeId(2)`，完整成员数据仍走存档），实测 24 只从 4107 字节降到 675 字节、客户端读回成员数 24/24；顺带验 20 只的巨兽跑 40000 tick（≈11 分钟游戏时间）仍在世界里（`rules.disableUnitCap` 才能一次造 20 只：默认 unitCap 会把第 9 只起 unitCapDeath）|
| `MegaLegsTest` | 用户报的"腿类单位的**腿部绘制和翻墙能力**被删了"：**腿**原先确实没画出来 —— 代表类型有真·整图时走了"整图已含部件"的分支，而整图里那几条腿是"图标姿态"（蜷在身体底下），看着就是没腿（真客户端对比图：修前 `042_legs_mega.png` 巨兽是个没腿的疙瘩、旁边原版 spiroct 六条长腿；修后 `074_legs_mega.png` 巨兽也伸出长腿）。现在**腿类和机甲一样一律自己补画**（`MegaUnitType.drawOwnParts`），并有断言钉住 `drawOwnParts=true` + 腿数=代表类型腿数；**翻墙（`allowLegStep`）确实从来没接上** —— 现在按成员推导 `type.allowLegStep`，`solidity()` 在腿模式且 allowLegStep 时用 `EntityCollisions::legsSolid`，`pathCost` 也按原版 initPathType 的顺序给了 costLegs。判定：腿类巨兽和原版 spiroct 在"墙格/空地/天然石墙格"三种格子的碰撞谓词逐格一致；**行为**上给它下移动指令能越过一排玩家放的墙（x 1200 → 1248，墙在 1240）；对照组机甲巨兽的谓词和原版 dagger 一致（照旧撞墙）|
| `MegaWeaponLayoutTest` | 任意 | 用户要求的武器位置与射程精修：①**x≈0（`|x|<0.5`）的武器按"同一成员的同一把武器"分组**（key = 成员类型 id + 武器名）：**组里只有 1 把 → 留在中间列**（x=0 的竖直线，以单位中心为中心：1 把→y=0；2 把→±rowGap/2；3 把→-rowGap/0/+rowGap…，y 之和恒为 0）；**组里 ≥2 把 → 成对分到两侧的圆上（左侧 = x 取反、y 相同），奇数剩的那把留中间列**（用户要求原话："如果存在两个及以上的 x=0 的相同单位的相同武器就分到两边的圆上，如果剩一个的话就放中间"）；②**mirror=true 的镜像对左右严格对称**（x 取反、y 相同）；③**mirror=false 且 x>0 放圆圈的右半边、x<0 放左半边**（两侧武器围成一个半径 rad 的圆）；④`range/maxRange` 按**实测挂载**重算（原版 initWeapons 就是按武器算这两个字段，巨兽类型 late 注册、init 从不执行，以前写死 260f）。测试把「镜像对成员（dagger，另外现场追加：一把只有右 x=5、一把只有左 x=-5、以及**一对 x=0 的同名镜像武器**模拟原版 init 对 mirror=true 且 x=0 的展开）+ x=0 成员（vela）」拼成巨兽，逐把核对落位（挂载顺序 = 成员顺序 × 成员武器顺序）：实测（rad=13）镜像对 `[0] (12,5) ↔ [1] (-12,5)`、`[7] (3,-13) ↔ [8] (-3,-13)`；单侧武器 `(12,-5)` / `(-13,0)`；**x=0 分组**：`dagger\|cc-test-mid-a` 2 把 → 圆上左右各 1 把（`(3,13)` / `(-3,13)`）、`vela\|vela-weapon` 1 把 → 留中间 `(0,0)`；3×vela 的巨兽：`vela\|vela-weapon` 3 把 → x=0 留 1 把（y=0）+ 圆上左右各 1 把；用户编组 3 toxopid + 3 pulsar + 1 elude（17 挂载、rad=27.6）：`toxopid\|toxopid-cannon` 3 把 → 留中间 1 把（x=0,y=0）+ 分到两边 `(7,-26)` / `(-7,-26)`，其余 14 把镜像对严格左右对称；射程 `type.range = type.maxRange = range() = 180`（该编组最大「弹体射程 + 枪口偏移」，不再是写死的 260，且 ≥ 最大弹体射程） |
| `MegaTankTest` | 任意 | 用户问的"**坦克合体后的履带绘制和碾压伤害还在吗**"：履带绘制在（`MegaUnitType.drawAttachments` 的 `ATT_TANK` → 原版 `drawTank`，滚动相位由 `MegaUnitEntity.updateAttachments` 维护，真客户端 `094_tank_mega.png` 里履带清清楚楚），**碾压伤害确实丢了** —— 原版碾压在 `TankComp.update()` 里（生成类 `TankUnit implements ... Tankc`），而巨兽继承的是普通 `UnitEntity`（`UnitEntity implements ... Unitc, Velc, Weaponsc`，**没有 Tankc/TankComp**），`super.update()` 里根本没这段；派生类型也从没推导 `crushDamage`/`crushFragile`。修法：派生类型按成员**伤害取最大、脆弱取并集**；实体 `updateCrush()` 照抄原版那段（身周 8 格的敌方脆弱方块秒碎；`r = hitSize×0.75/tilesize`、判定 `r-1` 格内的敌方建筑按 `crushDamage×Δt×方块倍率×unitDamage` 掉血、可踩碎的方块直接拆），飞在空中/被缴械时不碾；履带扬尘 + 滚动音也补上了（尺寸按体型缩放）。实测（该数据集）：vanquish hitSize=28 crushDamage=2.6、conquer hitSize=46 crushDamage=5；两台坦克巨兽 `crushDamage=2.6`、混编（2 vanquish + conquer）取最大 `5.0`；碾压场景把敌方铜墙放在斜对角 `r-1` 格（斜距 28px > 碰撞半径 23px，够得着又不重叠）：原版 conquer 掉血 320（320→0）、坦克巨兽（hitSize=60.7、r=5）同样 320→0、机甲巨兽 0；敌方脆弱方块（bridge-conveyor）被秒碎；履带相位 60 tick 涨了 36 |
| `MegaPlayerFireTest` | **需要装了饱和火力模组的数据集**（否则打印"跳过"并 exit 0） | 用户报"电脑端合体 3 个饱和火力模组的单位神渎后，在**玩家控制**时无法攻击"。本测试把 3 只神渎（`饱和火力-神渎`，hitSize=88、5 把武器：神渎1/神渎2 两对镜像 + 神渎0 正中单门，全是 `rotate=false`）合体，然后**模拟玩家开火输入**（`unit.aim(...)` + `unit.controlWeapons(true, true)`，即 DesktopInput.updateMovement 的尾巴）跑 60 tick，数每把武器的 `totalShots`。实测：单只神渎 649 发；巨兽 15 个挂载共 1947 发（= 3×649，每把可控武器都开过火）。真客户端另有 `sf` 模式（`verify/run-client.sh mx <数据集> sf`）走**原版 DesktopInput 自己的输入路径**（只把 `player.shooting` 置真），实测 `input.canShoot()=true`、`isFlying=false`、`Mechc=false`、`canBoost/omniMovement/faceTarget/hasWeapons=true`，15 个挂载全部 `shoot=true`、累计 12015 发，截图 `096_sf_fire.png`（自制输入）/`098_sf_hold.png`（原版输入路径）—— 也就是**当前版本复现不出"玩家控制时无法攻击"**，这条测试作为回归钉子留着 |
| `MegaBoostTest` | 任意 | 用户报的三条同源问题："**陆辅有助推，在组合巨兽里会变内鬼**"、"**在空中时整个巨兽都不能攻击了**"、"**组了空军后会固定飞天，整个巨兽直接瘫痪**"。根因全在原版这两行：`UnitComp.canShoot() = !disarmed && !(type.canBoost && isFlying())`（**带助推的类型只要离地就不能开火**，而 `isFlying()` 的门槛只有 `elevation >= 0.09`）、`UnitComp.updateBoosting(): shouldBoost = boost \|\| onSolid() \|\| (isFlying() && !canLand())`（撞到实心方块、或悬在别的落地单位上方就会自己往上飘）—— 而派生类型以前**继承了成员的 canBoost**，于是①带助推的成员一进编组，巨兽自己升空 → 整只打不出东西（"内鬼"）；②有飞行成员的编组本来就固定悬空 → 永久 `canShoot()=false`（"瘫痪"）。修法：派生类型恒 `canBoost=false`，飞不飞完全由巨兽自己的模型决定（有飞行成员才飞）。测试（给 dagger 手动加 canBoost 模拟"陆辅"、flare 当空军）：对照——原版带助推单位按 60 tick 助推后 `elevation=1.00 / isFlying=true / canShoot=false`；陆地巨兽 `type.canBoost=false`、按着助推仍 `elevation=0.00`、`canShoot=true`、打出 10 发；空军巨兽 `hasFlyer=true / isFlying=true / canBoost=false / canShoot=true`、空中打出 13 发。把 `ct.canBoost` 改回继承（旧行为）复跑：地面巨兽被顶到 `elevation=0.95 / canShoot=false`、空军巨兽空中 **0 发**，6 条断言集体变红 = 钉子有效 |
| `MegaPossessLoadTest` | 任意 | 用户报"**附身组合巨兽身上后退出地图后重新进去，不能攻击，得重新附身才行**"（2026-10-08 又补了一次）。根因链：`SaveIO.load` → `Logic.reset()` → **`Groups.clear()` 把玩家也清了** → `UnitEntity.read()` 里 `TypeIO.readController` 读到"玩家控制器"却 `Groups.player.getByID(id)` 找不到人 → 原版 `return prev`（null）→ 我们"controller==null 就按类型造一个"的兜底给巨兽塞上 **AI 控制器** → `AIController.updateWeapons()` 每帧复位 `mount.shoot/mount.rotate`，玩家的开火输入全被覆盖（重新附身 = `unit.controller(player)`，AI 不再跑，所以又能打）。修法：存档块升到**版本 4**，额外记下附身者的 **id + 名字**；读档后 `restoreOwner()` 每 20 tick 重试（原版客户端要到 WorldLoadEvent 才 `player.add()`）。**2026-10-08 加固**：①找附身者时**本机玩家 `Vars.player` 优先**（原版存档根本不写 Player 实体，`Groups.player` 里那份副本挂上了也没用——输入只认 `Vars.player`）；②单位已经在玩家名下但控制器不是玩家（`Groups.clear` 的 `PlayerComp.remove → clearUnit → unit.resetController()` 会把它换成 AI）时**直接 `controller(owner)`**（`PlayerComp.unit()` 见"已经是这个单位"会提前 return，光调它没用）；③读档后 **5 秒窗口内每 20 tick 复核一遍**（读档是一串步骤：Groups.clear → 读地图时 WorldLoadEvent 里 `player.add()` → 读实体 → 读档后玩家没单位时核心机还会补一台），谁把这对关系掀掉都能补回来，窗口过了就收手不抢玩家自己选的单位。实测（dagger×2 合体、造一个 Player 附身、`SaveIO.save` → `SaveIO.load`）：读档 `控制器=Player`、`owner.unit()=巨兽`、4/4 把 `shoot=true`、打出 46 发；**故意把玩家的单位掀掉**（`owner.unit(null)` = 模拟读档流程后半段）后 30 tick 内自动补回（控制器=Player）；再重新附身也照旧能打。关掉 `tickRestoreOwner()`（旧行为）复跑：`控制器=CommandAI`、`owner.unit()=null`、0/4 把 shoot、0 发。10/10 PASS |
| `ScriptModCompatTest` | 任意（要真验需要带 `verify/mods/ctcompat` 的数据集，否则打印"跳过"并 exit 0） | 用户报"**组合单位和 CT系统(1.75)/CT2起源(5.30) 冲突**"，崩溃日志 `1 (1).txt`：`Error loading mod combineunit` / `Failed to define class` / `Suppressed: NoClassDefFoundError: Failed resolution of: Lcombineunit/units/entities/CUnitEntity`，调用链是 `creators/T6.js:7 → rhino.JavaAdapter → combineunit…replaceUnitConstructors`。根因：CT2起源 的脚本有 27 处这种写法 `MyUnit.constructor = prov(() => extend(UnitTypes.<原版>.constructor.get().class, {}));` —— **拿原版单位构造器产出实例的 class 当超类**；我们把原版构造器换成镜像类之后它就继承了**模组类**，而安卓上 Rhino 的 JavaAdapter 在内存 dex 里定义适配器、其类加载器的父级只有游戏类加载器，**看不见别的模组（含我们）的类** → 定义失败 → 异常从 `register()` 抛出 → 整个 combineunit 加载失败、游戏崩。修法（`UnitComboDamage`）：①**两阶段**替换（先把所有类型取样、脚本适配器都在"原版构造器还都在"时建好并被 Rhino 缓存，之后才统一替换）；②镜像构造器**脚本感知**：执行别的模组的脚本构造器期间返回**原版实例**，脚本那句 `extend(...)` 拿到的就是游戏自己的类；游戏自己创建单位时才返回镜像（`replaceEntityMapping` 同样处理）；③**世界加载后补扫**：有的模组是进世界时才设构造器的（那时我们已经替换过一轮），扫到"既不是我们装的、也不是我们见过的原版构造器"就包上同样的标记（只比对象身份、**不取样**——取样会真的执行那个脚本构造器，正是安卓上会失败的地方）。CT 那两个模组是 **dex-only（安卓包）**，桌面 JVM 读不了 `classes.dex`，所以用 verify 自带的等价最小 JS 模组 `verify/mods/ctcompat/`（写法一模一样，另加一个"进世界时才设构造器"的单位）复现。实测 11 项：`ctcompat-unit` 的实体类 = `adapter3 ← UnitEntity ← Unit`、进世界时才设的 `ctcompat-late-unit` = `adapter5 ← LegsUnit ← Unit`（**都是游戏类**，链条里没有 combineunit 镜像）、能量产/入世界；原版 dagger 仍是 `CMechUnit`（ComboUnit，承伤共享照旧）。把"脚本感知"改回旧写法复跑：复现单位构造直接抛 `NoClassDefFoundError: combineunit/units/entities/CUnitEntity`（**和用户日志里那行一模一样**），断言变红 = 钉子有效 |
| `MegaFlightRuleTest` | 任意 | 用户 2026-09-25 改的口径：**只要单位组里有飞机，组合巨兽就能飞**（推翻 `~/sd/组合单位.txt` 里早期那句"Σ飞行 hitSize > Σ地面 hitSize 才能飞"）。中间那版按早期设计稿实现成"两边 hitSize 之和比大小"，结果"1 架小飞机 + 2 台坦克"落地（用户先报了"组了空军后固定飞天瘫痪"，但那条的根因是 `canBoost` 被继承，已由派生类型 `canBoost=false` 根治，见 MegaBoostTest）。现在 `canFly = 组里有没有飞行成员`，**同一个判定**驱动派生类型的 `flying`、引擎（`hoverEngines`）、寻路代价（`pathCost`/`flowfieldPathType`）、`moveMode()` 与每帧 elevation 驱动（`update()` 里能飞就钉在 1）。四种构成实测：2×dagger（不飞，moveMode=0，elevation 0，引擎 0）、**2×dagger+1×flare（9 的 flare 一架 → 飞，type.flying=true、moveMode=2、elevation 1.00、引擎 1 个）**、2×flare+1×dagger（飞）、2×flare（飞），四种都能开火（canShoot=true）。顺带补上 `flowfieldPathType`（原版 initPathType 里也按同一优先级指定；巨兽类型 late 注册从没跑过 init，停在 -1 时 `AIController.pathfind` 会拿 -1 当 cost 类型找流场 → 没指令的巨兽不会自己走） |
| `MegaHoverPathTest` | 任意 | 用户报"**ElevationMoveUnit 组合后的 ai 有问题：指挥模式下不能操控它移动到液体上，得手动控制才行**"。用户的怀疑（"ElevationMoveUnit 的 ai 和其它单位不同"）是对的：原版 `UnitType.initPathType()` 给悬浮单位（`type.hovering`，例如 elude）发的是 `costHover`（液体照走），给普通地面单位发的是 `costGround`（`PathTile.allDeep ? impassable`）；派生类型的 pathCost 以前只判"有陆地/海军/飞行成员"，悬浮成员被算成陆地 → 发 `costGround` → `CommandAI` 调 `ControlPathfinder.getPathPosition` 时目标深水格不可达（`move=false`），一步都不走。修法：按**派生类型自己的字段**重演 `initPathType()`（naval → legs → flying → hovering → ground），其中"能不能进深水"用 `!ct.canDrown` 判定（**不能**用 `ct.hovering`：机甲/履带/腿的实体类 `MechUnit`/`TankUnit` 本身就 `implements ElevationMovec`，用它会把手会淹死的纯机甲巨兽也发成 hover 代价）。测试现场铺一个 6×6 深水湖（铺完必须整片 `pathfinder.updateTile` 重打包，否则后铺的格子 `allDeep` 停在 false），量四件事：①口径钉子 —— `ControlPathfinder` 的 costGround 对湖心格 = **-1（impassable）**、costHover = 1；②原版 elude 自己的 `pathCostId=1`、`flowfieldPathType=5`（对照）；③2×elude 巨兽 `hovering=true / canDrown=false / pathCostId=costIdHover / flowfieldPathType=costHover`，**行为**：指挥模式下下移动指令后第 139 tick 进入深水格、第 155 tick 抵达湖心（脚下深水=true、距湖心 12px）；④对照 2×dagger 巨兽 `pathCostId=costIdGround`、cost 表对深水 = -1，同样指令下 1500 tick 都不进深水（停在 88px 外）—— 能力没被无差别放开 |
| `MegaPlaceholderTest` | 任意（数据目录里放用户存档更好，例如 `saves/17.msav`） | 用户报的"**3 个 toxopid 合体后指挥模式图标变成 corvus**"：命令面板是按 `content.unit(unit.type.id)` 取**类型**的图标/指令，而巨兽派生类型共用一个占位 id（`megaGround.id`）—— 占位类型的图标是"每推导一只巨兽就覆盖一次"，存档里先有 toxopid 巨兽、后面又推导过一只 corvus 巨兽，图标就停在 corvus 上。修法：抽出 `MegaUnitEntity.syncPlaceholder()`，客户端每帧（`UnitComboBind.tick()` → `syncPlaceholderToFocus()`）把占位类型同步成"当前焦点的那只巨兽"（指挥模式选中的优先、其次玩家操控的）。本测试：先合 toxopid×3 再合 corvus×3 → 断言两者共用占位 id；调用 `syncPlaceholderTo(某一只)` 后占位类型的指令=那一只的；并读用户存档打印现场（实测存档里有 4 只巨兽：risso / toxopid×2 / corvus）。图标本身要在真客户端看（`icon` 模式）|
| `MegaMiningTest` | 矿工巨兽：物品容量=成员之和（90=3×30）、`drawMineBeam`、光束起点不是 -Inf、真的挖得到东西、成员表丢了也不退回占位类型 |
| `MegaPayloadTest` | 巨兽能被原版载具装进载荷黑洞销毁；成员丢了的巨兽不能退化成占位类型（图标不变） |
| `MegaStatSumTest` | 建造/挖矿速率按成员**量行为**累加（1/2/3 台 = 1×/2×/3×）；客户端按 `EntityMapping + readSync` 造出来的副本也一样；力场实例/展开状态不被快照重建 |
| `MegaSurviveTest` | 地图内合体 2 秒后仍存活（机甲/机甲+海军/爬虫三种构成）；地图外合体被原版清理（老剧本的坑） |
| `MegaSyncTest` | 探雾/小地图/同步/存档；别的模组注册自定义实体后巨兽 id 不变（固定槽 250）；成员按名字读回；新旧三种成员格式都能读 |
| `MegaGhostMemberTest` | 客户端幽灵单位两路一起验：①"巨兽已出现、成员还没被通知移除"的竞态 —— 幽灵成员摘干净、巨兽不误删、同 id 不同类不误删；②**id 复用**（用户报的"客户端生成幽灵单位"）：实体 id 就是 `EntityGroup` 的空位下标，成员被收进巨兽后这些 id 立刻被新单位复用（LIFO，常常正好是刚合掉那两个、类型也一样）—— 只按 (id, 类型) 摘人会把刚造出来的正经单位删掉（删了又建、建了又删 = 一闪一闪的幽灵单位）；现在真幽灵靠**位置指纹**判定，玩家操控的单位一律不碰 |
| `ComboFireSupportTest`（+ combine 仓库的 `combine.dbg.MegaRepairFireTest`） | 借火只许打敌方目标：维修/建造武器跟踪的是**己方建筑**、玩家长按维修时瞄准点也压在自家房子上，照着打就会把同组其他成员的武器引到己方建筑上（用户报的"mega 武器去修复建筑的时候，其他武器的开火会损坏己方建筑"）。实测修前己方半血墙被借火打 14 发，修后己方目标/瞄准点 0 发、敌方目标照常 14 发；巨兽自己 `groupable=false`（不参与借火） |
| `MegaClipSizeTest` | 给巨兽排建造计划后 `clipSize` 不崩（视口裁剪用） |
| `MegaGhostTest` | 合体后不是本地幽灵、控制器/指令表/姿态齐全、移动指令生效、解体后成员回世界、编组入口（comboId）行为 |
| `MegaIdleFireTest` | 巨兽放原地不动、敌方匀速从侧上方路过：逐 tick 采样机身 rotation / 每把 mount 的 rotate,shoot,targetRot,totalShots / 舱里炮台开火数 —— 用来钉"一直索敌抽搐不攻击"（见下面」机身转向」那段） |
| `MegaTurretFireTest` | **要装炮台模组才有意义**（例如饱和火力：`verify/make-dataset.sh /tmp/mp_sf2/data /root/sd/饱和火力3.4.4.3.jar`）：逐个吸收每种炮台、铺靶子跑 3000 tick，统计开火率（诊断工具，默认 exit 0；修后 43/44）。另有 `MegaTurretScanTest`：列出所有炮台方块的 drawer/parts/弹药/heatReq 现场 |
| `MegaTurretTest` | **组合炮台**（手动"选取炮台 → 添加"把炮台收进巨兽体内的小 World）：①**合体不再自动吸收**脚下的炮台（用户 2026-10-08 要求改成手动挑选）；①b**舱位上限 = 成员武器数之和**（4 只 dagger → mounts=8 → 上限 8；2 只 dagger → 上限 4，逐座点 5 座只能进 4 座、第 5 座返回 false 且仍留在世界里）；②`absorbTurretAt` 点哪座吸哪座（没点的还在世界里、半径外的点不动 = 服务端重新校验）；③**布局和武器同一套口径（圆环）**：两侧落在同一个圆环上（4 座同尺寸实测中心距全是 17.8885px、左右严格镜像）、奇数座多出来的那座在正中间（3 座混编：半径 20.4/24.0、中间 1 座）；④炮台随巨兽转向；⑤**物品炮台的子弹从核心里扣**（实测核心铜 4992→4988、场上出现 duo 的弹体）且真的开火；⑥**玩家操控巨兽时舱里的炮台听玩家的鼠标**（用户问的"为什么不能控制炮台转向和开火"）：玩家朝正上瞄 150 tick → duo 朝向 `265.7°` = 玩家鼠标方向（它自己的 AI 目标是 `8°` 右边的敌人）、开火 9→17；松手后放一只新敌人 → 200 tick 后 duo 转向 `101.3°` = 自己的 AI 目标，回到自主索敌；⑦释放（放出 3 座）/批量吸收往返；⑧解体后炮台跟着放回；⑨**快照字节**（218B）与**存档字节**（834B，4 成员 4 炮台）两端往返都能重建成员+炮台；⑩**空舱**快照（150B）不炸；⑪**发布版老存档**（`saves/17.msav`，没有炮台段）照样读得进去（读回 4 只巨兽）；⑫**联机包**`MegaOrderPacket.turretAt` 写读往返（22 字节）；⑬**弹药循环/核心没货不发射/面板禁用**（duo 三种弹药全出现→只剩一种→全清零弹仓恒 0→勾掉一种只剩另一种，且 `ammoList`/`bannedAmmo` 面板读得到）。55/55 PASS |
| （UI 约定，无单独用例） | 合成单位菜单 (`UnitComboBind`) 里点了会改内容的按钮时，**弹窗重画必须延后一帧**（只置 `rebuildQueued`，下一帧 `tick` 再 `hide()+openDialog()`）：arc 的点击回调还在 input 派发栈上，当场 `dialog.cont.clear()` 会把正在派发的按钮/pane 从场景里摘掉，arc 随后 `getScene().addTouchFocus(...)` 直接 NPE 崩游戏（用户 2026-09-29 崩溃；combine 那边的设置列表、`ComboInputGuard` 兜底见 combine 仓库 README） |
| `MegaBuilderAiNpeTest` | 用户安卓崩溃 `BuilderAI.useFallback` 读 `unit.team` NPE（`CommandAI.updateUnit → 命令控制器 BuilderAI`）：巨兽的控制器是"半成品"（`controller != null` 但 `unit == null`，网络读回来的 CommandAI / 读快照中途异常的残留）时，挂着成员带来的 `rebuild`/`assist` 建造指令（poly 这类工程单位）那一帧必崩。判定：这种状态 tick 不抛异常、且当帧把控制器挂回巨兽（`ensureController` 不再只判 `controller == null`）。修前 2 项 FAIL（复现同款 NPE），修后 5/5 PASS |
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
verify/run-client.sh mx /tmp/mp_unit/data flight     # 2×dagger（该贴地）vs 2×dagger+1×flare（有飞机该悬空）
verify/run-client.sh mx /tmp/mp_unit/data engines    # 原版 avert vs 2×avert 巨兽（多引擎成员的喷口）
verify/run-client.sh mx /tmp/mp_unit/data turret     # 组合炮台：合体吸收 duo/scatter/wave，摆在巨兽身上、随朝向转
verify/run-client.sh mx /tmp/mp_unit/data possess    # 附身巨兽 → 存盘 → 读档 → 还能不能开火 / 舱内炮台听不听玩家
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
| hover | `*_hover_before.png` / `*_hover_cell.png` / `*_hover_flash_a/b.png` | 单独 elude / crawler 与各自巨兽并排：看 **cell** 贴图（`elude-cell`/`crawler-cell`/`spiroct-cell`/`power-cell` 都真实存在；修前巨兽没有这块，修后有）；最后两张是把巨兽冻住压到 30% 血连拍，看 cell 的**低血量闪烁**（同位置像素最大差 0.243）|
| icon | `*_icon_panel.png` | 指挥模式图标：读用户存档（没有就现场合 toxopid×3 + corvus×3），打印"面板按 type.id 取到的图标" vs "该巨兽代表成员的图标"，并选中一只巨兽让它走焦点同步。实测用户存档：修前 4 只巨兽面板图标全是 `unit-corvus-ui`；选中 toxopid 巨兽后变成 `unit-toxopid-ui`、`是否一致=true` |
| shipmega | `*_ship_mega.png` | 巨兽浮在深水上、地形速度系数和原版船一致（1.3） |
| flight | `*_flight_rule.png` | "有飞机就飞"：同一张图里左边 2×dagger（贴地、无引擎）、右边 2×dagger+1×flare（悬空、画出引擎尾焰、体型按综合 hitSize 放大）。日志同时打印两边的 `type.flying/elevation/isFlying/canShoot` —— 实测纯地面 `flying=false elevation=0.0 isFlying=false`，带一架 flare `flying=true elevation=1.0 isFlying=true` |
| engines | `*_engines_mega.png` | 多引擎成员：原版 avert（`setEnginesMirror` 摆的 4 个喷口、`engineSize=0`）的喷口要**整套**照抄到巨兽身上（原来只画居中一个）。日志逐个数：参考 avert `engines=4 → (9,-9 r3.0 315°) (-9,-9 r3.0 225°) (10,-4 r3.0 315°) (-10,-4 r3.0 225°)`；2×avert 巨兽 `hitSize=16.97 elevation=1.0 isFlying=true engines=4 → (12,-13 r4.2) (-12,-13 r4.2) (14,-6 r4.2) (-14,-6 r4.2)`（= 参考 ×1.414，朝向不变）。截图 `214_engines_mega.png` 里巨兽身上有 4 个喷口尾焰（原版 avert 参照那只在左下，和齿轮按钮有点重叠） |
| turret | `*_turret_rot90/rot0.png` `*_turret_pick.png` `*_turret_pick_added.png` `*_turret_panel.png` | **组合炮台**：两只 vanquish 合体后，duo/scatter/wave 三座炮台经 `absorbNearbyTurrets` 收进体内 —— ①**炮台本体/parts 画出来没有**（修前 `b.draw()` 的炮台主体落在 `Layer.turret`(50)、被画在 `groundUnit`(60) 的巨兽机身盖住，玩家只看得见一堆方底座 = 用户报的"只画base"；现在绘制层临时抬到机身之上），②位置对不对（日志逐座打 `相对巨兽 dx/dy`；rotation 90→0 坐标跟着转），③液体炮台（wave）直接补给（日志 `液体=9.0`）。`turret_pick` 那张是**手动选取流程**：世界里再摆 duo/scatter/wave 三座 → `MegaTurretPicker.show()` → 巨兽头上「完成选取」+ 每座炮台上一个「添加」；`turret_pick_added` 是点了 scatter 的「添加」之后（炮台舱 4 座、那颗按钮消失、剩下两颗还在）。面板那张看 `组合巨兽 … 炮台 N 座（…）` 那行 + 「选取炮台」「释放全部炮台」两个按钮（`选取炮台` 走和浮标同一条路）。**按钮位置**：`添加` 必须正压在对应炮台正上方 —— 驱动会把每颗按钮的实际坐标打进日志（`"添加"[i] 按钮底边=(x,y) …`），实测 900×700/3 倍缩放下炮台投影 `(396,380)` → 按钮底边 `y=359`、中心 `x=396`（正好在 2×2 炮台上缘之上）。实测截图 `014~018_turret_*.png`（`016_turret_pick.png` 是选取模式那张） |
| possess | `*_possess_after_load.png` | **附身 + 存盘 + 读档**（用户报"附身在组合巨兽身上读写后就不能控制巨兽攻击了，得重新附身"）：融合两只 vanquish → `Vars.player.unit(mega)` → 同步推 120 tick 玩家按住开火（实测打出 16 发）→ `SaveIO.save` → `SaveIO.load`（和暂停菜单"载入"同一条路）→ 再推 tick。日志逐项打 `Vars.player.unit()` / 巨兽控制器 / `player.shooting` / 每把武器 `shoot,rotate,totalShots`，以及**舱内炮台**的 `朝向 vs 玩家鼠标方向`。实测读档后 `玩家单位==巨兽=true 控制器=Player#0`、巨兽继续开火（累计 24 发）；舱内 duo `朝向=90° = 玩家鼠标方向 90°（差 0°）`、`logicControlTime=119`、开火 0→10。实测截图 `013_possess_after_load.png` |

驱动 mod（`verify/client/Driver.java`）的模式场景是从 combine 仓库搬过来的（拆仓后单位侧只在本仓库）；
建筑侧那些模式（设置列表/CoopPanel/电网/科技树…）留在 combine 仓库的 Driver 里。

## elude 的导弹"合体后变直线炮"：**已复现并修好**（用户线索：左右镜像武器被摊成前后）

用户报："elude 的武器合体后就变成极其不精准的炮了，本来子弹是拐弯的，合体后就纯直线了"，
并给出线索："可能是左右对称的武器合体后变成前后的位置了" —— 线索是对的。

**根因**：`MegaUnitEntity.rebuildMounts()` 原来把"第 i 把武器"按序号摊到圆环上
（`ang = i*360/武器总数`）。但原版武器布局里 `x` 是横向偏移、`y` 是纵向偏移，
镜像武器对（`otherSide`，elude：`x=±4, y=-2`）就是 x 反号的一对。2 只 elude = 4 把武器，
正好落在 0°/90°/180°/270° —— 镜像搭档一个被放到"右边"、另一个被放到"前面"，
交替开火时一次从右边打、一次从前面打：看到的正是"极其不精准的炮"，
子弹也不再从原来的枪口位置射出（拐弯的观感就没了）。实测：本体镜像对 2/2 正常，
巨兽 **0/4**（配对两把的 x 差不多、y 差 ~一个环半径 = 被摊成前后）。

**改法**（按用户要求"保留武器围一圈，但左右不能串"）：每个成员的武器作为**一整组刚性旋转**
（成员之间的锚点角按 `span = 180*(M-1)/M` 铺开，旋转角一律 < 90°，所以左右不会翻），
再落到半径 `hitSize*0.55` 的圆环上。修后实测 2 只 elude 的巨兽：4 把武器都在圆环上、
镜像搭档分居两侧 4/4、原来在左/右的武器仍在同一侧 4/4、无重叠。
（踩过的坑：arc 的 `Mathf.atan2` 参数序是 `(x, y)` 且返回**弧度**，按 `(y,x)+度` 用会把角度算错 ——
现在直接用 `Math.toDegrees(Math.atan2(y, x))`。）

以下是当初（还没拿到线索时）的量测记录，留着说明"只看子弹类型/瞄准点看不出问题"：

| 量什么 | elude 本体 | elude 巨兽（2 只） |
|---|---|---|
| 武器 / 子弹 | `UnitTypes$49$3` + `MissileBulletType`，`homingPower=0.19`、`homingRange=50`、`homingDelay=4` | 挂载上的真实武器 = 成员武器的 `copy()`（`WeaponMount.weapon`），子弹同类同参数（homingPower=0.19） |
| 玩家式瞄准（只写 `unit.aimX/aimY` + `mount.shoot`） | 子弹首帧瞄准点与目标偏差 **0.0** | **0.0** |
| 累计转向 / 每颗子弹 | 2.9° | 2.9° |
| 强制锁定目标开火 120 tick | 64 颗子弹、平均累计转向 3300° | 124 颗、3349° |
| 把敌人放进射程（自然瞄准/开火） | `unit.aim` 指到敌人、射击次数 +2 | 同样指到敌人、+4 |

（这批量测说明：只看"子弹类型/首帧瞄准点/转向量"看不出问题 —— 问题在**枪口布局**上，
得量"镜像搭档的相对位置"。）

## 组合炮台（手动"选取炮台 → 添加"）：实现、布局、绘制层与那个**存档字节错位**的坑

用户要求（2026-10-08 更新口径）：**合体不再自动把附近炮台吸进来**，改成
> 点组合巨兽 → 巨兽头上出现「选取炮台」按钮 → 点它进入选取模式（附近每座可吸收的炮台各画一个
> 「添加」按钮）→ 点某座炮台的「添加」→ 那座炮台被吸进巨兽体内。

（更早那版是"合体时自动吸收脚下炮台 + 面板一键'追加附近炮台'"，已按新口径删掉自动吸收，
面板那颗按钮换成同一入口的「选取炮台」。）交互在 `src/combineunit/units/MegaTurretPicker.java`：
按钮是挂在 `Core.scene.root` 上的真实 arc 元素（触摸端/桌面都能点，挂 root 而不是 hudGroup
的原因见 combine 仓库 `SuperTurretPlacer`），每帧按 `Core.camera.project` 跟着世界坐标走；
点回调只置 `pending`、下一帧再动场景（arc 的 input 派发栈里摘元素会 NPE）。
吸收本身服务端权威：`UnitComboMerge.requestAbsorbTurret` → `MegaOrderPacket.turretAt`
→ `absorbTurretAt`（服务端按格重新校验同队/是炮台/在吸收半径内）。

**"点了「添加」却没反应"的几道兜底（用户 2026-10-08 报）**：

1. **炮台本体也能点**：`MegaTurretPicker` 注册了一个 `InputProcessor`（排在 `Core.scene` **之后**，
   所以场景已经吃掉的点击——点在我们的按钮/别的面板上——不会进来）：选取模式下点到世界里、
   落点在某座待选炮台格子里时，就当点了它那颗「添加」并**吃掉这次点击**（顺带不会变成对巨兽下移动令）。
2. **点下去那一帧的引用可能已经过期**（客户端读快照/世界重建会换掉建筑对象）：`absorbSafely()`
   按"格"重新取一次当前那座，取不到就跳过。
3. **半径留 8px 余量**：客户端列出来/可点的用 `absorbRadius - 8`，服务端仍按原半径判定 ——
  联机时两边坐标插值差一点点也不会出现"客户端显示、服务端判超距"的静默失败。
4. **失败不再静默**：吸收失败会 `showInfoFade("这座炮台吃不进去：炮台舱已满（最多 N 座 = 成员武器数之和）或不在吸收范围内")`
   （用户 2026-10-08 的实况就是"舱位满了"却看不出来，一直点；诊断用的 `[combine] 选取炮台：…`
   日志按用户要求已删掉，只留这条触摸端/桌面都能看到的提示）。
5. **联机包单独钉**：`MegaTurretTest` 里对 `MegaOrderPacket.turretAt`（新增的
   `absorbTurretAt/turretX/turretY` 三个字段）做写读往返（实测 22 字节、格子与 unitId 都对得上）——
   单机走 `absorbTurretAt` 本地调用，**联机才走这个包**，不钉的话包格式写错只会表现为"点了没反应"。

**舱位上限 = 巨兽身上所有成员单位的武器数量之和（用户 2026-10-08 口径）**：
`MegaTurretBay.maxTurrets()` 直接取巨兽自己的武器挂载数（原版 `UnitType.init()` 已把 mirror 武器
展开成两条，所以 `type.weapons.size` 就是"这个单位的武器数量"，而巨兽的 mounts 正是全部成员武器
依次复制出来的）——**两端算出来的是同一个数**，联机不会出现"客户端能点、服务端说满了"。
另有 `HARD_MAX_TURRETS = 64` 这道硬闸（防"成员极多"的单只巨兽拖垮帧率，每座炮台每帧都要
update + 绘制），内部世界边长上限跟着提到 `MAX_GRID = 64`（格子只按实际炮台数增长，不是一上来就 64²）。
实测：2 只 dagger → 4 座、4 只 dagger → 8 座、2 只 vanquish → 10 座、4 只 vanquish → 20 座。
**到了上限**：`collectTargets()` 一律不列 → 不再画「添加」按钮（点了也吃不进去），
进选取模式时会直接提示"炮台舱已满（最多 N 座）"。

**按钮定位的坐标系（踩过的坑，用户报"按钮没有一直显示在炮台上面，有偏移"）**：
arc 的 `Camera.project` 返回的是**左下原点、y 向上**的屏幕坐标（arc 的
`Viewport.toScreenCoordinates` 就是"project 之后再 `height - y`"来得到左上原点/y 向下的坐标），
而 arc 的 Scene 用的也是同一套 —— `Scene.act()` 里 `root.y = marginBottom`、
`Element.setPosition` 的 javadoc 明说设的是"bottom left corner"。所以第一版里那句
`sy = height - project.y` 再 `y = sy - lift - height` 是**错上加错**（多翻了一次 y、lift 方向还反了）：
按钮会上下镜像 + 整体往下偏，越远离镜头中心偏得越多。现在 `placeAbove()` 直接
`y = root原点.y + project.y + lift`（`root` 原点 = `(marginLeft, marginBottom)`，刘海屏留白要加回来），
`lift` 按"世界单位 × 相机缩放"换算 —— 镜头放大时炮台贴图也放大，按固定像素抬会压在身上。
真客户端实测（`turret` 模式，900×700、缩放 3 倍）：炮台投影处 `(396,380)`（图像坐标）→ 按钮
底边 `y=359`、中心 `x=396`，正好压在那座 2×2 炮台上缘之上；三座炮台各一个，都不再偏移
（截图 `016_turret_pick.png`，驱动会逐颗打按钮实际坐标）。

**炮台位置（用户要求"和武器一样"）**：`MegaTurretBay.relayout()` 每次吸收后把全部炮台重摆一遍 ——
**偶数座 → 左右两边各一半（右侧先定锚点，左侧按巨兽中线镜像，严格对称）；奇数座 → 多出来的那一座
（最后吸收的那座）摆正中间**。坐标用的是和武器挂载同一套口径：内部世界的 **x 是横向、y 是前后**
（`project()` 和 Weapon 一样走 `Angles.trns(rotation - 90, x, y)`）。摆位换算按原版
`Block.offset = ((size+1)%2)*tilesize/2` 的口径反解锚点格（偶数尺寸的方块中心落在两格之间），
放不下时先就近外扩、再兜底随便找空地。

**两侧摆位 = 圆环（用户 2026-10-09："两边的被组合炮台的位置和武器一样是环形的"）**：
`relayout()` 不再把两侧码成左右两根竖列，而是和 `layoutWeapons` 同一套 —— 每半边 150°、
右侧第 k 座落在 `(cos a, sin a) * radius` 上、左侧按巨兽中线镜像（横向取反、前后相同 → 严格对称）；
半径取三者较大者：①武器圆的世界半径（`hitSize*0.55` 换算成格）；②至少 `maxSize+1` 格；
③"每座占 maxSize 格"所需的弧长（150° 弧 ≈ 2.6×半径）。奇数座多出来的那一座仍然摆正中间。
实测（headless）：4 座同尺寸 duo 到中心距离全是 17.8885px（极差 0，正圆环）、左右严格镜像；
3 座混编（duo+scatter+wave）半径 20.4/24.0px（差一格以内的对齐误差）、正中间 1 座。

**炮台弹药（用户 2026-10-09："发射的弹药为其弹药列表循环发射，核心没有对应物品就不发射，
在解体界面可以选择哪些弹药不被使用"）**：
`MegaTurretBay.feedFromCore()` 改成**按这座炮台自己的弹药表循环喂** —— 每次只喂 1 个料，
从上次停下的位置往后找第一个"没被勾掉、且队伍核心里有货"的弹药（原版 `ammo` 是栈、
`peekAmmo()` 就是下一发，所以不同弹药轮流出现、打出来的子弹在弹药表里轮换）；
核心没有对应物品（或全被勾掉）就一个都不补，那一座自然打不出子弹。仍然只补到半仓、每 tick 至多 1 个料。
面板（`UnitComboBind` 的组合巨兽分支）多了一段「炮台弹药」：列出舱里所有物品炮台弹药表的并集，
一颗按钮一种（`X（使用中）` / `X（不用）`，点了下一帧重画），改动走
`UnitComboMerge.requestAmmoTune`（单机直接改，联机走 `MegaOrderPacket.ammo`，服务端权威）。
这张"禁用表"跟着**快照和存档**走：炮台体格式加了 `VER_TUNE_COMPACT=3 / VER_TUNE_FULL=4`
（只有真的有禁用项时才升版本，空表写老版本 → 旧 jar 读新档不会因为多出来的一段读歪）；
读档/收快照时直接覆盖本地（服务端权威，不会出现"客户端勾了、服务端还在用"）。
实测（headless）：duo 的弹药表 = copper/graphite/silicon，核心三种都有时弹仓里三种都出现（循环）；
核心只剩 copper 时只补 copper；三种都清零后弹仓恒为 0（打不出去）；面板勾掉 copper 后只剩 graphite/silicon。

**"放在原地打路过的敌人：一直索敌抽搐就是不攻击"（用户 2026-10-09）**：根因是**机身从来不转**。
原版 `AIController.faceTarget()` 的条件是 `(unit.type.omniMovement || unit instanceof Mechc) &&
unit.type.faceTarget && unit.type.hasWeapons()` —— 巨兽两条都不满足：派生类型的 `weapons` 是空的
（武器挂载在实体自己的 `mounts` 上，由成员武器复制），实体也不是 `Mechc`（故意的，见类注释里
"机甲飞行时禁止开火"那段）。于是 `rotate = false` 的**固定武器**（原版只有 avert 一把，模组里很常见；
`Weapon.update` 里固定武器就是 `mount.rotation = baseRotation`，只能指着机身朝向）永远指不上目标，
看上去就是"一直在索敌、武器在转/不转之间抽动、一炮打不出去"。修法：`MegaUnitEntity.faceTargetLikeMech()`
照原版补一遍 —— 有固定武器（或编组本来就是机甲/腿/履带这类原版会转身打的）且控制器是 AI 时，
把机身朝最近敌人转过去（预判同样用 `Predict.intercept`，转速用类型 `rotateSpeed`）。
判定：3×avert 的巨兽放原地、敌方从侧上方贴边路过 —— 修前机身 `rotation 0~0（累计转过 0°）`、
自己的武器 0 发；修后机身 `0~350（转过 186°）`、固定武器打出子弹（`[avert-weapon] shots=2`）、
舱里 duo 照常 8 发（`MegaIdleFireTest`）。

**炮台绘制（用户 2026-10-09："炮台的 drawer 和 part 没画，参考一下合体炮台"）**：
`MegaUnitEntity.drawTurrets()` 对**原版 `DrawTurret`**（含模组里用的原版 drawer，实测饱和火力
全部 100 个炮台都是它）改成**逐项复刻原版 `DrawTurret.draw`、但所有层都落在巨兽机身的 z 上** ——
底板 → 本体 region → 液体 → top → 热量 → 描边 → `parts` → `ammoParts`，其中 parts **逐件 try 隔离**
（原版是同一段连续代码，任何一件抛异常会把描边和所有部件一起丢）。做法照搬 combine 仓库
`SuperTurret.drawCellDrawer`（那边已经被用户抓过几轮、修到通）。`DrawTurret` 的**子类**（模组自定义
drawer）走"抬它自己的 `turretLayer/shadowLayer/heatLayer` + 照常 `b.draw()`"，保留它的自定义逻辑；
不是 `DrawTurret` 的 drawer（`DrawMulti` 等）在当前 z 上直接交给它自己画。

**"有的炮台不发射"（用户 2026-10-09）**：新写了一个扫描工具
`combineunit.dbg.MegaTurretFireTest`（默认诊断模式、`-Dassert=1` 才断言）——
把**每一种**炮台方块逐个吸进巨兽体内，场上铺 40~600px 四向的地面+空中厚血靶子，
跑 3000 tick 看 `totalShots`/自己名下的子弹数。修前 61/98 开火、98 个炮台里 37 个一发不打；
按现场逐条修完（装饱和火力数据集实测 **43/44 开火**，唯一的例外见下）：
1. **一次补够"一发"的弹药**（原来每 tick 只喂 1 个料，`ammoPerShot>1` 的炮台永远凑不齐，
   实测 scathe 每发 15 / titan 每发 4 / 阻碍 每发 30）；
2. **补到满仓**（原来只补半仓，`ammoPerShot > maxAmmo/2` 的照样凑不齐一发）；
3. **冷却液 / 消耗型液体一次灌满**：原版 `consumeCoolant()` 生成的是 `ConsumeLiquidFilter`
   （不是 `ConsumeLiquid`），只认后者的话 meltdown/lustre/titan 这些永远拿不到液体、效率 0；
   而且原版是"按效率消耗"的反馈 —— 只补 5/帧会在低效率上稳定下来（sf 屠龙宝刀效率 0.083），
   所以直接灌满；
4. **要热量的炮台塞一个隐形假热块**：原版 `calculateHeat()` 遍历 `proximity` 里的 HeatBlock 求和，
   而接触点算法 `size/2+热源size/2-距离/8` 在格子里"贴着放"也常常算出 0（实测 afflict 4x4 +
   1x1 热源贴着放 = 0），所以干脆用一个**不注册、不占格子、坐标跟炮台完全重合**的假热块
   （`MegaTurretBay.FakeHeat`）塞进 proximity —— 不依赖版本/格子几何。afflict/malign/
   热轨道防御平台/碎影 四个修前全不打，修后全开火。
5. 剩下 1 个不开火的是饱和火力-前狼（`target=null`：扫描工具的靶子不在它的索敌范围/过滤条件里，
   不是"不发射"）。

**炮台绘制（用户报"只画了 base"）**：原版 `DrawTurret` 把炮台分三层 —— 基座画在**调用时的 z**、
炮台主体/parts 落在 `Layer.turret`(50)、热量 `Layer.turretHeat`(50.1)；而巨兽机身画在
`Layer.groundUnit`(60) 一带。原来直接 `b.draw()`，基座（≈61）看得见、主体和 parts 全被机身盖住。
现在 `MegaUnitEntity.drawTurrets(z)` 在画每座炮台前**临时**把这颗 drawer 的
`turretLayer/shadowLayer/heatLayer`（以及 parts 的 `RegionPart.turretHeatLayer`）抬到机身之上，画完立刻还原 ——
走的还是方块自己的 drawer（主体 region + liquid/top + `parts`/`ammoParts` + 热量），
模组炮台自定义的 drawer/parts 也一起照顾到。

**玩家操控（用户问"为什么不能控制炮台转向和开火"）**：玩家正在操控巨兽时
（`mega.controller() instanceof Player`），`MegaTurretBay.driveByPlayer()` 每 tick 走原版
**逻辑控制炮台**那条路驱动舱内炮台 —— `tb.control(LAccess.shoot, World.conv(mega.aimX()), World.conv(mega.aimY()), 玩家按住开火?1:0, 0)`，
和逻辑处理器写 `control shoot` 一模一样：目标点进 `targetPos`、`logicControlTime` 续到 2 秒、
`logicShooting` 记住开不开火，炮台自己在 `updateTile()` 里转向/开火。玩家松手就不再续，
2 秒后 `logicControlTime` 衰减到 0，炮台回到自己的 AI 索敌。
**故意不碰 `unit`/controller**：直接给炮台的 BlockUnit 挂玩家控制器会触发
`PlayerComp.unit()` 把 `player.unit` 从巨兽换成那个假单位（那是原版"附身炮台"，一次只能附身一台），
巨兽反而丢掉操控者 —— 实测表现为炮台照样只打 AI 目标。

实现见 `src/combineunit/units/mega/MegaTurretBay.java`：吸收时先 `tile.setBlock(air)` 走原版流程
（`onRemoved`、从 `Groups.build`/队伍索敌树里摘干净），再**绕过 `setBlock`**（`Tile.updateBlockReference`
+`tile.build`）把真实 `Building` 挂进内部小 World 的格子里 —— 不走事件、不再进 `Groups`，
免得在地图角落留一堆幽灵索敌目标。每 tick 按 `Angles.trns(rotation - 90)` 把内部坐标投影到
巨兽身上的世界坐标再 `update()`（索敌/起火用的都是真实坐标，`Vars.world` 全程是真实世界）。
补给：`ItemTurret` 的弹药从 `team.core()` 扣（每 tick 最多 2 个料、补到半仓）；`LiquidTurret`/
`ContinuousLiquidTurret` 直接加满、`power.status=1`。解体/释放走标准 `setBlock` 放回世界。

**踩过的坑（关键）**：炮台段一开始是写在"原版字段 + 成员块"之后的**尾随段**（标记 + 长度 + 体）。
但存档里每个实体是 `int 长度 + 体`（`SaveFileReader.writeChunk`），读端按长度前进 ——
**发布版（9/29）写的存档只有成员块、没有炮台段**，新读端在实体末尾再读 1 个字节的炮台标记，
正好吃到**下一个实体的长度前缀**，把后面所有实体读歪（整个存档读不进去）。
改法：把炮台段**写进成员体内部**（成员体本身有长度前缀、按长度整块读进内存）。
这样老存档读到炮台段之前就 EOF → 被吞掉保持空舱；新存档两端对称；
旧版客户端读新版快照也只是把多出来的炮台字节当成成员体的一部分丢掉。
回归：`MegaTurretTest` 第 10 节直接读 `/tmp/mp_unit/data/saves/17.msav`（发布版写的、没有炮台段），
实测能读进去、读回 4 只巨兽；同测试还压了存档字节往返（500B）与空舱快照（150B）。
炮台位置（快照/存档里的 `tx/ty` 是**服务端布局算好的**，客户端照字节重建，不再自己找格）。

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

## 6. 巨兽炮台舱：绘制与补给的钉子（2026-10-09）

- `MegaTurretCheatFireTest`（`/tmp/mp_unit/data`）：用户报"**有的炮台不发射，哪怕核心有弹药，
  比如 cyclone 等，所有的炮都是有的发射有的不发射**"。根因是沙盒/作弊规则
  （`team.rules().cheat` = 方块不耗资源）下 `feedFromCore` 直接 return —— 原版给作弊炮台塞第一份
  弹药的地方是 `ItemTurretBuild.onProximityAdded()`，而巨兽炮台是手工 `create()` + 挂假格造出来的、
  永远走不到那条路，于是**物品炮台弹仓恒空、一发都不打**，液体/电力炮台（`supply()` 直接灌满/给电）
  照常开火。判定：2 cyclone + 2 duo（物品）+ 1 wave（液体对照）吸进舱，普通 / cheat / 无限资源
  三种规则各 600 tick，每种规则下 4 座物品炮台都必须开火。修后 8/8 PASS；拿旧包
  （`combinec/res/combineunit.jar`，Oct-8）跑同一套：`cheat=true cyclone=0 0 duo=0 0 wave=178` = FAIL。
- **炮台绘制**（用户 2026-10-09 二报："drawer 就是没画，有的炮台轮廓线都没有，比如 cyclone"）：
  `MegaUnitEntity.drawTurrets(z)` 要同时覆盖三层：
  1. **层号**：炮台本体/parts 默认落在 `Layer.turret`(50)，低于巨兽机身（`Layer.groundUnit` 60）→ 被机身盖住，
     看起来就是"只剩底板/整个没画"。原版 `DrawTurret` 直接改它的 `turretLayer/shadowLayer/heatLayer`；
     模组常见的 `new DrawMulti(DrawRegion, new DrawTurret(){…})` 要走进 `DrawMulti.drawers` 抬**嵌套**的
     `DrawTurret`（`raiseNestedTurretLayers`），画完还原（抽屉实例是方块上共享的）。
  2. **组合方块副本**：装了 combine 时世界里的炮台是"组合方块接管了原版名字"的副本，副本的 drawer
     在有些客户端上**一张图都没 load**（本机就复现不出来）。照 combine 的 `SuperTurret` 口径处理：
     画的时候临时把 block 换成 `comboToOriginal` 里的原版实例（反射查 `combine.BlockCloner`，按类加载器缓存），
     于是走原版那份加载好的抽屉（本体/液体/top/热量/描边/parts 全在）。
  3. **兜底**：抽屉判据（同 `SuperTurret.cellDrawerIncomplete`：base/preview/top/outline/region 一张都没有，
     或声明了要画图的 parts 一张图都没有）成立时，退回**整套图标** `drawTurretIcon()`——除了
     `fullIcon/uiIcon/region`，还**按方块名直接查图集**（`block-<名字>-full` / `<名字>` / `<名字>-preview` / error），
     因为副本方块连 fullIcon 都是空的，按名字查才是关键。

  核对（真客户端）：`verify/run-client.sh mx /tmp/mp_unit/data turret` 的 `*_turret_clean.png`
  （对照行：同样的 duo/scatter/wave/cyclone 摆在吸收半径外，被吸进巨兽的那几座要和它们长得一样）；
  装饱和火力时（`/tmp/mp_sf2/data`）还会额外吸一座 `DrawMulti` 炮台（饱和火力-短波雷达）看嵌套绘制。
  **确定性复现**：`-Ddrv.break=1` 会把舱里第一座炮台的 `drawer.base/preview/top/outline/liquid/heat` 与
  `region/fullIcon/uiIcon` 全清空（= 用户机器上"抽屉一张图都没加载"的状态）再截图 ——
  旧包（`combinec/res/combineunit.jar`）这一份连炮台带底板全没了；本版照样把整座炮台画出来。
