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
            }else{
                Log.err("[drv] 未知模式 @（combineunit 支持 mega|legs|mech|duo|shipmega）", mode);
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
