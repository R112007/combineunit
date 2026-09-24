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

## 1. headless 逻辑测试（23 项）

```bash
for t in SanityCheck MegaEnvTest MegaFieldTest MegaWaterTest MegaHoverTest MegaBigTest \
         MegaLegsTest MegaWeaponLayoutTest MegaPlaceholderTest MegaMiningTest MegaPayloadTest MegaTankTest MegaBoostTest \
         MegaPossessLoadTest ScriptModCompatTest MegaFlightRuleTest \
         MegaStatSumTest MegaSurviveTest MegaSyncTest MegaGhostMemberTest MegaClipSizeTest \
         MegaGhostTest ComboFireSupportTest; do
  verify/run-headless.sh mx /tmp/mp_unit/data combineunit.dbg.$t
done
```

| 测试 | 验什么 |
|---|---|
| `SanityCheck` | 防呆：模组真的加载、三种巨兽类型建出来了、巨兽实体类登记在**固定槽 250**、原版单位实体被换成本仓库的镜像类（`dagger` → `combineunit.units.entities.CMechUnit`）。每个 run-headless 前自动跑 |
| `MegaEnvTest` | 埃里克尔地图（`Env.scorching\|terrestrial`）上合体不环境死亡：占位类型支持该环境、派生类型按成员推导（`envDisabled` 不含 scorching）、2 秒后仍存活 |
| `MegaFieldTest` | 力场（ForceFieldAbility）合并：**盾容（max）按成员求和**、合并成 1 份（力墙条不超 100%，容差=原版一帧最多冲过 regen 的量）、**半径 = 成员半径最大值 × 体型缩放系数 bodyScale()**（用户要求"范围适配合体后的巨兽单位"；成员 oct 140 × 1.422 = 199.1，比成员自己的 140 大、能罩住放大的身体）；行为验证：在"成员半径之外、巨兽半径之内"（169px）放一发敌方子弹会被力场吸收、半径之外的一发不会被吸收（对照组）；另有引擎按体型缩放、`flyingLayer`/`clipSize` 不是 -1、船的水阻/速度、poly 建造速率 2×、指挥类型按 `type.id` 查回来是巨兽 |
| `MegaWaterTest` | 用户报的"ElevationMoveUnit 的实体（elude）合体后淹死"：原版溺水判定一半看**类型**（`UnitEntity.canDrown() = isGrounded() && type.canDrown`），海军与悬浮单位（`ElevationMoveUnit`，elude：`flying=false`、`canDrown=false`）本来就不进溺水分支；巨兽实体是普通 `UnitEntity`、派生类型默认 `canDrown=true`，于是成员不淹、合体后开始淹（修前实测：合体 2 秒 `drownTime` 0.005→0.31，按 deep-water 的 `drownTime=200` 约 6 秒就该掉血/淹死）。现在派生类型的 `canDrown` 按成员**取交集**（有一台不淹就不淹）。四条断言：①单只 elude 在深水上不淹（前置）；②两只 elude 合体后 8 秒不死、不掉血、`drownTime` 一直是 0；③纯陆地编组（dagger×2）照旧会淹（没被一刀切成全体免淹）；④混合编组（elude + dagger）照样不淹 |
| `MegaHoverTest` | 悬浮成员（ElevationMoveUnit / elude）与 cell 贴图四件事：①**cell**：原版 `UnitType.draw()` 的顺序是 `drawBody → if(drawCell) drawCell(unit) → drawWeapons`，巨兽派生类型把 `drawCell` 关了（绘制全在自定义 draw 里），自定义绘制里又没接这一段 → 成员有 cell、合体后没了（用户报的"组合巨兽没画 cell"；`cellRegionFor(dom)` 抽成了绘制与验证共用的判断）；②**液体状态**：原版 `UnitEntity.update()` 是 `if(isGrounded() && !type.hovering) apply(floor.status, …)` —— 悬浮单位靠 `type.hovering` 免掉液体 buff（wet/tarred…），巨兽派生类型从没设过这个字段 → 成员不吃、合体后全吃（用户报的"ElevationMoveUnit 在液体上不受液体 buff"）。现在按成员推导：全是悬浮/飞行/海军这类不贴地的编组才给 `hovering=true`，有真正贴地走的成员就按原版吃地形状态；③**武器位置**：x≈0 的武器只有 1 把时留中间那列（**一条直线**）、≥2 把"同成员的同一把"就**成对分到两侧**（完整断言在 `MegaWeaponLayoutTest`；本测试只做「布局自洽」检查：每把武器落在中间直线 x=0 或两侧圆弧 |位置|=colX、镜像搭档左右严格对称、无重叠）④**cell 的低血量闪烁**：`cellColor(unit)` 里的 `absin(Time.time, f*5, 1)*(1-f)` 脉冲（低血量 cell 会闪），单独 crawler 与 crawler 巨兽的脉冲幅度都是 0.542；真客户端把巨兽冻住压到 30% 血连拍两帧（`049/050_hover_flash_*.png`），同一位置 cell 像素最大差 0.243 = 看得见在闪；⑤elude 导弹的子弹类型/瞄准点/转向量本体与巨兽一致 |
| `MegaBigTest` | 用户报的"合体成员 &gt; 18 时非房主客户端完全看不见这只大单位"：服务端每个实体的快照是 `id(4)+classId(1)+writeSync`、按 800 字节分批，而快照走 UDP、arc 客户端写缓冲只有 16384 字节 —— 成员数据全量内联时 18 只就 3.1KB、24 只 4.1KB，连同别的实体一超整包就被丢。现在**快照只发紧凑构成**（版本 3：每成员 `id(4)+typeId(2)`，完整成员数据仍走存档），实测 24 只从 4107 字节降到 675 字节、客户端读回成员数 24/24；顺带验 20 只的巨兽跑 40000 tick（≈11 分钟游戏时间）仍在世界里（`rules.disableUnitCap` 才能一次造 20 只：默认 unitCap 会把第 9 只起 unitCapDeath）|
| `MegaLegsTest` | 用户报的"腿类单位的**腿部绘制和翻墙能力**被删了"：**腿**原先确实没画出来 —— 代表类型有真·整图时走了"整图已含部件"的分支，而整图里那几条腿是"图标姿态"（蜷在身体底下），看着就是没腿（真客户端对比图：修前 `042_legs_mega.png` 巨兽是个没腿的疙瘩、旁边原版 spiroct 六条长腿；修后 `074_legs_mega.png` 巨兽也伸出长腿）。现在**腿类和机甲一样一律自己补画**（`MegaUnitType.drawOwnParts`），并有断言钉住 `drawOwnParts=true` + 腿数=代表类型腿数；**翻墙（`allowLegStep`）确实从来没接上** —— 现在按成员推导 `type.allowLegStep`，`solidity()` 在腿模式且 allowLegStep 时用 `EntityCollisions::legsSolid`，`pathCost` 也按原版 initPathType 的顺序给了 costLegs。判定：腿类巨兽和原版 spiroct 在"墙格/空地/天然石墙格"三种格子的碰撞谓词逐格一致；**行为**上给它下移动指令能越过一排玩家放的墙（x 1200 → 1248，墙在 1240）；对照组机甲巨兽的谓词和原版 dagger 一致（照旧撞墙）|
| `MegaWeaponLayoutTest` | 任意 | 用户要求的武器位置与射程精修：①**x≈0（`|x|<0.5`）的武器按"同一成员的同一把武器"分组**（key = 成员类型 id + 武器名）：**组里只有 1 把 → 留在中间列**（x=0 的竖直线，以单位中心为中心：1 把→y=0；2 把→±rowGap/2；3 把→-rowGap/0/+rowGap…，y 之和恒为 0）；**组里 ≥2 把 → 成对分到两侧的圆上（左侧 = x 取反、y 相同），奇数剩的那把留中间列**（用户要求原话："如果存在两个及以上的 x=0 的相同单位的相同武器就分到两边的圆上，如果剩一个的话就放中间"）；②**mirror=true 的镜像对左右严格对称**（x 取反、y 相同）；③**mirror=false 且 x>0 放圆圈的右半边、x<0 放左半边**（两侧武器围成一个半径 rad 的圆）；④`range/maxRange` 按**实测挂载**重算（原版 initWeapons 就是按武器算这两个字段，巨兽类型 late 注册、init 从不执行，以前写死 260f）。测试把「镜像对成员（dagger，另外现场追加：一把只有右 x=5、一把只有左 x=-5、以及**一对 x=0 的同名镜像武器**模拟原版 init 对 mirror=true 且 x=0 的展开）+ x=0 成员（vela）」拼成巨兽，逐把核对落位（挂载顺序 = 成员顺序 × 成员武器顺序）：实测（rad=13）镜像对 `[0] (12,5) ↔ [1] (-12,5)`、`[7] (3,-13) ↔ [8] (-3,-13)`；单侧武器 `(12,-5)` / `(-13,0)`；**x=0 分组**：`dagger\|cc-test-mid-a` 2 把 → 圆上左右各 1 把（`(3,13)` / `(-3,13)`）、`vela\|vela-weapon` 1 把 → 留中间 `(0,0)`；3×vela 的巨兽：`vela\|vela-weapon` 3 把 → x=0 留 1 把（y=0）+ 圆上左右各 1 把；用户编组 3 toxopid + 3 pulsar + 1 elude（17 挂载、rad=27.6）：`toxopid\|toxopid-cannon` 3 把 → 留中间 1 把（x=0,y=0）+ 分到两边 `(7,-26)` / `(-7,-26)`，其余 14 把镜像对严格左右对称；射程 `type.range = type.maxRange = range() = 180`（该编组最大「弹体射程 + 枪口偏移」，不再是写死的 260，且 ≥ 最大弹体射程） |
| `MegaTankTest` | 任意 | 用户问的"**坦克合体后的履带绘制和碾压伤害还在吗**"：履带绘制在（`MegaUnitType.drawAttachments` 的 `ATT_TANK` → 原版 `drawTank`，滚动相位由 `MegaUnitEntity.updateAttachments` 维护，真客户端 `094_tank_mega.png` 里履带清清楚楚），**碾压伤害确实丢了** —— 原版碾压在 `TankComp.update()` 里（生成类 `TankUnit implements ... Tankc`），而巨兽继承的是普通 `UnitEntity`（`UnitEntity implements ... Unitc, Velc, Weaponsc`，**没有 Tankc/TankComp**），`super.update()` 里根本没这段；派生类型也从没推导 `crushDamage`/`crushFragile`。修法：派生类型按成员**伤害取最大、脆弱取并集**；实体 `updateCrush()` 照抄原版那段（身周 8 格的敌方脆弱方块秒碎；`r = hitSize×0.75/tilesize`、判定 `r-1` 格内的敌方建筑按 `crushDamage×Δt×方块倍率×unitDamage` 掉血、可踩碎的方块直接拆），飞在空中/被缴械时不碾；履带扬尘 + 滚动音也补上了（尺寸按体型缩放）。实测（该数据集）：vanquish hitSize=28 crushDamage=2.6、conquer hitSize=46 crushDamage=5；两台坦克巨兽 `crushDamage=2.6`、混编（2 vanquish + conquer）取最大 `5.0`；碾压场景把敌方铜墙放在斜对角 `r-1` 格（斜距 28px > 碰撞半径 23px，够得着又不重叠）：原版 conquer 掉血 320（320→0）、坦克巨兽（hitSize=60.7、r=5）同样 320→0、机甲巨兽 0；敌方脆弱方块（bridge-conveyor）被秒碎；履带相位 60 tick 涨了 36 |
| `MegaPlayerFireTest` | **需要装了饱和火力模组的数据集**（否则打印"跳过"并 exit 0） | 用户报"电脑端合体 3 个饱和火力模组的单位神渎后，在**玩家控制**时无法攻击"。本测试把 3 只神渎（`饱和火力-神渎`，hitSize=88、5 把武器：神渎1/神渎2 两对镜像 + 神渎0 正中单门，全是 `rotate=false`）合体，然后**模拟玩家开火输入**（`unit.aim(...)` + `unit.controlWeapons(true, true)`，即 DesktopInput.updateMovement 的尾巴）跑 60 tick，数每把武器的 `totalShots`。实测：单只神渎 649 发；巨兽 15 个挂载共 1947 发（= 3×649，每把可控武器都开过火）。真客户端另有 `sf` 模式（`verify/run-client.sh mx <数据集> sf`）走**原版 DesktopInput 自己的输入路径**（只把 `player.shooting` 置真），实测 `input.canShoot()=true`、`isFlying=false`、`Mechc=false`、`canBoost/omniMovement/faceTarget/hasWeapons=true`，15 个挂载全部 `shoot=true`、累计 12015 发，截图 `096_sf_fire.png`（自制输入）/`098_sf_hold.png`（原版输入路径）—— 也就是**当前版本复现不出"玩家控制时无法攻击"**，这条测试作为回归钉子留着 |
| `MegaBoostTest` | 任意 | 用户报的三条同源问题："**陆辅有助推，在组合巨兽里会变内鬼**"、"**在空中时整个巨兽都不能攻击了**"、"**组了空军后会固定飞天，整个巨兽直接瘫痪**"。根因全在原版这两行：`UnitComp.canShoot() = !disarmed && !(type.canBoost && isFlying())`（**带助推的类型只要离地就不能开火**，而 `isFlying()` 的门槛只有 `elevation >= 0.09`）、`UnitComp.updateBoosting(): shouldBoost = boost \|\| onSolid() \|\| (isFlying() && !canLand())`（撞到实心方块、或悬在别的落地单位上方就会自己往上飘）—— 而派生类型以前**继承了成员的 canBoost**，于是①带助推的成员一进编组，巨兽自己升空 → 整只打不出东西（"内鬼"）；②有飞行成员的编组本来就固定悬空 → 永久 `canShoot()=false`（"瘫痪"）。修法：派生类型恒 `canBoost=false`，飞不飞完全由巨兽自己的模型决定（有飞行成员才飞）。测试（给 dagger 手动加 canBoost 模拟"陆辅"、flare 当空军）：对照——原版带助推单位按 60 tick 助推后 `elevation=1.00 / isFlying=true / canShoot=false`；陆地巨兽 `type.canBoost=false`、按着助推仍 `elevation=0.00`、`canShoot=true`、打出 10 发；空军巨兽 `hasFlyer=true / isFlying=true / canBoost=false / canShoot=true`、空中打出 13 发。把 `ct.canBoost` 改回继承（旧行为）复跑：地面巨兽被顶到 `elevation=0.95 / canShoot=false`、空军巨兽空中 **0 发**，6 条断言集体变红 = 钉子有效 |
| `MegaPossessLoadTest` | 任意 | 用户报"**附身组合巨兽身上后退出地图后重新进去，不能攻击，得重新附身才行**"。根因链：`SaveIO.load` → `Logic.reset()` → **`Groups.clear()` 把玩家也清了** → `UnitEntity.read()` 里 `TypeIO.readController` 读到"玩家控制器"却 `Groups.player.getByID(id)` 找不到人 → 原版 `return prev`（null）→ 我们"controller==null 就按类型造一个"的兜底给巨兽塞上 **AI 控制器** → `AIController.updateWeapons()` 每帧复位 `mount.shoot/mount.rotate`，玩家的开火输入全被覆盖（重新附身 = `unit.controller(player)`，AI 不再跑，所以又能打）。修法：存档块升到**版本 4**，额外记下附身者的 **id + 名字**；读档后 `restoreOwner()` 每 20 tick 重试（原版客户端要到 WorldLoadEvent 才 `player.add()`），找到就把玩家重新挂上（`owner.unit(this)`），最多等 5 分钟，等不到保持原版行为。实测（dagger×2 合体、造一个 Player 附身、`SaveIO.save` → `SaveIO.load`）：修后读档 `控制器=Player`、`owner.unit()=巨兽`、开火输入 4/4 把 `shoot=true`、打出 46 发；关掉 `tickRestoreOwner()`（旧行为）复跑：`控制器=CommandAI`、`owner.unit()=null`、**0/4 把 shoot、0 发**，重新附身之后才恢复（10 发）—— 4 条断言变红 = 钉子有效 |
| `ScriptModCompatTest` | 任意（要真验需要带 `verify/mods/ctcompat` 的数据集，否则打印"跳过"并 exit 0） | 用户报"**组合单位和 CT系统(1.75)/CT2起源(5.30) 冲突**"，崩溃日志 `1 (1).txt`：`Error loading mod combineunit` / `Failed to define class` / `Suppressed: NoClassDefFoundError: Failed resolution of: Lcombineunit/units/entities/CUnitEntity`，调用链是 `creators/T6.js:7 → rhino.JavaAdapter → combineunit…replaceUnitConstructors`。根因：CT2起源 的脚本有 27 处这种写法 `MyUnit.constructor = prov(() => extend(UnitTypes.<原版>.constructor.get().class, {}));` —— **拿原版单位构造器产出实例的 class 当超类**；我们把原版构造器换成镜像类之后它就继承了**模组类**，而安卓上 Rhino 的 JavaAdapter 在内存 dex 里定义适配器、其类加载器的父级只有游戏类加载器，**看不见别的模组（含我们）的类** → 定义失败 → 异常从 `register()` 抛出 → 整个 combineunit 加载失败、游戏崩。修法（`UnitComboDamage`）：①**两阶段**替换（先把所有类型取样、脚本适配器都在"原版构造器还都在"时建好并被 Rhino 缓存，之后才统一替换）；②镜像构造器**脚本感知**：执行别的模组的脚本构造器期间返回**原版实例**，脚本那句 `extend(...)` 拿到的就是游戏自己的类；游戏自己创建单位时才返回镜像（`replaceEntityMapping` 同样处理）；③**世界加载后补扫**：有的模组是进世界时才设构造器的（那时我们已经替换过一轮），扫到"既不是我们装的、也不是我们见过的原版构造器"就包上同样的标记（只比对象身份、**不取样**——取样会真的执行那个脚本构造器，正是安卓上会失败的地方）。CT 那两个模组是 **dex-only（安卓包）**，桌面 JVM 读不了 `classes.dex`，所以用 verify 自带的等价最小 JS 模组 `verify/mods/ctcompat/`（写法一模一样，另加一个"进世界时才设构造器"的单位）复现。实测 11 项：`ctcompat-unit` 的实体类 = `adapter3 ← UnitEntity ← Unit`、进世界时才设的 `ctcompat-late-unit` = `adapter5 ← LegsUnit ← Unit`（**都是游戏类**，链条里没有 combineunit 镜像）、能量产/入世界；原版 dagger 仍是 `CMechUnit`（ComboUnit，承伤共享照旧）。把"脚本感知"改回旧写法复跑：复现单位构造直接抛 `NoClassDefFoundError: combineunit/units/entities/CUnitEntity`（**和用户日志里那行一模一样**），断言变红 = 钉子有效 |
| `MegaFlightRuleTest` | 任意 | 用户设计稿 `~/sd/组合单位.txt` 的口径："**如果飞行单位的 hitsize 总和大于地面单位的话就可以飞**"。以前实现的是"有飞行成员就能飞"，一架小飞机搭两台坦克也会整体固定飞天（用户报的"组了空军后会固定飞天，整个巨兽直接瘫痪"）。现在 `canFly = Σ(飞行成员 hitSize) > Σ(地面成员 hitSize)`（海军/悬浮/爬虫都算"地面"），并且**同一个判定**驱动派生类型的 `flying`、引擎（`hoverEngines`）、寻路代价（`pathCost`/`flowfieldPathType`）、`moveMode()` 与每帧 elevation 驱动（`update()` 里能飞就钉在 1）。四种构成实测：2×dagger（0 vs 16 → 不飞，moveMode=0，elevation 0）、2×dagger+1×flare（9 vs 16 → 仍不飞）、2×flare+1×dagger（18 vs 8 → 飞，type.flying=true、moveMode=2、elevation 1、引擎 1 个）、2×flare（18 vs 0 → 飞），四种都能开火（canShoot=true）。顺带补上 `flowfieldPathType`（原版 initPathType 里也按同一优先级指定；巨兽类型 late 注册从没跑过 init，停在 -1 时 `AIController.pathfind` 会拿 -1 当 cost 类型找流场 → 没指令的巨兽不会自己走） |
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
| hover | `*_hover_before.png` / `*_hover_cell.png` / `*_hover_flash_a/b.png` | 单独 elude / crawler 与各自巨兽并排：看 **cell** 贴图（`elude-cell`/`crawler-cell`/`spiroct-cell`/`power-cell` 都真实存在；修前巨兽没有这块，修后有）；最后两张是把巨兽冻住压到 30% 血连拍，看 cell 的**低血量闪烁**（同位置像素最大差 0.243）|
| icon | `*_icon_panel.png` | 指挥模式图标：读用户存档（没有就现场合 toxopid×3 + corvus×3），打印"面板按 type.id 取到的图标" vs "该巨兽代表成员的图标"，并选中一只巨兽让它走焦点同步。实测用户存档：修前 4 只巨兽面板图标全是 `unit-corvus-ui`；选中 toxopid 巨兽后变成 `unit-toxopid-ui`、`是否一致=true` |
| shipmega | `*_ship_mega.png` | 巨兽浮在深水上、地形速度系数和原版船一致（1.3） |

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
