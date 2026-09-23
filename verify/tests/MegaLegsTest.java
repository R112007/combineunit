package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.entities.*;
import mindustry.game.*; import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："把 LegsUnit 的腿部绘制和**翻墙能力**删了"。
 *
 * <p>查证结论：腿部绘制一直在（`MegaUnitType.drawAttachments` → `drawLegsOf` → 原版 `drawLegs`，
 * 真客户端截图 `042_legs_mega.png` 里腿是画出来的）；**翻墙（allowLegStep）确实从来没接上** ——
 * 原版腿类单位是 `UnitType.allowLegStep=true` + `LegsUnit.solidity()` 用
 * `EntityCollisions::legsSolid`（只挡石头/实心地板，玩家放的建筑都能踩过去），
 * 而巨兽派生类型从没设过 allowLegStep、实体的 solidity 也一律用 `::solid` → 有腿成员的巨兽撞墙就停。
 *
 * <p>本测试验：①腿类巨兽的 `type.allowLegStep=true`、实体 instanceof `Legsc`（腿才画得出来）；
 * ②碰撞谓词：玩家放的墙面前 `legsSolid` 判定"不实心"（能踩上去），石头地板仍然实心；
 * ③行为：给腿类巨兽下移动指令，它能**越过一排墙**走到对面；对照组（机甲巨兽）被墙挡住。
 */
public class MegaLegsTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaLegsTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[ML] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
    }
    static Unit mergeAt(float x, float y, UnitType... types){
        try{
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = types[i].create(Team.sharded);
                u.set(x + i * 12f, y);
                u.add();
                us.add(u);
            }
            run(2);
            Object mega = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("mergeSelected", Seq.class).invoke(null, us);
            run(2);
            if(mega instanceof Unit mu) mu.set(x, y);
            return mega instanceof Unit mu ? mu : null;
        }catch(Throwable t){ System.out.println("[ML] 融合失败: " + t); return null; }
    }
    /** 命令单位往 (tx,ty) 走（用原版 CommandAI 的移动指令）。 */
    static void orderMove(Unit u, float tx, float ty){
        try{
            u.controller(new mindustry.ai.types.CommandAI());
            u.controller().unit(u);
            u.command().command(mindustry.ai.UnitCommand.moveCommand);
            u.command().commandPosition(new arc.math.geom.Vec2(tx, ty));
        }catch(Throwable t){ System.out.println("[ML] 下移动指令失败: " + t); }
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

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.waves = false;
        Vars.state.rules.disableUnitCap = true;
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(10);
        int ox=-1, oy=-1;
        outer:
        for(int y=45;y<150;y++) for(int x=40;x<220;x++){
            boolean ok = true;
            for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-6;dx<=6;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ System.out.println("[ML] 没找到陆地"); System.exit(3); }
        place(Blocks.coreShard, ox + 40, oy + 20, Team.sharded);
        run(10);
        float cx = ox * 8f, cy = oy * 8f;

        int wx = ox + 3, wy = oy;   // 测试用的墙格
        // ---------- ① 腿类巨兽：类型/实体/碰撞谓词 ----------
        Unit spiroctRef = UnitTypes.spiroct.create(Team.sharded);
        spiroctRef.set(cx - 40f, cy);
        spiroctRef.add();
        check("原版 spiroct 自己有翻墙能力（allowLegStep）", UnitTypes.spiroct.allowLegStep);

        Unit legsBeast = mergeAt(cx, cy, UnitTypes.spiroct, UnitTypes.arkyid);
        if(legsBeast == null) check("腿类单位能融合（前置）", false);
        else{
            check("腿类单位能融合（前置）", true);
            check("腿类巨兽 instanceof Legsc（腿部绘制的前提）", legsBeast instanceof mindustry.gen.Legsc);
            // 【用户报的"你就是没有画"】有真·整图的代表类型（arkyid）也必须自己补画腿：
            // 整图里的腿是"图标姿态"，只画整图 = 看起来没腿。
            Object drawOwn = null;
            try{
                Class<?> mt = Class.forName("combineunit.units.mega.MegaUnitType", true, ml);
                java.lang.reflect.Method m = mt.getMethod("drawOwnParts",
                    Class.forName("combineunit.units.mega.MegaUnitEntity", true, ml),
                    UnitType.class, arc.graphics.g2d.TextureRegion.class);
                drawOwn = m.invoke(null, legsBeast, legsBeast.type, null);
            }catch(Throwable t){ System.out.println("[ML] 反射 drawOwnParts 失败: " + t); }
            Object attKind = null;
            try{
                java.lang.reflect.Field f = legsBeast.getClass().getDeclaredField("attKind");
                f.setAccessible(true);
                attKind = f.get(legsBeast);
            }catch(Throwable ignored){}
            System.out.println("[ML] 巨兽 attKind=" + attKind + " drawOwnParts=" + drawOwn
                + "（腿类必须自己画腿）");
            check("腿类巨兽即使有真·整图也自己补画腿（drawOwnParts=true）", Boolean.TRUE.equals(drawOwn));
            check("腿类巨兽的 type.allowLegStep=true（翻墙能力）", legsBeast.type.allowLegStep);
            check("腿类巨兽的腿数=代表类型腿数（6 条长腿）",
                legsBeast instanceof mindustry.gen.Legsc lc && lc.legs().length == UnitTypes.arkyid.legCount);

            // 墙面前：会翻墙的单位应当认为"不实心"（能踩上去）
            place(Blocks.copperWall, wx, wy, Team.sharded);
            place(Blocks.copperWall, wx, wy + 1, Team.sharded);
            run(5);
            EntityCollisions.SolidPred legsPred = legsBeast.solidity();
            EntityCollisions.SolidPred refPred = spiroctRef.solidity();
            boolean legsWallSolid = legsPred == null || legsPred.solid(wx, wy);
            boolean refWallSolid = refPred == null || refPred.solid(wx, wy);
            System.out.println("[ML] 墙面格 legsSolid: 巨兽=" + legsWallSolid + " 原版 spiroct=" + refWallSolid);
            check("腿类巨兽把玩家放的墙当'可踩'（和原版 spiroct 一致）", legsWallSolid == refWallSolid && !refWallSolid);

            // 【逐格对齐原版】巨兽的碰撞谓词必须和原版腿类单位**逐格一致**：
            // 墙格（可踩）、空格（可走）、天然石墙格（实心）三处都比一遍。
            int sx = ox + 3, sy2 = oy + 5;
            Vars.world.tile(sx, sy2).setBlock(Blocks.stoneWall);   // 天然石墙：腿类也过不去
            run(3);
            boolean sameWall = (legsPred == null ? refPred == null : legsPred.solid(wx, wy) == refPred.solid(wx, wy));
            boolean sameEmpty = (legsPred == null ? refPred == null : legsPred.solid(ox + 2, oy + 2) == refPred.solid(ox + 2, oy + 2));
            boolean sameRock = (legsPred == null ? refPred == null : legsPred.solid(sx, sy2) == refPred.solid(sx, sy2));
            System.out.println("[ML] 碰撞谓词对比（巨兽 vs 原版 spiroct）: 墙=" + sameWall
                + " 空地=" + sameEmpty + " 天然石墙=" + sameRock
                + "（天然石墙实心=" + (refPred != null && refPred.solid(sx, sy2)) + "）");
            check("腿类巨兽的碰撞判定与原版腿类单位逐格一致", sameWall && sameEmpty && sameRock);
        }

        // ---------- ② 机甲巨兽（对照组）：没有 allowLegStep，被墙挡住 ----------
        Unit mechBeast = mergeAt(cx - 200f, cy + 60f, UnitTypes.dagger, UnitTypes.fortress);
        if(mechBeast == null) check("机甲单位能融合（前置）", false);
        else{
            check("机甲单位能融合（前置）", true);
            check("机甲巨兽没有 allowLegStep（照旧撞墙）", !mechBeast.type.allowLegStep);
        }

        // ---------- ③ 行为：腿类巨兽越过一排墙 ----------
        float wallX = (ox + 3) * 8f;
        if(legsBeast != null){
            // 把巨兽摆到墙左边，命令它走到墙右边
            legsBeast.set(wallX - 40f, (oy + 0.5f) * 8f);
            run(5);
            float startX = legsBeast.x;
            orderMove(legsBeast, wallX + 60f, legsBeast.y);
            for(int i = 0; i < 420; i++){
                run(1);
                if(legsBeast.x > wallX + 8f) break;
            }
            System.out.println("[ML] 腿类巨兽: 起点 x=" + (int)startX + " → 现在 x=" + (int)legsBeast.x
                + "（墙 x=" + (int)wallX + "）");
            check("腿类巨兽能越过玩家放的墙（x 越过墙线）", legsBeast.x > wallX + 8f);
        }
        if(mechBeast != null){
            // 【对照】机甲巨兽的碰撞谓词必须和原版机甲（dagger）一致：墙格对它是实心的
            // （不跟"翻墙"的单位一起变），所以这里比对谓词而不是跑路（AI 会自己绕路过去）。
            Unit daggerRef = UnitTypes.dagger.create(Team.sharded);
            daggerRef.set(cx - 300f, cy - 300f);
            daggerRef.add();
            EntityCollisions.SolidPred mechPred = mechBeast.solidity();
            EntityCollisions.SolidPred dagPred = daggerRef.solidity();
            boolean mechWallSolid = mechPred != null && mechPred.solid(wx, wy);
            boolean dagWallSolid = dagPred != null && dagPred.solid(wx, wy);
            System.out.println("[ML] 墙格对机甲巨兽实心=" + mechWallSolid + "，原版 dagger=" + dagWallSolid);
            check("机甲巨兽的碰撞判定也和原版机甲一致（没有被一起改成翻墙）",
                mechWallSolid == dagWallSolid && dagWallSolid);
            daggerRef.remove();
        }

        System.out.println("[ML] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
