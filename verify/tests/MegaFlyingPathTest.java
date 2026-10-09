package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.math.Mathf; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.ai.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.game.*; import mindustry.gen.*; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.Tile;

/**
 * 用户报（组合战役 combinec）：**组合敌方波次之后，如果那一波里有一些别的类型的单位，
 * 合出来的组合巨兽不会向玩家核心进攻，而是停在原地**（举例：tarFields 的第 34 波）。
 *
 * <p>根因：派生类型的 `flowfieldPathType` 照抄原版 `initPathType()` 的顺序
 * "naval → allowLegStep → flying → hovering → ground"，把**腿类**排在**飞行**前面。
 * 原版那串顺序没问题，是因为原版一个类型不可能同时是腿类和飞行；派生类型是"成员能力的并集"，
 * 两者会同时成立 —— tarFields 第 34 波就是 spiroct（腿，`allowLegStep`）+ horizon（飞机）：
 * 巨兽既能飞（`canFly` → 每帧 `elevation` 钉在 1，永远悬空），又拿到了腿类的代价表。
 *
 * <p>而腿类代价表是 `PathTile.legSolid(tile) ? impassable : ...` —— **天然岩壁/悬崖那一格
 * 直接判不可达**。能飞的巨兽偏偏就经常悬在这种格子上（悬空不撞墙），于是流场在它自己脚下
 * 返回 -1：`Pathfinder.getTargetTile` 把"当前格"当下一格返回，
 * `AIController.pathfind` 见 `tile == targetTile` 立刻 return —— **一步都不走**，
 * 而且只要它不离开那一格就永远走不了。玩家看到的就是"组合巨兽停在原地、不向核心进攻"。
 *
 * <p>修法：流场代价类型的优先级里飞行必须排在腿类前面（飞行单位的流场就是原版的
 * `flying → costNone`：全图平坦代价、格子永远可达），腿类只在**不能飞**的编组里生效。
 *
 * <p>本测试：
 * <ol>
 *   <li><b>口径钉子</b>：能飞 + 有腿的编组 → `flowfieldPathType=costNone`；纯腿编组 → 仍是
 *       `costLegs`（能力没丢）；纯陆地 → `costGround`；</li>
 *   <li><b>代价表钉子</b>：天然岩壁那一格，`costLegs` 返回 impassable、`costNone` 不返回；</li>
 *   <li><b>复现 + 修复</b>：让能飞 + 有腿的巨兽**悬在天然岩壁上**（tarFields 的真实出生点就是这种格子），
 *       先把 `flowfieldPathType` 按旧行为强制成 `costLegs` → 900 tick 一步不走（复现用户报的现象）；
 *       再恢复成 `costNone` → 明显朝玩家核心逼近。</li>
 * </ol>
 *
 * <p>只装 combineunit 也能跑（波次刷怪由本测试自己按扇区 `rules.spawns` 复刻，不依赖 combinec）。
 */
public class MegaFlyingPathTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;

    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[MFP] "+t); };
        new HeadlessApplication(new MegaFlyingPathTest(), t->t.printStackTrace()); }

    static void check(String n, boolean ok){ System.out.println("[MFP] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    /** 跑逻辑 + 睡一点真实时间：Pathfinder 的流场是独立线程算的，不给它真实时间它永远算不完。 */
    static void runReal(int f, long sleepMs){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); if(sleepMs>0) try{ Thread.sleep(sleepMs); }catch(InterruptedException e){} } }
    static boolean isMega(Unit u){ return u != null && u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity"); }

    static UnitType unit(String name){
        for(UnitType t : Vars.content.units()) if(t.name.equals(name)) return t;
        return null;
    }

    /** 造 unit.type.spawn 之外的普通敌方单位（控制器 = 类型的默认 AI，和波次刷出来的一样）。 */
    static Unit spawn(UnitType t, Team team, float x, float y){
        Unit u = t.spawn(team, x, y, 0f);
        return u;
    }

    /** 把一串单位融合成巨兽（走 u 的服务器权威入口，和波次合体/手动合体同一条路）。 */
    static Unit merge(Seq<Unit> units){
        try{
            return (Unit)Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("mergeSelected", Seq.class).invoke(null, units);
        }catch(Throwable t){ System.out.println("[MFP] 融合失败: " + t); return null; }
    }

    /** 找一格"天然岩壁"（腿类代价表判不可达那种）：离核心至少 minDst 远。 */
    static Tile findLegSolidTile(float minDst){
        Tile best = null;
        for(int y = 8; y < Vars.world.height() - 8; y++){
            for(int x = 8; x < Vars.world.width() - 8; x++){
                Tile t = Vars.world.tile(x, y);
                if(t == null || !t.legSolid()) continue;
                // 要一块够大的岩壁，免得只是边上一格
                if(!(Vars.world.tile(x+1, y) != null && Vars.world.tile(x+1, y).legSolid())) continue;
                if(Vars.state.rules.defaultTeam.core() != null && Mathf.dst(t.worldx(), t.worldy(),
                        Vars.state.rules.defaultTeam.core().x, Vars.state.rules.defaultTeam.core().y) < minDst) continue;
                best = t;
            }
        }
        return best;
    }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();
        ml = Vars.mods.getMod("combineunit").main.getClass().getClassLoader();

        UnitType spiroct = unit("spiroct"), horizon = unit("horizon"), flare = unit("flare"), dagger = UnitTypes.dagger;
        if(spiroct == null || horizon == null || flare == null){ System.out.println("[MFP] 缺原版单位"); System.exit(3); }
        System.out.println("[MFP] spiroct allowLegStep=" + spiroct.allowLegStep + " / horizon flying=" + horizon.flying
            + " / flare flying=" + flare.flying);

        // ---------- 用真的战役扇区：tarFields（第 34 波就是"腿 + 飞机"的混合编组） ----------
        Sector sector = SectorPresets.tarFields.sector;
        Vars.world.loadSector(sector);
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.disableUnitCap = true;
        Vars.logic.play();
        run(20);

        Building core = Vars.state.rules.defaultTeam.core();
        // 打印第 32~36 波的原版阵容（用户报的是第 34 波；波次编号按原版 getSpawned(wave-1) 的口径）
        boolean legsAndFlyer = false;
        System.out.println("[MFP] tarFields 原版阵容（扇区 id=" + sector.id + "）:");
        for(int w = 32; w <= 36; w++){
            StringBuilder sb = new StringBuilder();
            boolean legs = false, flyer = false;
            for(SpawnGroup g : Vars.state.rules.spawns){
                int n = g.getSpawned(w - 1);
                if(n <= 0) continue;
                sb.append(g.type.name).append("×").append(n).append(" ");
                legs |= g.type.allowLegStep;
                flyer |= g.type.flying;
            }
            System.out.println("[MFP]    第" + w + "波: " + sb + (legs && flyer ? "  ← 腿类 + 飞行（踩坑的组合）" : ""));
            if(legs && flyer) legsAndFlyer = true;
        }
        check("tarFields 这几波里存在「腿类 + 飞行」的混合阵容（前置：用户的现场）", legsAndFlyer);

        // ---------- ① 口径钉子：派生类型的流场代价类型 ----------
        {
            Seq<Unit> a = new Seq<>();
            a.add(spawn(horizon, Team.crux, 600f, 600f));
            a.add(spawn(spiroct, Team.crux, 620f, 600f));
            Unit mix = merge(a);
            check("能飞 + 有腿的编组能融合（前置）", mix != null && isMega(mix));
            if(mix != null){
                System.out.println("[MFP] 能飞+有腿巨兽: flying=" + mix.type.flying + " allowLegStep=" + mix.type.allowLegStep
                    + " flowfieldPathType=" + mix.type.flowfieldPathType
                    + "（costNone=" + Pathfinder.costNone + " costLegs=" + Pathfinder.costLegs + "）");
                check("能飞 + 有腿的巨兽用 costNone 流场（修前是 costLegs → 悬在岩壁上一步不走）",
                    mix.type.flowfieldPathType == Pathfinder.costNone);
                mix.kill();
                run(2);
            }
            Seq<Unit> b = new Seq<>();
            b.add(spawn(spiroct, Team.crux, 600f, 700f));
            b.add(spawn(spiroct, Team.crux, 620f, 700f));
            Unit legs = merge(b);
            check("纯腿编组能融合（前置）", legs != null && isMega(legs));
            if(legs != null){
                System.out.println("[MFP] 纯腿巨兽: flying=" + legs.type.flying + " flowfieldPathType=" + legs.type.flowfieldPathType);
                check("纯腿巨兽仍然用 costLegs 流场（腿的能力没被一起改掉）",
                    legs.type.flowfieldPathType == Pathfinder.costLegs);
                legs.kill();
                run(2);
            }
            Seq<Unit> c = new Seq<>();
            c.add(spawn(dagger, Team.crux, 600f, 800f));
            c.add(spawn(dagger, Team.crux, 620f, 800f));
            Unit ground = merge(c);
            check("纯陆地编组能融合（前置）", ground != null && isMega(ground));
            if(ground != null){
                System.out.println("[MFP] 纯陆地巨兽: flying=" + ground.type.flying + " flowfieldPathType=" + ground.type.flowfieldPathType);
                check("纯陆地巨兽仍然用 costGround 流场",
                    ground.type.flowfieldPathType == Pathfinder.costGround);
                ground.kill();
                run(2);
            }
        }

        // ---------- ② 复现现场：能飞 + 有腿的巨兽悬在天然岩壁上 ----------
        Tile wall = findLegSolidTile(900f);
        check("地图上找得到一块天然岩壁（前置）", wall != null);
        if(wall == null){ System.out.println("[MFP] RESULT FAILED (地图上没有天然岩壁)"); System.exit(1); }
        int packed = Vars.pathfinder.get(wall.x, wall.y);
        int legsCost = Pathfinder.costTypes.get(Pathfinder.costLegs).getCost(Team.crux.id, packed);
        int noneCost = Pathfinder.costTypes.get(Pathfinder.costNone).getCost(Team.crux.id, packed);
        System.out.println("[MFP] 天然岩壁 (" + wall.x + "," + wall.y + ") " + wall.block().name
            + " legSolid=" + wall.legSolid() + ": costLegs=" + legsCost + " costNone=" + noneCost);
        check("口径钉子：腿类代价表把天然岩壁判不可达（用错表的后果）", legsCost == -1);
        check("口径钉子：飞行流场代价表不把天然岩壁当不可达", noneCost != -1);

        Unit mega = null;
        {
            Seq<Unit> us = new Seq<>();
            us.add(spawn(horizon, Team.crux, wall.worldx(), wall.worldy()));
            us.add(spawn(spiroct, Team.crux, wall.worldx(), wall.worldy()));
            mega = merge(us);
        }
        check("能飞 + 有腿的巨兽能融合（前置）", mega != null && isMega(mega));
        if(mega == null){ System.out.println("[MFP] RESULT FAILED (没融出巨兽)"); System.exit(1); }

        mega.set(wall.worldx(), wall.worldy());
        run(2);
        System.out.println("[MFP] 巨兽: " + wallInfo(mega) + " 离核心 " + (int)Mathf.dst(mega.x, mega.y, core.x, core.y) + "px");
        check("巨兽确实悬在岩壁上（复现用户现场：能飞所以能悬在不可通行的格子上）",
            mega.tileOn() != null && mega.tileOn().legSolid());

        // ---- ②a 旧行为：原版 GroundAI + 流场 costLegs（= 修复前）→ 一步都不走 ----
        int oldType = mega.type.flowfieldPathType;
        mega.type.flowfieldPathType = Pathfinder.costLegs;
        mega.controller(new mindustry.ai.types.GroundAI());
        float x0 = mega.x, y0 = mega.y;
        float d0 = Mathf.dst(x0, y0, core.x, core.y);
        runReal(900, 2);
        float movedOld = Mathf.dst(x0, y0, mega.x, mega.y);
        float dOld = Mathf.dst(mega.x, mega.y, core.x, core.y);
        System.out.println("[MFP] 旧行为（原版 GroundAI + costLegs 流场）: 900 tick 位移=" + (int)movedOld
            + "px 距核心 " + (int)d0 + " → " + (int)dOld + " 速度=" + String.format("%.2f", mega.vel().len()));
        check("复现用户报的现象：悬在岩壁上、用腿类流场的巨兽一步不走", movedOld < 8f && d0 - dOld < 32f);

        // ---- ②b 修复后：流场 = costNone + 巨兽自己的 AI（MegaGroundAI）→ 朝核心飞 ----
        mega.type.flowfieldPathType = oldType;
        mega.controller(mega.type.createController(mega));
        run(2);
        float x1 = mega.x, y1 = mega.y;
        float dd0 = Mathf.dst(x1, y1, core.x, core.y);
        float best = dd0;
        for(int i = 0; i < 900; i++){
            runReal(1, 2);
            if(!mega.isAdded() || mega.dead()) break;
            best = Math.min(best, Mathf.dst(mega.x, mega.y, core.x, core.y));
        }
        float dd1 = Mathf.dst(mega.x, mega.y, core.x, core.y);
        System.out.println("[MFP] 修复后（costNone 流场 + " + mega.controller().getClass().getSimpleName() + "）: 距核心 " + (int)dd0 + " → " + (int)best
            + "（当前 " + (int)dd1 + "）活着=" + (mega.isAdded() && !mega.dead()));
        check("修复后：巨兽明显朝玩家核心进攻（" + (int)dd0 + " → " + (int)best + "）", dd0 - best > 100f);

        // ---------- ③ 顺带钉住：指挥模式（CommandAI + ControlPathfinder）那条路没被改坏 ----------
        {
            Unit flareU = spawn(flare, Team.crux, wall.worldx(), wall.worldy());
            flareU.set(wall.worldx(), wall.worldy());
            run(2);
            float flareMoved = commandDiag(flareU, core.x, core.y, "原版飞行单位 flare（悬在岩壁上）");
            check("对照：原版飞行单位悬在岩壁上也能被指挥走（" + (int)flareMoved + "px）", flareMoved > 200f);
            flareU.kill();
            run(2);
            mega.set(wall.worldx(), wall.worldy());
            mega.vel().setZero();
            run(2);
            float megaMoved = commandDiag(mega, core.x, core.y, "巨兽（悬在岩壁上）");
            check("指挥模式下悬在岩壁上的巨兽也能被指挥走（pathCost 那条路没被一起改坏，" + (int)megaMoved + "px）",
                megaMoved > 200f);
        }

        System.out.println("[MFP] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    /** 指挥模式（CommandAI + ControlPathfinder）下把单位指到 (tx,ty)，返回这 600 tick 走了多少像素。 */
    static float commandDiag(Unit u, float tx, float ty, String tag){
        try{
            u.controller(new mindustry.ai.types.CommandAI());
            u.controller().unit(u);
            u.command().command(mindustry.ai.UnitCommand.moveCommand);
            u.command().commandPosition(new arc.math.geom.Vec2(tx, ty));
        }catch(Throwable t){ System.out.println("[MFP] " + tag + " 下指令失败: " + t); return -1f; }
        float x0 = u.x, y0 = u.y;
        runReal(600, 2);
        float moved = Mathf.dst(x0, y0, u.x, u.y);
        System.out.println("[MFP] " + tag + ": pathCostId=" + u.type.pathCostId
            + " 行进=" + (int)Mathf.dst(x0, y0, u.x, u.y) + "px 速度=" + String.format("%.2f", u.vel().len()));
        return moved;
    }

    static String wallInfo(Unit u){
        Tile t = u.tileOn();
        return "flowfieldPathType=" + u.type.flowfieldPathType + " flying=" + u.isFlying()
            + " allowLegStep=" + u.type.allowLegStep + " 脚下格=" + (t == null ? "null" : t.x + "," + t.y + "/" + t.block().name);
    }
}
