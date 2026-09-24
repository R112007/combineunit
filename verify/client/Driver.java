package drv;
import arc.*; import arc.struct.Seq; import arc.util.*; import arc.util.Timer;
import mindustry.*; import mindustry.content.*; import mindustry.game.*; import mindustry.gen.*; import mindustry.mod.*;
import mindustry.ui.dialogs.*; import mindustry.world.*; import mindustry.world.modules.*; import mindustry.type.*;

/**
 * 验证驱动（combineunit 版）：真客户端里造单位 → 融合成组合巨兽 → 截图。
 *
 * 截图输出目录：-Ddrv.out=<目录>（默认 ~/sd/shots）。文件按**跨次运行的连续序号**命名：
 *   001_mega_world.png、002_mega_hud.png …  这样多次跑不会互相覆盖，也能看出先后顺序。
 *
 * 这份 Driver 的场景代码（mega/legs/mech/duo/shipmega）是从 combine 仓库
 * verify/client/Driver.java **原样搬过来**的：拆仓之后单位侧机制只在本仓库，
 * 这些场景的验证也跟着单位侧走。建筑侧的那些模式（list/coop/gen/pool/tech/...）留在
 * combine 仓库的 Driver 里，不在这里。
 *
 * 模式（-Ddrv.mode=）：
 *   mega     mace + 2×oct + poly 融合成组合巨兽：世界 / 操控 HUD / 信息面板 / 指挥模式图标
 *   legs     spiroct + arkyid（腿类成员）融合：腿按体型放大，和旁边参照 spiroct 对比
 *   mech     dagger + fortress（机甲成员）融合：机甲腿
 *   duo      dagger + vela：碰撞箱 + 武器（治疗类武器不许被代打）
 *   shipmega 两艘 risso 在深水里融合：地形速度系数（船那套）与浮在水面
 */
public class Driver extends Mod{
    static String outDir = System.getProperty("drv.out", System.getProperty("user.home") + "/sd/shots");
    static ClassLoader ml;
    static Building b1, b2, c1;

    static int counter = -1;

    @Override public void init(){
        Events.on(EventType.ClientLoadEvent.class, e -> {
            Log.info("[drv] ClientLoadEvent mods=@ blocks=@ mode=@", Vars.mods.list().size, Vars.content.blocks().size, mode);
            try{ Core.files.absolute(outDir).mkdirs(); }catch(Throwable t){}
            counter = nextIndex();
            Log.info("[drv] 截图目录 @（从序号 @ 开始）", Core.files.absolute(outDir).absolutePath(), counter);
            // 单位侧机制在本仓库的 mod 里（不是 combine）
            var mod = Vars.mods.getMod("combineunit");
            if(mod == null || mod.main == null){
                Log.err("[drv] 数据目录里没有 combineunit —— 驱动场景没法跑（用 verify/make-dataset.sh 造目录）");
                Core.app.exit();
                return;
            }
            ml = mod.main.getClass().getClassLoader();

            if(mode.equals("mega")){
                // 用户报：mace + oct 组合成"巨兽"后 ①不绘制单位身体 ②力墙 bar 超上限。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupMegaScene, 5f);
                Timer.schedule(() -> shot("mega_before"), 10f);
                Timer.schedule(Driver::megaMerge, 14f);
                Timer.schedule(Driver::megaDumpStep, 20f);
                Timer.schedule(() -> shot("mega_after"), 22f);
                Timer.schedule(Driver::megaTakeControl, 26f);
                Timer.schedule(() -> shot("mega_hud"), 30f);
                Timer.schedule(Driver::megaOpenPanel, 34f);
                Timer.schedule(() -> shot("mega_panel"), 38f);
                // 指挥模式：框选巨兽后命令菜单里的图标（用户报"一直显示 dagger"）
                Timer.schedule(Driver::megaCommandMode, 42f);
                Timer.schedule(() -> shot("mega_command"), 47f);
                Timer.schedule(() -> { Log.info("[drv] mega 模式结束 frames=@", frames); Core.app.exit(); }, 53f);
            }else if(mode.equals("shipmega")){
                // 用户报："两艘船组合后速度变得超级慢 / 没处理水的阻力"。
                // 两艘 risso 在**深水**里合体：看它还在不在水面、速度系数是不是和原版船一致
                // （原版船 1.3；没处理水阻时按普通单位算只有 0.2）。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupShipScene, 5f);
                Timer.schedule(Driver::shipMerge, 12f);
                Timer.schedule(Driver::shipReport, 20f);
                Timer.schedule(() -> shot("ship_mega"), 24f);
                Timer.schedule(() -> { Log.info("[drv] shipmega 模式结束 frames=@", frames); Core.app.exit(); }, 30f);
            }else if(mode.equals("duo")){
                // 用户报：dagger + vela 组合后"会发射 vela 治疗武器的子弹"、"碰撞箱好像变了"。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupDuoScene, 5f);
                Timer.schedule(Driver::duoMerge, 12f);
                Timer.schedule(Driver::duoReport, 20f);
                Timer.schedule(() -> shot("duo_fight"), 30f);
                Timer.schedule(Driver::duoReport, 34f);
                Timer.schedule(() -> shot("duo_fight2"), 40f);
                Timer.schedule(() -> { Log.info("[drv] duo 模式结束 frames=@", frames); Core.app.exit(); }, 46f);
            }else if(mode.equals("legs")){
                // 用户报：组合巨兽（成员是腿类单位）没画腿，腿也要按比例放大。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupLegsScene, 5f);
                Timer.schedule(Driver::legsMerge, 12f);
                Timer.schedule(Driver::legsReport, 20f);
                // 参照 spiroct 紧跟着巨兽，同一张图里对比腿的比例（融合半径 160，必须融合后再放）
                Timer.schedule(Driver::legsRefSpawn, 21f);
                Timer.schedule(() -> shot("legs_mega"), 24f);
                Timer.schedule(Driver::legsReport, 28f);
                Timer.schedule(() -> {
                    Log.info("[drv] legs 参照: ref=@ 位置=(@,@) 腿数=@", legsRef == null ? "null" : legsRef.type.name,
                        legsRef == null ? 0 : (int)legsRef.x, legsRef == null ? 0 : (int)legsRef.y,
                        legsRef instanceof mindustry.gen.Legsc l ? l.legs().length : -1);
                    logLegSpan("巨兽", legsMega);
                    logLegSpan("参照spiroct", legsRef);
                }, 30f);
                Timer.schedule(() -> { Log.info("[drv] legs 模式结束 frames=@", frames); Core.app.exit(); }, 36f);
            }else if(mode.equals("mech")){
                // 机甲类成员（dagger/fortress…）合体：机甲腿也是原版分开画的（drawMech），
                // 一样要按体型放大——和 legs 模式同一套场景，只是把成员换成机甲。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupLegsScene, 5f);
                Timer.schedule(Driver::legsMerge, 12f);
                Timer.schedule(Driver::legsRefSpawn, 21f);
                Timer.schedule(() -> shot("mech_mega"), 24f);
                Timer.schedule(() -> {
                    Log.info("[drv] mech 巨兽: attKind=@ 部件状态: 行走相位=@ baseRotation=@ hitSize=@",
                        field(legsMega, "attKind"), field(legsMega, "mechWalkTime"),
                        field(legsMega, "legBaseRotation"), legsMega == null ? -1f : legsMega.hitSize());
                    Log.info("[drv] mech 参照: @ hitSize=@", legsRef == null ? "null" : legsRef.type.name,
                        legsRef == null ? -1f : legsRef.hitSize());
                }, 28f);
                Timer.schedule(() -> { Log.info("[drv] mech 模式结束 frames=@", frames); Core.app.exit(); }, 36f);
            }else if(mode.equals("hover")){
                // 用户报的"组合巨兽没画 cell"，以及"ElevationMoveUnit 在液体上不吃液体 buff"的对照：
                // 场景里并排摆 单独 elude / elude 巨兽 / 单独 crawler / crawler 巨兽，
                // 放大镜头截一张图看 cell（底盘那块贴图）在不在、比例对不对。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupHoverScene, 5f);
                Timer.schedule(Driver::hoverCenter, 6f, 0.5f, 60);
                Timer.schedule(() -> shot("hover_before"), 18f);
                Timer.schedule(Driver::hoverMerge, 22f);
                Timer.schedule(() -> shot("hover_cell"), 28f);
                // cell 的"低血量闪烁"：把巨兽压到 30% 血，隔几帧拍两张（脉冲来自 cellColor 里的 absin）
                Timer.schedule(Driver::hoverDamage, 30f);
                Timer.schedule(() -> shot("hover_flash_a"), 32f);
                Timer.schedule(() -> shot("hover_flash_b"), 33.2f);
                Timer.schedule(() -> { Log.info("[drv] hover 模式结束 frames=@", frames); Core.app.exit(); }, 36f);
            }else if(mode.equals("icon")){
                // 用户报："3 个 toxopid 合体后，指挥模式下的图标变成了 corvus"。
                // 面板是按 content.unit(unit.type.id) 取**类型**的图标/指令的，而巨兽派生类型共用一个
                // 占位 id —— 存档里同时有 toxopid 巨兽和 corvus 巨兽时，占位类型会停在最后推导的那只上。
                // 本模式：读用户存档（没有就现场合 toxopid×3 + corvus×3），选中 toxopid 巨兽，
                // 打印"面板会用的图标" vs "该巨兽代表成员的图标"，并截图。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupIconScene, 5f);
                Timer.schedule(Driver::iconReport, 16f);
                Timer.schedule(Driver::iconSelect, 20f);
                Timer.schedule(Driver::iconReport, 24f);
                Timer.schedule(() -> shot("icon_panel"), 26f);
                Timer.schedule(() -> { Log.info("[drv] icon 模式结束 frames=@", frames); Core.app.exit(); }, 30f);
            }else if(mode.equals("mid")){
                // 用户报："3 个 toxopid + 3 个 pulsar + 1 个 elude 合体后，中间的武器全跑单位后面去了。"
                // 本模式复现这个编组：合体后把巨兽转正（rotation=90，屏幕上方 = 单位正前方）、
                // 每帧把所有枪口钉在 90°（全部朝上），这样截图里**枪管的位置 = 挂载的 (x,y)**：
                // 中间那一列该是 x=0 的竖直线（关于中心对称），两边该是围一圈。
                // 同时把每把挂载的 (x,y) 打进日志，和截图逐把对照。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupMidScene, 5f);
                Timer.schedule(Driver::midMerge, 10f);
                Timer.schedule(Driver::midDump, 16f);
                Timer.schedule(() -> Vars.renderer.setScale(2f), 17f);
                Timer.schedule(() -> shot("mid_layout_far"), 19f);
                Timer.schedule(() -> Vars.renderer.setScale(4f), 21f);
                Timer.schedule(() -> shot("mid_layout_near"), 23f);
                Timer.schedule(Driver::midDump, 25f);
                Timer.schedule(() -> { Log.info("[drv] mid 模式结束 frames=@", frames); Core.app.exit(); }, 30f);
            }else if(mode.equals("tank")){
                // 用户问："坦克合体后的履带绘制和碾压伤害还在吗" —— 履带得看真客户端。
                // 两台 vanquish + 一台 conquer 融合，右边放一台单独的 vanquish 当参照；
                // 让巨兽慢速往前开（履带要动起来才看得出滚动帧），相机钉在它身上。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupMidScene, 5f);     // 同一套"找平地 + 放核心"
                Timer.schedule(Driver::tankMerge, 10f);
                Timer.schedule(Driver::tankDrive, 12f, 0.05f, 600);
                Timer.schedule(Driver::tankReport, 16f);
                Timer.schedule(() -> Vars.renderer.setScale(2.5f), 18f);
                Timer.schedule(() -> shot("tank_mega"), 22f);
                Timer.schedule(Driver::tankReport, 24f);
                Timer.schedule(() -> { Log.info("[drv] tank 模式结束 frames=@", frames); Core.app.exit(); }, 30f);
            }else if(mode.equals("sf")){
                // 用户报："电脑端合体 3 个饱和火力模组的单位神渎后，在玩家控制时无法攻击"。
                // 这个模式把 3 只神渎（饱和火力 mod）合体、接管控制，然后**照抄 DesktopInput.updateMovement
                // 的尾巴**（aim + controlWeapons(true, player.shooting && !boosted)）来模拟"玩家按住开火"，
                // 同时把输入侧/武器侧的状态全打进日志（player.shooting / canShoot / isFlying / mount.shoot…）。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupMidScene, 5f);
                Timer.schedule(Driver::sfMerge, 10f);
                Timer.schedule(Driver::sfTakeControl, 14f);
                // 阶段 1：自己照抄输入尾巴（aim + controlWeapons）——证明武器系统本身能开火
                Timer.schedule(Driver::sfLoop, 16f, 0.05f, 800);
                Timer.schedule(Driver::sfReport, 18f);
                Timer.schedule(() -> Vars.renderer.setScale(2f), 20f);
                Timer.schedule(() -> shot("sf_fire"), 24f);
                Timer.schedule(Driver::sfReport, 26f);
                Timer.schedule(Driver::sfReportWeapons, 28f);
                // 阶段 2：**只按住开火键**，瞄准/转向/开火全交给游戏自己的桌面输入处理
                Timer.schedule(() -> { sfPhase = 2; Log.info("[drv] sf: 切到阶段 2（只按住开火，交给原版 DesktopInput）"); }, 30f);
                Timer.schedule(Driver::sfReportWeapons, 42f);
                Timer.schedule(() -> shot("sf_hold"), 44f);
                Timer.schedule(Driver::sfReport, 46f);
                Timer.schedule(() -> { Log.info("[drv] sf 模式结束 frames=@", frames); Core.app.exit(); }, 52f);
            }else if(mode.equals("flight")){
                // 用户设计稿："飞行单位的 hitsize 总和大于地面单位的话就可以飞"。
                // 并排两个编组：左边 2×dagger + 1×flare（地面为主 → 该贴地），
                // 右边 2×flare + 1×dagger（空军为主 → 该悬空）。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupMidScene, 5f);
                Timer.schedule(Driver::flightScene, 10f);
                Timer.schedule(Driver::flightReport, 18f);
                Timer.schedule(() -> Vars.renderer.setScale(2f), 20f);
                Timer.schedule(() -> shot("flight_rule"), 24f);
                Timer.schedule(Driver::flightReport, 26f);
                Timer.schedule(() -> { Log.info("[drv] flight 模式结束 frames=@", frames); Core.app.exit(); }, 32f);
            }else{
                Log.err("[drv] 未知模式 @（combineunit 支持 mega|legs|mech|duo|shipmega|hover|icon|mid|tank|sf|flight）", mode);
                Core.app.exit();
            }
        });
    }

    static final String mode = System.getProperty("drv.mode", "mega");

    static int nextIndex(){
        int max = 0;
        try{
            arc.files.Fi d = Core.files.absolute(outDir);
            d.mkdirs();
            for(arc.files.Fi f : d.list()){
                String n = f.name();
                int i = 0;
                while(i < n.length() && Character.isDigit(n.charAt(i))) i++;
                if(i > 0) max = Math.max(max, Integer.parseInt(n.substring(0, i)));
            }
        }catch(Throwable t){ Log.err("[drv] 读取截图目录失败", t); }
        return max + 1;
    }

    // ---------------- 组合巨兽（mace + oct 融合） ----------------
    static Unit megaUnit, maceUnit, octUnit, octUnit2, polyUnit;
    static int megaPhase = 0, megaFrames = -1;

    static Object combineCall(String cls, String method, Class<?>[] sig, Object... args){
        try{
            Class<?> c = Class.forName(cls, true, ml);
            java.lang.reflect.Method m = sig == null ? null : c.getMethod(method, sig);
            if(m == null) m = c.getMethod(method);
            m.setAccessible(true);
            return m.invoke(null, args);
        }catch(Throwable t){ Log.err("[drv] 调用 @.@ 失败", cls, method, t); return null; }
    }

    static void setupMegaScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            // 找一块陆地（别让 mace 落水里淹死）
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<125;y++){
                for(int x=35;x<190;x++){
                    boolean ok = true;
                    for(int dy=-1;dy<=1 && ok;dy++) for(int dx=-2;dx<=2;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] mega 没找到陆地"); return; }

            // 队伍得有核心，不然游戏会把队伍当成已出局、清掉单位
            Building core = placeBL(Blocks.coreShard, ox + 20, oy + 12);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            megaOx = ox; megaOy = oy;
            Core.camera.position.set(ox * 8f, oy * 8f);
            Timer.schedule(Driver::megaSpawn, 2f);
            Timer.schedule(Driver::megaMerge, 5f);
        }catch(Throwable t){ Log.err("[drv] setupMegaScene failed", t); }
    }

    static int megaOx = -1, megaOy = -1;

    static void megaSpawn(){
        try{
            float cx = megaOx * 8f, cy = megaOy * 8f;
            maceUnit = UnitTypes.mace.create(Team.sharded);
            maceUnit.set(cx - 20f, cy);
            maceUnit.add();
            octUnit = UnitTypes.oct.create(Team.sharded);
            octUnit.set(cx + 30f, cy);
            octUnit.add();
            // 第二台带力场的成员：力场必须合并成一份，否则"力墙条"用单个成员的上限去除全组盾量（超 100%）
            octUnit2 = UnitTypes.oct.create(Team.sharded);
            octUnit2.set(cx + 70f, cy + 20f);
            octUnit2.add();
            // poly：工程/采矿单位 —— 自动重建 / 辅助建造 / 挖矿 这些指令都挂在它身上，
            // 合体后指令表必须继承（用户报的"poly 合体后这些命令消失"）
            polyUnit = UnitTypes.poly.create(Team.sharded);
            polyUnit.set(cx - 60f, cy + 20f);
            polyUnit.add();
            Core.camera.position.set(cx, cy);
            Log.info("[drv] mega 场景: 陆地=@,@ mace=@(@, @) oct=@(@, @) Groups.unit=@ frames=@", megaOx, megaOy,
                maceUnit.type.name, (int)maceUnit.x, (int)maceUnit.y, octUnit.type.name, (int)octUnit.x, (int)octUnit.y,
                Groups.unit.count(u -> true), frames);
        }catch(Throwable t){ Log.err("[drv] megaSpawn failed", t); }
    }

    static void megaDumpStep(){
        megaDump("融合后");
        arc.math.geom.Vec2 sp = Core.camera.project(megaUnit == null ? 0 : megaUnit.x, megaUnit == null ? 0 : megaUnit.y);
        Log.info("[drv] 屏幕坐标=@,@ 相机=@,@ frames=@ 力墙条: @", (int)sp.x, (int)sp.y,
            (int)Core.camera.position.x, (int)Core.camera.position.y, frames, shieldBar());
    }

    static void megaTakeControl(){
        if(megaUnit != null) Vars.player.unit(megaUnit);
        if(megaUnit != null) Core.camera.position.set(megaUnit.x, megaUnit.y);
        installCameraLock();
        Log.info("[drv] 玩家单位=@ frames=@", Vars.player.unit() == null ? "null" : Vars.player.unit().type.name, frames);
    }

    /** 力墙条比例 = 盾量 / 力场上限（原版 HUD 与信息面板都用这个数）。 */
    static String shieldBar(){
        if(megaUnit == null) return "无巨兽";
        int fields = 0;
        float max = 0f, scaled = 0f;
        for(var a : megaUnit.abilities()){
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff){
                fields++;
                max += ff.max;
                scaled += ff.scaledMax(megaUnit);
            }
        }
        return "力场数=" + fields + " 盾=" + megaUnit.shield() + " max合计=" + max + " scaledMax合计=" + scaled
            + " 第一条bar比例=" + firstShieldRatio();
    }

    static String firstShieldRatio(){
        for(var a : megaUnit.abilities()){
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff)
                return (megaUnit.shield() / ff.max) + "（上限=" + ff.max + "）";
        }
        return "无";
    }

    /** 截图时想锁定的单位（默认是 mega 模式那台巨兽）。 */
    static Unit camTarget;

    /** 每帧把相机钉在目标单位身上（llvmpipe 下相机跟随会跟丢，截图就看不到单位）。 */
    static void installCameraLock(){
        var t = new arc.scene.ui.layout.Table();
        t.touchable = arc.scene.event.Touchable.disabled;
        t.update(() -> {
            // camTarget 优先；其次是大模式自己造的巨兽；最后按类名找（巨兽可能是别人造出来的，
            // 没有本地字段可指）—— 不这么兜底，截图会拍到"镜头跟着玩家、巨兽在画面外"。
            Unit u = camTarget != null ? camTarget : (megaUnit != null ? megaUnit : megaUnit());
            if(u != null && u.isAdded()) Core.camera.position.set(u.x, u.y);
        });
        Vars.ui.hudGroup.addChild(t);
    }

    static void megaOpenPanel(){
        try{
            if(megaUnit == null) return;
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            megaUnit.type.display(megaUnit, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合巨兽信息面板");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] 巨兽信息面板已弹出（子元素=@）frames=@", panel.getChildren().size, frames);
        }catch(Throwable t){ Log.err("[drv] megaOpenPanel failed", t); }
    }

    static Unit legsMega, legsA, legsB, legsRef;
    static int legsOx = -1, legsOy = -1;

    static void setupLegsScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<120;y++){
                for(int x=40;x<190;x++){
                    boolean ok = true;
                    for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-4;dx<=4;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] legs: 没找到陆地"); return; }
            legsOx = ox; legsOy = oy;
            Building core = placeBL(Blocks.coreShard, ox + 18, oy + 10);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            Core.camera.position.set(ox * 8f, oy * 8f);
            Timer.schedule(Driver::legsSpawn, 2f);
        }catch(Throwable t){ Log.err("[drv] setupLegsScene failed", t); }
    }

    static void legsSpawn(){
        try{
            float cx = legsOx * 8f, cy = legsOy * 8f;
            if("mech".equals(mode)){
                // mech 模式：换成一中一小两台机甲（成员构成不同、hitSize 不同，缩放才看得出来）
                legsA = UnitTypes.dagger.create(Team.sharded);
                legsA.set(cx - 20f, cy);
                legsA.add();
                legsB = UnitTypes.fortress.create(Team.sharded);
                legsB.set(cx + 20f, cy);
                legsB.add();
                Log.info("[drv] mech 场景: dagger + fortress 已就位");
                return;
            }
            legsA = UnitTypes.spiroct.create(Team.sharded);
            legsA.set(cx - 20f, cy);
            legsA.add();
            legsB = UnitTypes.arkyid.create(Team.sharded);
            legsB.set(cx + 20f, cy);
            legsB.add();
            Log.info("[drv] legs 场景: spiroct + arkyid 已就位（腿=@/@ 段）",
                UnitTypes.spiroct.legCount, UnitTypes.arkyid.legCount);
        }catch(Throwable t){ Log.err("[drv] legsSpawn failed", t); }
    }

    /** 融合**之后**再放一只参照 spiroct 到巨兽旁边（融合半径内的会被卷进去，所以必须后放）。 */
    static void legsRefSpawn(){
        try{
            if(legsMega == null || !legsMega.isValid()){ Log.err("[drv] legsRefSpawn: 巨兽没了"); return; }
            // 挪到场景开头挑好的那块空地（离核心 18 格），免得巨兽正好站在核心上、贴图互相糊住
            legsMega.set(legsOx * 8f, legsOy * 8f);
            // 相机：关掉"跟随玩家单位"，让 installCameraLock 每帧把镜头钉在巨兽身上；
            // 缩放调大（1.3）保证整只巨兽连同放大后的腿都在画面里。
            Core.settings.put("detach-camera", true);
            Vars.renderer.setScale(1.3f);
            Core.camera.position.set(legsMega.x, legsMega.y);
            legsRef = ("mech".equals(mode) ? UnitTypes.fortress : UnitTypes.spiroct).create(Team.sharded);
            legsRef.set(legsMega.x - 110f, legsMega.y);
            legsRef.add();
            var under = Vars.world.buildWorld(legsMega.x, legsMega.y);
            Log.info("[drv] legs 参照物已放: 位置=(@,@) 巨兽脚下建筑=@ 相机=(@,@)",
                (int)legsRef.x, (int)legsRef.y, under == null ? "无" : under.block.name,
                (int)Core.camera.position.x, (int)Core.camera.position.y);
        }catch(Throwable t){ Log.err("[drv] legsRefSpawn failed", t); }
    }

    static void legsMerge(){
        try{
            Object merged = legsA == null ? null
                : combineCall("combineunit.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, legsA);
            if(merged instanceof Unit u) legsMega = u;
            Log.info("[drv] legs 融合结果=@", merged);
        }catch(Throwable t){ Log.err("[drv] legsMerge failed", t); }
    }

    /** 打一只腿类单位的"腿展"（每根腿的 base 相对身体中心的距离），用来对账缩放比例。 */
    static void logLegSpan(String tag, Unit u){
        if(u == null || !(u instanceof mindustry.gen.Legsc l)){ Log.info("[drv] @ 不是腿类单位", tag); return; }
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < l.legs().length; i++){
            var leg = l.legs()[i];
            sb.append(String.format(" %.1f", arc.math.Mathf.dst(u.x, u.y, leg.base.x, leg.base.y)));
        }
        Log.info("[drv] @ 腿展(中心到脚)=@ hitSize=@ 腿数=@", tag, sb.toString(), u.hitSize(), l.legs().length);
    }

    static void legsReport(){
        try{
            if(legsMega == null || !legsMega.isValid()){ Log.info("[drv] legs: 巨兽没了"); return; }
            Unit u = legsMega;
            camTarget = u;
            Core.camera.position.set(u.x, u.y);
            Object dom = field(u.getClass(), u, "dominant");
            Log.info("[drv] legs 巨兽: type=@ hitSize=@ 代表类型=@(hitSize=@) 贴图缩放=@ dom.legRegion=@ dom.legCount=@",
                u.type.name, u.hitSize(), dom instanceof UnitType ? ((UnitType)dom).name : "-",
                dom instanceof UnitType ? ((UnitType)dom).hitSize : -1f,
                dom instanceof UnitType ? (u.hitSize() / ((UnitType)dom).hitSize) : -1f,
                dom instanceof UnitType ? regionName(((UnitType)dom).legRegion) : "-",
                dom instanceof UnitType ? ((UnitType)dom).legCount : -1);
            Log.info("[drv] legs 巨兽是不是 Legsc=" + (u instanceof mindustry.gen.Legsc)
                + " 成员数=" + memberCount(u));
            try{
                Object att = field(u.getClass(), u, "attKind");
                Object legs = field(u.getClass(), u, "legs");
                int n = legs instanceof Object[] a ? a.length : -1;
                StringBuilder sb = new StringBuilder();
                if(legs instanceof Object[] a){
                    for(int i = 0; i < Math.min(n, 6); i++){
                        var l = (mindustry.entities.Leg)a[i];
                        sb.append(" [").append(i).append(" base=").append((int)l.base.x).append(",").append((int)l.base.y)
                          .append(" joint=").append((int)l.joint.x).append(",").append((int)l.joint.y).append("]");
                    }
                }
                Log.info("[drv] legs 部件: attKind=@ legs.length=@ 单位位置=(@,@) 腿=@", att, n, (int)u.x, (int)u.y, sb.toString());
            }catch(Throwable t){ Log.err("[drv] legs 部件报告失败", t); }
        }catch(Throwable t){ Log.err("[drv] legsReport failed", t); }
    }


    static Unit duoMega, duoDagger, duoVela, duoEnemy, duoAlly;
    static int duoOx = -1, duoOy = -1;

    static void setupDuoScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            // 关掉"设置里的绘制碰撞箱" → 画出 hitbox（看碰撞箱到底多大、和身体对不对得上）
            Core.settings.put("drawhitboxes", true);
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<120;y++){
                for(int x=40;x<190;x++){
                    boolean ok = true;
                    for(int dy=-2;dy<=2 && ok;dy++) for(int dx=-3;dx<=3;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] duo: 没找到陆地"); return; }
            duoOx = ox; duoOy = oy;
            Building core = placeBL(Blocks.coreShard, ox + 16, oy + 10);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            Core.camera.position.set(ox * 8f, oy * 8f);
            Timer.schedule(Driver::duoSpawn, 2f);
        }catch(Throwable t){ Log.err("[drv] setupDuoScene failed", t); }
    }

    static void duoSpawn(){
        try{
            float cx = duoOx * 8f, cy = duoOy * 8f;
            duoDagger = UnitTypes.dagger.create(Team.sharded);
            duoDagger.set(cx - 20f, cy);
            duoDagger.add();
            duoVela = UnitTypes.vela.create(Team.sharded);
            duoVela.set(cx + 20f, cy);
            duoVela.add();
            // 敌方靶子：贴近一点（60 单位）逼它开火；看它到底发的是光束还是子弹、打谁
            duoEnemy = UnitTypes.dagger.create(Team.crux);
            duoEnemy.set(cx + 60f, cy);
            duoEnemy.add();
            // 自己人：故意打残（40% 血）—— vela 的维修光束（RepairBeamWeapon）本来只治它
            duoAlly = UnitTypes.dagger.create(Team.sharded);
            duoAlly.set(cx, cy + 40f);
            duoAlly.add();
            duoAlly.health(duoAlly.maxHealth() * 0.4f);
            Core.camera.position.set(cx + 40f, cy);
            Log.info("[drv] duo 场景: dagger+vela 已就位，敌方靶子 @,@ 伤员 @,@（血 @%）",
                (int)duoEnemy.x, (int)duoEnemy.y, (int)duoAlly.x, (int)duoAlly.y, (int)(duoAlly.healthf() * 100));
        }catch(Throwable t){ Log.err("[drv] duoSpawn failed", t); }
    }

    static void duoMerge(){
        try{
            Object merged = duoDagger == null ? null
                : combineCall("combineunit.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, duoDagger);
            if(merged instanceof Unit u){
                duoMega = u;
                camTarget = u;
                installCameraLock();
                // 直接按住扳机（controllable 武器只有玩家扣扳机才开火；autoTarget 的维修光束
                // 会用自己的索敌覆盖 mount.shoot，所以这里只影响那些"要玩家瞄"的武器）
                installTrigger(u, duoEnemy);
                Log.info("[drv] duo 融合后立刻: 类=@ 成员=@ 挂座=@ 能力=@ 有武器=@", u.getClass().getName(),
                    memberCount(u), u.mounts() == null ? -1 : u.mounts().length, u.abilities() == null ? -1 : u.abilities().length, u.hasWeapons());
            }
        }catch(Throwable t){ Log.err("[drv] duoMerge failed", t); }
    }

    /** 每帧替目标单位按住扳机并对准 target（截图用：逼它开火，好看清武器到底发什么）。 */
    static void installTrigger(Unit u, Unit target){
        var t = new arc.scene.ui.layout.Table();
        t.touchable = arc.scene.event.Touchable.disabled;
        t.update(() -> {
            if(u == null || !u.isAdded()) return;
            float tx = target != null && target.isValid() ? target.x : u.x + 100f;
            float ty = target != null && target.isValid() ? target.y : u.y;
            if(u.mounts() == null) return;
            for(var m : u.mounts()){
                m.aimX = tx;
                m.aimY = ty;
                m.shoot = true;
                m.rotate = true;
            }
        });
        Vars.ui.hudGroup.addChild(t);
    }

    static int memberCount(Unit u){
        try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; }
    }

    static void duoReport(){
        try{
            if(duoMega == null || !duoMega.isValid()){ Log.info("[drv] duo: 巨兽没了"); return; }
            Unit u = duoMega;
            camTarget = u;
            Core.camera.position.set(u.x, u.y);
            Object dom = field(u.getClass(), u, "dominant");
            Log.info("[drv] duo 巨兽: type=@ flying=@ 综合hitSize=@ 代表类型=@(hitSize=@) 贴图缩放=@",
                u.type.name, u.type.flying, u.hitSize(), dom instanceof UnitType ? ((UnitType)dom).name : "-",
                dom instanceof UnitType ? ((UnitType)dom).hitSize : -1f,
                dom instanceof UnitType ? (u.hitSize() / ((UnitType)dom).hitSize) : -1f);
            Log.info("[drv] duo 稳定后: 类=@ 成员=@ 挂座=@ 能力=@ 有武器=@", u.getClass().getName(), memberCount(u),
                u.mounts() == null ? -1 : u.mounts().length, u.abilities() == null ? -1 : u.abilities().length, u.hasWeapons());
            int idx = 0;
            if(u.mounts() != null) for(var m : u.mounts()){
                Log.info("[drv]   挂座@ 武器=@ mount=@ bullet=@ 目标=@", idx++,
                    m.weapon.getClass().getSimpleName(), m.getClass().getSimpleName(),
                    m.weapon.bullet == null ? "null" : m.weapon.bullet.getClass().getSimpleName(),
                    m.target == null ? "null" : m.target.getClass().getSimpleName());
            }
            if(duoEnemy != null)
                Log.info("[drv] duo 敌方靶子: 血=@/@ 在=@,@ 巨兽在=@,@ 玩家单位=@", duoEnemy.health(), duoEnemy.maxHealth(),
                    (int)duoEnemy.x, (int)duoEnemy.y, (int)u.x, (int)u.y,
                    Vars.player.unit() == null ? "null" : Vars.player.unit().type.name);
            if(duoAlly != null)
                Log.info("[drv] duo 伤员: 血=@%@", (int)(duoAlly.healthf() * 100));
            for(var m : u.mounts())
                Log.info("[drv] duo 挂座目标: 武器=@ target=@ shoot=@", m.weapon.getClass().getSimpleName(),
                    m.target == null ? "null" : m.target.getClass().getSimpleName(), m.shoot);
        }catch(Throwable t){ Log.err("[drv] duoReport failed", t); }
    }

    // ---------------- 组合巨兽：两艘船在深水里合体（水阻） ----------------
    static int shipOx = -1, shipOy = -1;
    static Unit shipMegaUnit, rissoA, rissoB, shipRef;

    static void setupShipScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            // 关掉战争迷雾：否则深水那块是没探索过的，单位 inFogTo() 直接不画，截图上啥都看不到
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            int ox = -1, oy = -1;
            outer:
            for(int y = 45; y < 150; y++){
                for(int x = 40; x < 200; x++){
                    boolean ok = true;
                    for(int dy = -2; dy <= 2 && ok; dy++) for(int dx = -3; dx <= 3; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor() == null || !t.floor().isDeep() || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] shipmega: 没找到深水"); return; }
            // 核心必须放在**陆地**上（水里 placeBL 会失败 → 队伍没核心 → 那块水域没被探索 →
            // 单位 inFogTo() 为真，截图里什么都看不到）
            int coreX = -1, coreY = -1;
            outerCore:
            for(int r = 3; r <= 12; r++){
                for(int dy = -r; dy <= r; dy++) for(int dx = -r; dx <= r; dx++){
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    Tile t = Vars.world.tile(ox + dx, oy + dy);
                    if(t != null && t.floor() != null && !t.floor().isLiquid && t.block() == Blocks.air){ coreX = ox + dx; coreY = oy + dy; break outerCore; }
                }
            }
            if(coreX >= 0){
                Building core = placeBL(Blocks.coreShard, coreX, coreY);
                if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
                Log.info("[drv] shipmega: 岸上核心 @,@ 放入=@", coreX, coreY, core != null);
            }else Log.err("[drv] shipmega: 附近没找到陆地放核心");
            shipOx = ox; shipOy = oy;
            Log.info("[drv] shipmega: 深水=@,@ 地形=@", ox, oy, Vars.world.tile(ox, oy).floor().name);
            Timer.schedule(Driver::shipSpawn, 2f);
        }catch(Throwable t){ Log.err("[drv] setupShipScene failed", t); }
    }

    static void shipSpawn(){
        try{
            float cx = shipOx * 8f, cy = shipOy * 8f;
            rissoA = UnitTypes.risso.create(Team.sharded);
            rissoA.set(cx - 12f, cy);
            rissoA.add();
            rissoB = UnitTypes.risso.create(Team.sharded);
            rissoB.set(cx + 12f, cy);
            rissoB.add();
            // 对照船放远一点：merge 会把 160 单位内的同队未编组单位一起并走
            shipRef = UnitTypes.risso.create(Team.sharded);
            shipRef.set(cx + 220f, cy);
            shipRef.add();
            Core.camera.position.set(cx, cy);
            Log.info("[drv] shipmega: 两艘 risso + 一台对照船已就位");
        }catch(Throwable t){ Log.err("[drv] shipSpawn failed", t); }
    }

    static void shipMerge(){
        try{
            Object merged = rissoA == null ? null
                : combineCall("combineunit.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, rissoA);
            if(merged instanceof Unit u) shipMegaUnit = u;
            Log.info("[drv] shipmega 融合结果=@", merged);
        }catch(Throwable t){ Log.err("[drv] shipMerge failed", t); }
    }

    static void shipReport(){
        try{
            if(shipMegaUnit == null){ Log.info("[drv] shipmega: 没有巨兽"); return; }
            Unit u = shipMegaUnit;
            camTarget = u;
            installCameraLock();
            Core.camera.position.set(u.x, u.y);
            if(shipRef != null && shipRef.isValid())
                Log.info("[drv] shipmega 对照（同地形）: 巨兽 speed=@ 系数=@ 有效=@（地形 @） | 原版 risso speed=@ 系数=@ 有效=@",
                    u.type.speed, u.floorSpeedMultiplier(), u.type.speed * u.floorSpeedMultiplier(),
                    u.floorOn() == null ? "null" : u.floorOn().name,
                    shipRef.type.speed, shipRef.floorSpeedMultiplier(),
                    shipRef.type.speed * shipRef.floorSpeedMultiplier());
            Log.info("[drv] shipmega: type=@ hitSize=@ 溺水=@ elevation=@ 盾=@", u.type.name, u.hitSize(), u.canDrown(), u.elevation, u.shield());
        }catch(Throwable t){ Log.err("[drv] shipReport failed", t); }
    }

    static void megaCommandMode(){
        try{
            if(megaUnit == null) return;
            // 真按 Shift 进指挥模式做不到（commandMode 每帧按按键状态重算），
            // 所以这里把"命令菜单列表用的那个控件"直接搭出来：原版面板用的是
            // StatValues.stack(content.unit(unit.type.id), 数量) → stack(type.uiIcon, ...)，
            // 画出来的是不是巨兽自己的图标，一眼就能看。
            Vars.control.input.selectedUnits.clear();
            Vars.control.input.selectedUnits.add(megaUnit);
            arc.Events.fire(mindustry.game.EventType.Trigger.unitCommandChange);

            mindustry.type.UnitType resolved = Vars.content.unit(megaUnit.type.id);
            Object dominant = field(megaUnit.getClass(), megaUnit, "dominant");
            String domIcon = "-", resIcon = "-";
            if(dominant instanceof UnitType dt && dt.uiIcon != null) domIcon = regionName(dt.uiIcon);
            if(resolved != null && resolved.uiIcon != null) resIcon = regionName(resolved.uiIcon);
            Log.info("[drv] 指挥模式: 巨兽 type=@(id=@) → content.unit(id)=@(@) 名字=@",
                megaUnit.type.name, megaUnit.type.id, resolved == null ? "null" : resolved.name,
                resolved == null ? "-" : resolved.getClass().getSimpleName(),
                resolved == null ? "-" : resolved.localizedName);
            Log.info("[drv]   代表成员图标=@ 面板会用的图标=@ 同一个=@", domIcon, resIcon, domIcon.equals(resIcon));
            Log.info("[drv]   选择集=@", Vars.control.input.selectedUnits.size);

            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            panel.add("命令菜单里那一格（StatValues.stack(content.unit(type.id), 1)）：").left().row();
            panel.table(t -> {
                t.left();
                t.add(mindustry.world.meta.StatValues.stack(resolved, 1)).pad(6f);
                t.add(new arc.scene.ui.Image(UnitTypes.dagger.uiIcon)).size(32f).pad(6f).tooltip("这是 dagger（旧 bug 里显示的那个）");
                t.add("← 左=现在面板会显示的；右=dagger（旧 bug 对照）").left().padLeft(6f);
            }).left().row();
            // 命令菜单里的指令按钮行（原版面板就是遍历 content.unit(id).commands 画这些）
            panel.add("命令按钮（面板遍历 type.commands 画的就是这些）：").left().padTop(8f).row();
            StringBuilder names = new StringBuilder();
            panel.table(t -> {
                t.left();
                int col = 0;
                for(var command : resolved.commands){
                    t.button(mindustry.gen.Icon.icons.get(command.icon, mindustry.gen.Icon.cancel), mindustry.ui.Styles.clearNoneTogglei, () -> {})
                        .size(44f).pad(3f).tooltip(command.localized());
                    if(++col % 8 == 0) t.row();
                }
            }).left().row();
            for(var command : resolved.commands) names.append(command.name).append(' ');
            Log.info("[drv]   面板会用到的指令: @", names.toString());
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("指挥模式单位图标核对");
            d.cont.add(panel).pad(10f);
            d.show();
        }catch(Throwable t){ Log.err("[drv] megaCommandMode failed", t); }
    }

    static void megaMerge(){
        try{
            Log.info("[drv] 融合前: Groups.unit=@ mace 有效=@ 已添加=@ 血=@ oct 有效=@ 已添加=@ 血=@ 盾=@",
                Groups.unit.count(u -> true),
                maceUnit == null ? "-" : maceUnit.isValid(), maceUnit == null ? "-" : maceUnit.isAdded(), maceUnit == null ? -1f : maceUnit.health(),
                octUnit == null ? "-" : octUnit.isValid(), octUnit == null ? "-" : octUnit.isAdded(), octUnit == null ? -1f : octUnit.health(),
                octUnit == null ? -1f : octUnit.shield());
            Object gid = combineCall("combineunit.units.UnitComboDamage", "comboId", new Class<?>[]{Unit.class}, maceUnit);
            Log.info("[drv] mace comboId=@", gid);
            Object merged = combineCall("combineunit.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, maceUnit);
            Log.info("[drv] 融合结果=@", merged);
            if(merged instanceof Unit u) megaUnit = u;
            if(megaUnit != null){
                megaDump("融合后");
                shot("mega_world");
            }
        }catch(Throwable t){ Log.err("[drv] megaMerge failed", t); }
    }

    static String regionName(arc.graphics.g2d.TextureRegion r){
        return r == null ? "null" : (r + "@" + System.identityHashCode(r));
    }

    static void megaDump(String tag){
        try{
            if(megaUnit == null){ Log.info("[drv] mega @: 没有巨兽", tag); return; }
            Unit u = megaUnit;
            Object dominant = field(u.getClass(), u, "dominant");
            Object drawScale = field(u.getClass(), u, "drawScale");
            Log.info("[drv] mega @: type=@ 类=@ 有效=@ 已添加=@ hitSize=@ 血=@/@ 盾=@ 力场数=@ 挂座=@ dominant=@ drawScale=@",
                tag, u.type.name, u.getClass().getName(), u.isValid(), u.isAdded(), u.hitSize(), u.health(), u.maxHealth(), u.shield(),
                u.abilities().length, u.mounts().length, dominant, drawScale);
            for(var a : u.abilities()){
                Log.info("[drv]   能力 @ (max=@ scaledMax=@)", a.getClass().getName(),
                    a instanceof mindustry.entities.abilities.ForceFieldAbility ff ? ff.max : "-",
                    a instanceof mindustry.entities.abilities.ForceFieldAbility ff ? ff.scaledMax(u) : "-");
            }
            try{
                Class<?> mt = Class.forName("combineunit.units.mega.MegaUnitType", true, ml);
                Log.info("[drv]   type 是 MegaUnitType? = @ 类=@", mt.isInstance(u.type), u.type.getClass().getName());
            }catch(Throwable t){ Log.err("[drv] 类型检查失败", t); }
            if(dominant instanceof UnitType dt){
                Log.info("[drv]   dominant fullIcon=@ found=@ region=@ found=@ uiIcon=@",
                    regionName(dt.fullIcon), dt.fullIcon != null && Core.atlas.isFound(dt.fullIcon),
                    regionName(dt.region), dt.region != null && Core.atlas.isFound(dt.region), regionName(dt.uiIcon));
            }
            Log.info("[drv]   type.fullIcon=@ found=@ type.region=@ found=@ drawShields=@ flying=@",
                regionName(u.type.fullIcon), u.type.fullIcon != null && Core.atlas.isFound(u.type.fullIcon),
                regionName(u.type.region), u.type.region != null && Core.atlas.isFound(u.type.region),
                u.type.drawShields, u.type.flying);
            // 绘制裁剪：EntityGroup.draw 用 clipSize 做视口裁剪
            try{
                float clip = u.clipSize();
                arc.math.geom.Rect vp = Core.camera.bounds(new arc.math.geom.Rect());
                boolean overlaps = vp.overlaps(u.x - clip / 2f, u.y - clip / 2f, clip, clip);
                boolean inDrawGroup = false;
                int drawSize = 0;
                for(var d : Groups.draw){
                    drawSize++;
                    if(d == (Object)u) inDrawGroup = true;
                }
                Log.info("[drv]   clipSize=@ 视口=@x@+@x@ 裁剪结果=@ 在绘制组=@ 绘制组大小=@ 在单位组=@",
                    clip, (int)vp.x, (int)vp.y, (int)vp.width, (int)vp.height, overlaps, inDrawGroup, drawSize,
                    Groups.unit.contains(o -> o == u));
            }catch(Throwable t){ Log.err("[drv] 裁剪诊断失败", t); }
        }catch(Throwable t){ Log.err("[drv] megaDump failed", t); }
    }

    static Object field(Class<?> c, Object o, String name){
        Class<?> k = c;
        while(k != null){
            try{
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            }catch(Throwable ignored){ k = k.getSuperclass(); }
        }
        return null;
    }

    /** 按对象自己找字段（mech 模式用；巨兽实体的字段在派生类型上，得多走一层父类）。 */
    static Object field(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null;
    }

    static float megaShieldMax(){
        for(var a : megaUnit.abilities()){
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff) return ff.max;
        }
        return -1f;
    }

    static void run(int ticks){
        for(int i = 0; i < ticks; i++){
            arc.util.Time.delta = 1f;
            Vars.logic.update();
        }
    }

    /** 帧计数器：挂在 HUD 上的空表，只用来数"画面真的跑了多少帧"。 */
    static int frames = 0, framesAtAdd = -1;
    static void installFrameCounter(){
        var t = new arc.scene.ui.layout.Table();
        t.update(() -> frames++);
        t.touchable = arc.scene.event.Touchable.disabled;
        Vars.ui.hudGroup.addChild(t);
    }

    static void hideDialogs(){
        try{
            for(arc.scene.Element e : Core.scene.root.getChildren()){
                if(e instanceof arc.scene.ui.Dialog d){
                    // 弹窗里往往就是"被踢/连接失败"的原因（比如 @disconnect.closed），先把文字打出来再关
                    String text = dialogText(d);
                    Log.info("[drv] 关掉弹窗 @ @", d.getClass().getSimpleName(), text.isEmpty() ? "" : ("→ " + text));
                    d.hide();
                }
            }
        }catch(Throwable t){ Log.err("[drv] hideDialogs failed", t); }
    }

    /** 递归收集弹窗里的文字（诊断"被服务器踢了/连接失败"的原因）。 */
    static String dialogText(arc.scene.Element e){
        StringBuilder sb = new StringBuilder();
        collectText(e, sb);
        return sb.length() > 300 ? sb.substring(0, 300) : sb.toString();
    }

    static void collectText(arc.scene.Element e, StringBuilder sb){
        try{
            if(e instanceof arc.scene.ui.Label l && l.getText() != null && l.getText().length() > 0){
                if(sb.length() > 0) sb.append(" | ");
                sb.append(l.getText());
            }
            if(e instanceof arc.scene.Group g) for(arc.scene.Element c : g.getChildren()) collectText(c, sb);
        }catch(Throwable ignored){}
    }

    static int sfPhase = 1;

    /** 阶段 1 = 自己模拟输入尾巴；阶段 2 = 只按住开火，其余交给原版 DesktopInput。 */
    static void sfLoop(){
        if(sfBeast == null || !sfBeast.isAdded()) return;
        if(sfPhase == 1){
            sfFireLoop();
        }else{
            Vars.player.shooting = true;
        }
    }

    /**
     * 场景阶段**每秒**清一次弹窗。
     * 客户端的"检查更新"弹窗是启动后隔几秒才弹出来的（软渲染下时机不固定），
     * 只在场景开头清一次会正好被它盖住截图（照片里全是版本列表，看不到单位）。
     */
    static void keepDialogsHidden(){
        Timer.schedule(Driver::hideDialogs, 2f, 1f, 60);
    }

    static Unit megaUnit(){
        for(Unit u : Groups.unit)
            if(u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")) return u;
        return null;
    }


    // ---------------- hover / cell（悬浮单位与 cell 贴图） ----------------
    static Unit hoverSolo, crawlSolo, hoverBeast, crawlBeast;

    static void setupHoverScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            int ox=-1, oy=-1;
            outer:
            for(int y=45;y<120;y++) for(int x=40;x<190;x++){
                boolean ok = true;
                for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-6;dx<=6;dx++){
                    Tile t = Vars.world.tile(x+dx, y+dy);
                    if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                }
                if(ok){ ox=x; oy=y; break outer; }
            }
            if(ox < 0){ Log.err("[drv] hover: 没找到陆地"); return; }
            Building core = placeBL(Blocks.coreShard, ox + 14, oy + 8);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            float cx = ox * 8f, cy = oy * 8f;
            // 一排摆好：单独 elude、单独 crawler（融合后巨兽插到各自右边）
            hoverSolo = UnitTypes.elude.create(Team.sharded);   hoverSolo.set(cx - 135f, cy); hoverSolo.add();
            crawlSolo = UnitTypes.crawler.create(Team.sharded); crawlSolo.set(cx + 15f, cy); crawlSolo.add();
            camTarget = hoverSolo;
            Vars.renderer.setScale(2.5f);
            Core.camera.position.set(cx, cy);
            cellFacts("elude", UnitTypes.elude);
            cellFacts("crawler", UnitTypes.crawler);
            cellFacts("spiroct", UnitTypes.spiroct);
            cellFacts("dagger", UnitTypes.dagger);
            Log.info("[drv] hover 场景: 单独 elude=(@,@) 单独 crawler=(@,@) elude 巨兽=@ crawler 巨兽=@",
                (int)hoverSolo.x, (int)hoverSolo.y, (int)crawlSolo.x, (int)crawlSolo.y, hoverBeast != null, crawlBeast != null);
        }catch(Throwable t){ Log.err("[drv] setupHoverScene failed", t); }
    }

    /** 没有 AI 的控制器：把巨兽钉住，好让"低血量 cell 闪烁"的两张截图在同一个位置。 */
    static class NoopController implements mindustry.entities.units.UnitController{
        Unit u;
        @Override public void unit(Unit u){ this.u = u; }
        @Override public Unit unit(){ return u; }
    }

    static void hoverDamage(){
        // 两个巨兽都压到 30% 血：cell 的低血量脉冲（cellColor 里的 absin）应当看得见
        for(Unit u : new Unit[]{hoverBeast, crawlBeast}){
            if(u != null && u.isAdded()){
                u.health(u.maxHealth() * 0.3f);
                u.controller(new NoopController());   // 冻住，别在两张截图之间跑掉
                u.vel.setZero();
            }
        }
        Log.info("[drv] hover: 巨兽已压到 30% 血（elude=@ crawler=@）",
            hoverBeast == null ? "-" : (int)hoverBeast.health(), crawlBeast == null ? "-" : (int)crawlBeast.health());
    }

    static void cellFacts(String name, UnitType t){
        Object wouldDraw = combineCall("combineunit.units.mega.MegaUnitType", "cellRegionFor", new Class<?>[]{UnitType.class}, t);
        Log.info("[drv] cell 事实 @: drawCell=@ cellRegion=@ found=@ 巨兽绘制会补的 cell=@", name, t.drawCell,
            regionName(t.cellRegion), t.cellRegion != null && Core.atlas.isFound(t.cellRegion), regionName((arc.graphics.g2d.TextureRegion)wouldDraw));
    }

    static void mergeAt(float x, float y, UnitType a, UnitType b, java.util.function.Consumer<Unit> out){
        try{
            Seq<Unit> us = new Seq<>();
            Unit u1 = a.create(Team.sharded); u1.set(x - 14f, y); u1.add(); us.add(u1);
            Unit u2 = b.create(Team.sharded); u2.set(x + 14f, y); u2.add(); us.add(u2);
            run(2);
            Object mega = combineCall("combineunit.units.UnitComboMerge", "mergeSelected", new Class<?>[]{Seq.class}, us);
            if(mega instanceof Unit mu){ mu.set(x, y); out.accept(mu); }
        }catch(Throwable t){ Log.err("[drv] mergeAt failed", t); }
    }

    static void hoverCenter(){
        float x = 0f, y = 0f; int n = 0;
        for(Unit u : new Unit[]{hoverSolo, crawlSolo, hoverBeast, crawlBeast}){
            if(u != null && u.isAdded()){ x += u.x; y += u.y; n++; }
        }
        if(n > 0) Core.camera.position.set(x / n, y / n);
    }

    static void hoverMerge(){
        // 融合出各自的巨兽（在"单独一只"的右边并排），镜头对着两台巨兽中间
        float cx = Core.camera.position.x, cy = Core.camera.position.y;
        mergeAt(cx - 30f, cy, UnitTypes.elude, UnitTypes.elude, u -> hoverBeast = u);
        mergeAt(cx + 120f, cy, UnitTypes.crawler, UnitTypes.crawler, u -> crawlBeast = u);
        // 融合完把四个并排摆成一行：单独 elude | elude 巨兽 | 单独 crawler | crawler 巨兽
        if(hoverSolo != null) hoverSolo.set(cx - 135f, cy);
        if(hoverBeast != null) hoverBeast.set(cx - 45f, cy);
        if(crawlSolo != null) crawlSolo.set(cx + 45f, cy);
        if(crawlBeast != null) crawlBeast.set(cx + 135f, cy);
        camTarget = hoverBeast != null ? hoverBeast : hoverSolo;
        Vars.renderer.setScale(2.5f);
        Log.info("[drv] hover: elude 巨兽=@ crawler 巨兽=@ 单独 elude=@ 单独 crawler=@",
            hoverBeast == null ? "无" : hoverBeast.type.name, crawlBeast == null ? "无" : crawlBeast.type.name,
            hoverSolo == null ? "无" : hoverSolo.type.name, crawlSolo == null ? "无" : crawlSolo.type.name);
    }

    // ---------------- icon（指挥模式图标：巨兽代表类型 vs 面板按 type.id 取到的类型） ----------------
    static Unit iconBeast;


    /** 现场合 count 只同型单位（Driver 里没有这个 helper，icon 模式用）。 */
    static Unit mergeMany(float x, float y, UnitType type, int count){
        try{
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < count; i++){
                Unit u = type.create(Team.sharded);
                u.set(x + i * 12f, y);
                u.add();
                us.add(u);
            }
            run(2);
            Object mega = combineCall("combineunit.units.UnitComboMerge", "mergeSelected", new Class<?>[]{Seq.class}, us);
            if(mega instanceof Unit mu){ mu.set(x, y); return mu; }
        }catch(Throwable t){ Log.err("[drv] mergeMany failed", t); }
        return null;
    }

    /** 读用户存档（有的话），否则现场合 toxopid×3 + corvus×3 —— 复现"占位类型图标被覆盖"。 */
    static void setupIconScene(){
        try{
            hideDialogs();
            arc.files.Fi save = null;
            try{
                // 用游戏自己的数据目录 API（run-client.sh 传的是 -Dmindustry.data.dir）
                arc.files.Fi dir = Core.settings.getDataDirectory().child("saves");
                Log.info("[drv] icon: 找存档目录 @ exists=@", dir.absolutePath(), dir.exists());
                if(dir.exists()) for(arc.files.Fi f : dir.list()){
                    Log.info("[drv] icon:   存档候选 @", f.name());
                    if(f.name().endsWith(".msav") && !f.name().contains("backup")){ save = f; break; }
                }
            }catch(Throwable t){ Log.err("[drv] icon: 找不到存档目录", t); }
            if(save != null){
                Log.info("[drv] icon: 读用户存档 @", save.name());
                mindustry.io.SaveIO.load(save);
            }else{
                var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
                Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            }
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            run(30);
            pickIconBeast();
            if(iconBeast == null){
                Log.info("[drv] icon: 存档里没有巨兽，现场合一只 toxopid（对照一只 corvus）");
                float cx = Core.camera.position.x, cy = Core.camera.position.y;
                // 先 toxopid 后 corvus：后者的推导会覆盖共用的占位类型（复现用户的现场）
                Unit a = mergeMany(cx, cy, UnitTypes.toxopid, 3);
                Unit b = mergeMany(cx + 120f, cy, UnitTypes.corvus, 3);
                iconBeast = a != null ? a : b;
            }
            Log.info("[drv] icon: 选中目标 = @（代表类型 @）", iconBeast == null ? "无" : iconBeast.id(),
                iconBeast == null ? "-" : dominantName(iconBeast));
        }catch(Throwable t){ Log.err("[drv] setupIconScene failed", t); }
    }

    /** 挑一只 toxopid 巨兽（用户报的正是"3 个 toxopid 合体后显示成 corvus"）；没有就挑第一只非 corvus 的。 */
    static void pickIconBeast(){
        Unit any = null, nonCorvus = null;
        for(Unit u : Groups.unit){
            if(!u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")) continue;
            if(any == null) any = u;
            String dom = dominantName(u);
            if(dom.equals("toxopid")){ iconBeast = u; return; }
            if(nonCorvus == null && !dom.equals("corvus")) nonCorvus = u;
        }
        iconBeast = nonCorvus != null ? nonCorvus : any;
    }

    static String dominantName(Unit u){
        Object o = field(u.getClass(), u, "dominant");
        return o instanceof UnitType t ? t.name : "null";
    }

    static void iconSelect(){
        if(iconBeast == null) return;
        Vars.control.input.selectedUnits.clear();
        Vars.control.input.selectedUnits.add(iconBeast);
        // 真的切进指挥模式：截图里才会出现那块"指挥模式"面板（用户报的那张图就是它）
        try{ Vars.control.input.commandMode = true; }catch(Throwable ignored){}
        Core.camera.position.set(iconBeast.x, iconBeast.y);
        camTarget = iconBeast;
        Log.info("[drv] icon: 已选中巨兽 @（指挥模式选择集大小=@）", iconBeast.id(),
            Vars.control.input.selectedUnits.size);
    }

    /** 打印"面板会用的图标/指令" vs "巨兽代表成员的图标/指令"。 */
    static void iconReport(){
        try{
            int beasts = 0;
            for(Unit u : Groups.unit){
                if(!u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")) continue;
                beasts++;
                UnitType resolved = Vars.content.unit(u.type.id);
                Object domO = field(u.getClass(), u, "dominant");
                UnitType dom = domO instanceof UnitType t ? t : null;
                Log.info("[drv] icon 巨兽@ id=@ 代表类型=@", beasts, u.id(), dom == null ? "-" : dom.name);
                Log.info("[drv]   面板按 type.id 取到的类型=@（名字=@）  面板图标=@", resolved == null ? "null" : resolved.name,
                    resolved == null ? "-" : resolved.localizedName, regionName(resolved == null ? null : resolved.uiIcon));
                Log.info("[drv]   代表成员自己的图标=@  是否一致=@", regionName(dom == null ? null : dom.uiIcon),
                    resolved != null && dom != null && resolved.uiIcon == dom.uiIcon);
            }
            Log.info("[drv] icon 场上有 @ 只巨兽", beasts);
        }catch(Throwable t){ Log.err("[drv] iconReport failed", t); }
    }

    // ---------------- mid（用户编组 3 toxopid + 3 pulsar + elude 的武器落位） ----------------
    static Unit midBeast;
    static int midOx = -1, midOy = -1;

    static void setupMidScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            int ox = -1, oy = -1;
            outer:
            for(int y=50;y<120;y++){
                for(int x=40;x<190;x++){
                    boolean ok = true;
                    for(int dy=-4;dy<=4 && ok;dy++) for(int dx=-5;dx<=5;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] mid 没找到陆地"); return; }
            Building core = placeBL(Blocks.coreShard, ox + 20, oy + 12);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            midOx = ox; midOy = oy;
            Core.camera.position.set(ox * 8f, oy * 8f);
        }catch(Throwable t){ Log.err("[drv] setupMidScene failed", t); }
    }

    static void midMerge(){
        try{
            float cx = midOx * 8f, cy = midOy * 8f;
            Seq<Unit> us = new Seq<>();
            UnitType[] comp = {UnitTypes.toxopid, UnitTypes.toxopid, UnitTypes.toxopid,
                               UnitTypes.pulsar, UnitTypes.pulsar, UnitTypes.pulsar, UnitTypes.elude};
            for(int i = 0; i < comp.length; i++){
                Unit u = comp[i].create(Team.sharded);
                u.set(cx + (i - 3) * 26f, cy + (i % 2 == 0 ? -30f : 30f));
                u.add();
                us.add(u);
            }
            run(2);
            Object mega = combineCall("combineunit.units.UnitComboMerge", "mergeSelected", new Class<?>[]{Seq.class}, us);
            if(mega instanceof Unit mu){
                mu.set(cx, cy);
                midBeast = mu;
                camTarget = mu;
                // 钉住姿态：rotation=90（屏幕上方 = 单位正前方），每帧把全部枪口钉在 90°、速度清零。
                // 这样截图里"枪管画在哪儿"就直接等于该挂载的 (x,y)，能和日志逐把对上。
                var t = new arc.scene.ui.layout.Table();
                t.touchable = arc.scene.event.Touchable.disabled;
                t.update(() -> {
                    if(midBeast == null || !midBeast.isAdded()) return;
                    midBeast.rotation(90f);
                    midBeast.vel().setZero();
                    for(var m : midBeast.mounts()) m.rotation = 90f;
                });
                Vars.ui.hudGroup.addChild(t);
            }else{
                Log.err("[drv] mid: 融合失败（返回 @）", mega);
            }
        }catch(Throwable t){ Log.err("[drv] midMerge failed", t); }
    }

    static void midDump(){
        try{
            if(midBeast == null){ Log.err("[drv] mid: 没有巨兽"); return; }
            var mounts = midBeast.mounts();
            StringBuilder sb = new StringBuilder();
            int midN = 0;
            float sumY = 0f;
            for(int i = 0; i < mounts.length; i++){
                mindustry.type.Weapon w = mounts[i].weapon;
                sb.append("\n      [").append(i).append("] ").append(w.name)
                  .append(" x=").append(String.format("%.1f", w.x)).append(" y=").append(String.format("%.1f", w.y));
                if(Math.abs(w.x) < 0.01f){ midN++; sumY += w.y; }
            }
            Log.info("[drv] mid 巨兽 mounts=@ hitSize=@ rotation=@ x=0 的武器=@ 把（y 之和=@）:@",
                mounts.length, midBeast.hitSize(), midBeast.rotation(), midN, String.format("%.2f", sumY), sb);
        }catch(Throwable t){ Log.err("[drv] midDump failed", t); }
    }

    // ---------------- tank（坦克巨兽的履带绘制 / 碾压） ----------------
    static Unit tankBeast, tankRef;

    static void tankMerge(){
        try{
            float cx = midOx * 8f, cy = midOy * 8f;
            Seq<Unit> us = new Seq<>();
            UnitType[] comp = {UnitTypes.vanquish, UnitTypes.vanquish, UnitTypes.conquer};
            for(int i = 0; i < comp.length; i++){
                Unit u = comp[i].create(Team.sharded);
                u.set(cx - 40f + i * 30f, cy);
                u.add();
                us.add(u);
            }
            run(2);
            Object mega = combineCall("combineunit.units.UnitComboMerge", "mergeSelected", new Class<?>[]{Seq.class}, us);
            if(mega instanceof Unit mu){
                mu.set(cx, cy);
                tankBeast = mu;
                camTarget = mu;
                // 参照：单独一台 vanquish，放在巨兽右边同一张图里对比履带比例
                tankRef = UnitTypes.vanquish.create(Team.sharded);
                tankRef.set(cx + 150f, cy);
                tankRef.add();
            }else{
                Log.err("[drv] tank: 融合失败（返回 @）", mega);
            }
        }catch(Throwable t){ Log.err("[drv] tankMerge failed", t); }
    }

    /** 让巨兽往前慢慢开（履带滚动帧只在走起来时变），路上铺一排脆弱的敌方方块给它压。 */
    static void tankDrive(){
        try{
            if(tankBeast == null || !tankBeast.isAdded()) return;
            tankBeast.rotation(90f);
            tankBeast.vel().set(0f, 0.6f);
            if(tankRef != null && tankRef.isAdded()) tankRef.rotation(90f);
        }catch(Throwable t){ Log.err("[drv] tankDrive failed", t); }
    }

    // ---------------- flight（设计稿的"能不能飞"口径：地面为主不飞、空军为主才飞） ----------------
    static Unit flightGround, flightAir;

    static void flightScene(){
        try{
            float cx = midOx * 8f, cy = midOy * 8f;
            flightGround = combineMerge(cx - 110f, cy + 60f, UnitTypes.dagger, UnitTypes.dagger, UnitTypes.flare);
            flightAir = combineMerge(cx + 110f, cy - 20f, UnitTypes.flare, UnitTypes.flare, UnitTypes.dagger);
            // 镜头挂在空军那只上：地面那只在画面左侧、空军那只在中间偏右，两只都能入镜
            camTarget = flightAir;
        }catch(Throwable t){ Log.err("[drv] flightScene failed", t); }
    }

    /** 现场融合一个编组（返回巨兽，失败返回 null）。 */
    static Unit combineMerge(float x, float y, UnitType... types){
        try{
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = types[i].create(Team.sharded);
                u.set(x + i * 24f, y);
                u.add();
                us.add(u);
            }
            run(2);
            Object mega = combineCall("combineunit.units.UnitComboMerge", "mergeSelected", new Class<?>[]{Seq.class}, us);
            if(mega instanceof Unit mu){ mu.set(x, y); return mu; }
        }catch(Throwable t){ Log.err("[drv] combineMerge failed", t); }
        return null;
    }

    static void flightReport(){
        try{
            for(var e : new Object[][]{{"地面为主 2×dagger+1×flare", flightGround}, {"空军为主 2×flare+1×dagger", flightAir}}){
                Unit u = (Unit)e[1];
                if(u == null){ Log.err("[drv] flight: @ 没有巨兽", e[0]); continue; }
                Log.info("[drv] flight @: hitSize=@ type.flying=@ elevation=@ isFlying=@ moveMode=@ canShoot=@",
                    e[0], u.hitSize(), u.type.flying, u.elevation(), u.isFlying(), field(u, "moveMode") != null ? "?" : "?",
                    u.canShoot());
            }
        }catch(Throwable t){ Log.err("[drv] flightReport failed", t); }
    }

    // ---------------- sf（饱和火力 mod 的神渎：玩家控制能不能开火） ----------------
    static Unit sfBeast;
    static UnitType sfType;
    static long sfShots0 = -1;
    static Object sfInput;   // Vars.control.input（InputHandler），用来问 canShoot()

    static UnitType findByName(String part){
        for(UnitType t : Vars.content.units()) if(t.name.contains(part)) return t;
        return null;
    }

    static void sfMerge(){
        try{
            sfType = findByName("神渎");
            if(sfType == null){
                Log.err("[drv] sf: 内容里找不到神渎单位（装了饱和火力吗？）");
                for(UnitType t : Vars.content.units()) if(t.name.contains("饱和")) Log.info("[drv]   候选 @", t.name);
                return;
            }
            sfInput = field(Vars.control, "input");
            float cx = midOx * 8f, cy = midOy * 8f;
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < 3; i++){
                Unit u = sfType.create(Team.sharded);
                u.set(cx - 200f + i * 200f, cy);
                u.add();
                us.add(u);
            }
            run(2);
            Object mega = combineCall("combineunit.units.UnitComboMerge", "mergeSelected", new Class<?>[]{Seq.class}, us);
            if(mega instanceof Unit mu){
                mu.set(cx, cy);
                mu.rotation(90f);
                sfBeast = mu;
                camTarget = mu;
                Log.info("[drv] sf: 神渎=@ hitSize=@ 融合后 mounts=@ type=@",
                    sfType.name, sfType.hitSize, mu.mounts().length, mu.type.name);
            }else{
                Log.err("[drv] sf: 融合失败（返回 @）", mega);
            }
        }catch(Throwable t){ Log.err("[drv] sfMerge failed", t); }
    }

    static void sfTakeControl(){
        try{
            if(sfBeast == null) return;
            Vars.player.unit(sfBeast);
            Log.info("[drv] sf: 玩家单位=@（== 巨兽? @）", Vars.player.unit() == null ? "null" : Vars.player.unit().type.name,
                Vars.player.unit() == sfBeast);
            sfShots0 = totalShots(sfBeast);
        }catch(Throwable t){ Log.err("[drv] sfTakeControl failed", t); }
    }

    /** 每帧照抄 DesktopInput.updateMovement 的尾巴：模拟"玩家按着开火键"。 */
    static void sfFireLoop(){
        try{
            if(sfBeast == null || !sfBeast.isAdded()) return;
            boolean mech = sfBeast instanceof mindustry.gen.Mechc;
            boolean boosted = mech && sfBeast.isFlying();
            Vars.player.shooting = true;                       // 相当于鼠标按住
            sfBeast.aim(sfBeast.x, sfBeast.y + 400f, true);
            sfBeast.controlWeapons(true, Vars.player.shooting && !boosted);
        }catch(Throwable t){ Log.err("[drv] sfFireLoop failed", t); }
    }

    static long totalShots(Unit u){
        long n = 0;
        for(var m : u.mounts()) n += m.totalShots;
        return n;
    }

    static void sfReport(){
        try{
            if(sfBeast == null){ Log.err("[drv] sf: 没有巨兽"); return; }
            boolean canShoot = true;
            try{
                canShoot = (Boolean)sfInput.getClass().getMethod("canShoot").invoke(sfInput);
            }catch(Throwable ignored){ }
            Log.info("[drv] sf 状态: player.shooting=@ input.canShoot()=@ isFlying=@ elevation=@ Mechc=@ canBoost=@"
                + " omniMovement=@ faceTarget=@ hasWeapons=@ type.weapons=@ 开火总数=@",
                Vars.player.shooting, canShoot, sfBeast.isFlying(), sfBeast.elevation(), sfBeast instanceof mindustry.gen.Mechc,
                sfBeast.type.canBoost, sfBeast.type.omniMovement, sfBeast.type.faceTarget, sfBeast.hasWeapons(),
                sfBeast.type.weapons.size, totalShots(sfBeast) - Math.max(sfShots0, 0));
        }catch(Throwable t){ Log.err("[drv] sfReport failed", t); }
    }

    static void sfReportWeapons(){
        try{
            if(sfBeast == null) return;
            int i = 0;
            for(var m : sfBeast.mounts()){
                Log.info("[drv]   mount[@] @ x=@ y=@ controllable=@ shoot=@ rotate=@ warmup=@ reload=@ totalShots=@",
                    i, m.weapon.name, m.weapon.x, m.weapon.y, m.weapon.controllable, m.shoot, m.rotate,
                    m.warmup, m.reload, m.totalShots);
                i++;
            }
        }catch(Throwable t){ Log.err("[drv] sfReportWeapons failed", t); }
    }

    static void tankReport(){
        try{
            if(tankBeast == null){ Log.err("[drv] tank: 没有巨兽"); return; }
            var tc = (mindustry.gen.Tankc)tankBeast;
            Log.info("[drv] tank 巨兽: hitSize=@ attKind=@ treadTime=@ walked=@ crushDamage=@ crushFragile=@ radius=@",
                tankBeast.hitSize(), field(tankBeast, "attKind"), tc.treadTime(), tc.walked(),
                tankBeast.type.crushDamage, tankBeast.type.crushFragile,
                (int)(tankBeast.hitSize() * 0.75f / 8f));
            Log.info("[drv] tank 参照（单台 vanquish）: treadRects=@ treadFrames=@ crushDamage=@",
                UnitTypes.vanquish.treadRects.length, UnitTypes.vanquish.treadFrames, UnitTypes.vanquish.crushDamage);
        }catch(Throwable t){ Log.err("[drv] tankReport failed", t); }
    }

    static void shot(String name){
        try{
            if(counter < 0) counter = nextIndex();
            arc.files.Fi f = Core.files.absolute(outDir).child(String.format("%03d_%s.png", counter++, name));
            ScreenUtils.saveScreenshot(f);
            Log.info("[drv] shot @  (@x@)", f.name(), Core.graphics.getWidth(), Core.graphics.getHeight());
        }catch(Throwable t){ Log.err("[drv] shot failed: @", name, t); }
    }


    static Building place(Block b, int x, int y){
        mindustry.world.Build.beginPlace(null, b, Team.sharded, x, y, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x, y), b, null, (byte)0, Team.sharded, null);
        return Vars.world.build(x, y);
    }

    /** 和 headless 测试同一套坐标约定：(x,y) 是方块左下角，锚点 = 左下角 + (size-1)/2。 */
    static Building placeBL(Block b, int x, int y){
        return placeBL(b, x, y, Team.sharded);
    }

    static Building placeBL(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, team, null);
        return Vars.world.build(ax, ay);
    }
}
