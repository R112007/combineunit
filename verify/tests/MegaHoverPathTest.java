package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.math.Mathf; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.entities.*; import mindustry.entities.units.UnitController;
import mindustry.game.*; import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.environment.Floor;

/**
 * 用户报："**ElevationMoveUnit 组合后的 ai 有问题：在指挥模式下不能操控它移动到液体上，
 * 得手动控制才行**。我怀疑是 ElevationMoveUnit 的 ai 和其他单位不同。"
 *
 * <p>用户的怀疑是对的：原版悬浮单位（`ElevationMovec` / `type.hovering`，例如 elude）
 * 的"AI 待遇"确实和其它地面单位不同 —— `UnitType.initPathType()` 给它们发的**寻路代价表是
 * `costHover`**（液体照走、只挡实心方块），而普通地面单位发的是 `costGround`
 * （`PathTile.allDeep(tile) ? impassable`：**深水直接不可通行**）。
 *
 * <p>巨兽派生类型的 pathCost 以前只判"有没有陆地成员/海军成员/飞行成员"，悬浮成员被当成陆地的
 * 一种 → 发 `costGround`。于是两只 elude 合体之后：本体能待在深水上（`canDrown=false`、
 * 手动 WASD 随便开），但**指挥模式**里 `CommandAI` 调
 * `ControlPathfinder.getPathPosition(unit, 目标深水格)` 拿到 costGround 的表 → 目标格 impassable
 * → `move=false`，单位一步都不走（"得手动控制才行"就是这么来的）。
 *
 * <p>本测试验四件事：
 * <ol>
 *   <li>口径钉子：costGround 对"四面都是深水"的格子返回 impassable（-1），costHover 不返回 -1 ——
 *       说明"用错代价表"确实会让人下水这一动作变成不可达；</li>
 *   <li>原版 elude 自己的 pathCost 就是 hover 代价（对照，证明这是原版对悬浮单位的口径）；</li>
 *   <li>两只 elude 的巨兽：`type.hovering=true`、`pathCost`/`pathCostId`/`flowfieldPathType` 全是 hover；
 *       **行为**上在指挥模式里下移动指令（走原版 `CommandAI` + `ControlPathfinder`）能真的开进深水；</li>
 *   <li>对照组：两只 dagger 的巨兽走 ground 代价，同样指令下**进不了**深水（守住"纯陆地编组照旧不下水"）。</li>
 * </ol>
 */
public class MegaHoverPathTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaHoverPathTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MHP] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
    }
    static class DummyController implements UnitController{
        Unit u;
        @Override public void unit(Unit u){ this.u = u; }
        @Override public Unit unit(){ return u; }
    }
    static Unit spawn(UnitType t, float x, float y){
        Unit u = t.create(Team.sharded);
        u.controller(new DummyController());
        u.set(x, y);
        u.add();
        return u;
    }
    static Unit mergeAt(float x, float y, UnitType... types){
        try{
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < types.length; i++) us.add(spawn(types[i], x + i * 14f, y));
            run(2);
            Object mega = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("mergeSelected", Seq.class).invoke(null, us);
            run(2);
            if(mega instanceof Unit mu){ mu.controller(new DummyController()); mu.set(x, y); return mu; }
        }catch(Throwable t){ System.out.println("[MHP] 融合失败: " + t); }
        return null;
    }
    /** 命令单位往 (tx,ty) 走（原版指挥模式的控制器 CommandAI + CommandAI 的移动指令）。 */
    static void orderMove(Unit u, float tx, float ty){
        try{
            u.controller(new mindustry.ai.types.CommandAI());
            u.controller().unit(u);
            u.command().command(mindustry.ai.UnitCommand.moveCommand);
            u.command().commandPosition(new arc.math.geom.Vec2(tx, ty));
        }catch(Throwable t){ System.out.println("[MHP] 下移动指令失败: " + t); }
    }
    /** 单位脚下这一格是不是"深水"（drownTime > 0 的液体地形）。 */
    static boolean onDeep(Unit u){
        Tile t = u.tileOn();
        return t != null && t.floor() != null && t.floor().isDeep();
    }
    /** 某张代价表对指定格打的代价（-1 = impassable）。 */
    static int costOf(mindustry.ai.Pathfinder.PathCost cost, int x, int y){
        return cost.getCost(Team.sharded.id, Vars.pathfinder.get(x, y));
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

        UnitType hover = null;
        for(UnitType t : Vars.content.units()) if(t.name.equals("elude")) hover = t;
        if(hover == null){ System.out.println("[MHP] 找不到 elude"); System.exit(3); }
        System.out.println("[MHP] elude: 实体=" + hover.constructor.get().getClass().getSimpleName()
            + " hovering=" + hover.hovering + " canDrown=" + hover.canDrown
            + " pathCostId=" + hover.pathCostId + "（ControlPathfinder.costIdHover="
            + mindustry.ai.ControlPathfinder.costIdHover + "）"
            + " flowfieldPathType=" + hover.flowfieldPathType
            + "（Pathfinder.costHover=" + mindustry.ai.Pathfinder.costHover + "）");
        check("原版 elude 的 pathCostId 就是 hover 代价（前置：原版悬浮单位的 AI 口径）",
            hover.pathCostId == mindustry.ai.ControlPathfinder.costIdHover);
        check("原版 elude 的流场代价类型是 costHover", hover.flowfieldPathType == mindustry.ai.Pathfinder.costHover);

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
        for(int y=50;y<140;y++) for(int x=40;x<200;x++){
            boolean ok = true;
            for(int dy=-6;dy<=6 && ok;dy++) for(int dx=-6;dx<=12;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ System.out.println("[MHP] 没找到足够大的陆地"); System.exit(4); }
        place(Blocks.coreShard, ox + 24, oy + 16, Team.sharded);
        run(10);

        // ---------- 造一个 6×6 的深水湖（湖心格四面都是深水 → allDeep） ----------
        Floor deep = Blocks.deepwater.asFloor();
        int lx = ox + 4, ly = oy - 3;
        for(int dy=0; dy<6; dy++) for(int dx=0; dx<6; dx++) Vars.world.tile(lx + dx, ly + dy).setFloor(deep);
        // 【必须把整片湖连同外圈重打包一次】Pathfinder.updateTile 只重算"这一格"的
        // allDeep/nearDeep（T.setFloor → updateTile(自己)），而 allDeep 要看四正四斜八个邻居 ——
        // 逐格铺地板时，先铺的格子是在"右/下邻居还不是深水"的状态下打包的，allDeep 停在 false
        // （实测湖心格 costGround=6009 而不是 impassable）。这里在所有地板都铺好之后整片重打包。
        for(int dy=-1; dy<=6; dy++) for(int dx=-1; dx<=6; dx++){
            Tile t = Vars.world.tile(lx + dx, ly + dy);
            if(t != null) Vars.pathfinder.updateTile(t);
        }
        run(20);
        Tile deepTile = Vars.world.tile(lx + 3, ly + 3);
        System.out.println("[MHP] 深水湖: 格(" + lx + ".." + (lx+5) + ", " + ly + ".." + (ly+5) + ")"
            + " 湖心 floor=" + deepTile.floor().name + " isLiquid=" + deepTile.floor().isLiquid
            + " isDeep=" + deepTile.floor().isDeep());
        check("深水湖就位（前置：湖心格是深水）",
            deepTile.floor().isLiquid && deepTile.floor().isDeep());

        // CommandAI 走的是 ControlPathfinder（按 type.pathCostId 取表），这里直接用它的两张表量。
        int gCost = costOf(mindustry.ai.ControlPathfinder.costTypes.get(mindustry.ai.ControlPathfinder.costIdGround),
            deepTile.x, deepTile.y);
        int hCost = costOf(mindustry.ai.ControlPathfinder.costTypes.get(mindustry.ai.ControlPathfinder.costIdHover),
            deepTile.x, deepTile.y);
        System.out.println("[MHP] 湖心格代价（CommandAI 用的 ControlPathfinder 表）: costGround=" + gCost
            + "（-1=impassable） costHover=" + hCost);
        check("口径钉子：地面代价把这种深水格当不可通行（用错表 = 指挥不动它下水）", gCost == -1);
        check("口径钉子：悬浮代价认为深水可通行（原版悬浮单位的待遇）", hCost != -1);

        float startX = (lx - 3) * 8f + 4f, startY = (ly + 3) * 8f + 4f;
        float destX = deepTile.worldx(), destY = deepTile.worldy();

        // ---------- ① 悬浮巨兽（2×elude）：代价表 = hover，指挥模式下能开进深水 ----------
        {
            Unit beast = mergeAt(startX, startY, hover, hover);
            if(beast == null){ check("两只 elude 能融合（前置）", false); }
            else{
                check("两只 elude 能融合（前置）", true);
                UnitType t = beast.type;
                System.out.println("[MHP] 悬浮巨兽: hovering=" + t.hovering + " canDrown=" + t.canDrown
                    + " pathCostId=" + t.pathCostId + " flowfieldPathType=" + t.flowfieldPathType
                    + " hitSize=" + String.format("%.1f", beast.hitSize()));
                check("悬浮巨兽的派生类型仍是 hovering", t.hovering);
                check("悬浮巨兽不溺水（canDrown=false）", !t.canDrown);
                check("悬浮巨兽的 pathCostId = costIdHover（修前这里是 costIdGround=0）",
                    t.pathCostId == mindustry.ai.ControlPathfinder.costIdHover);
                check("悬浮巨兽的 pathCost 就是 hover 那套代价表（isPathImpassable/UnitGroup 读它）",
                    t.pathCost == mindustry.ai.Pathfinder.costTypes.get(mindustry.ai.Pathfinder.costHover));
                check("悬浮巨兽的流场代价类型 = costHover",
                    t.flowfieldPathType == mindustry.ai.Pathfinder.costHover);
                int beastCost = costOf(t.pathCost, deepTile.x, deepTile.y);
                System.out.println("[MHP] 悬浮巨兽自己的 pathCost 对湖心格 = " + beastCost);
                check("悬浮巨兽的代价表不把深水当不可通行", beastCost != -1);

                orderMove(beast, destX, destY);
                int enterTick = -1, arriveTick = -1;
                for(int i = 0; i < 1500; i++){
                    run(1);
                    if(enterTick < 0 && onDeep(beast)) enterTick = i;
                    if(Mathf.dst(beast.x, beast.y, destX, destY) < 12f){ arriveTick = i; break; }
                }
                Tile on = beast.tileOn();
                System.out.println("[MHP] 指挥模式下令其进湖: " + (enterTick >= 0
                    ? ("第 " + enterTick + " tick 进入深水格")
                    : "1500 tick 都没进深水")
                    + "；" + (arriveTick >= 0 ? ("第 " + arriveTick + " tick 抵达湖心") : "1500 tick 没到湖心")
                    + " 现在位置=(" + (int)beast.x + "," + (int)beast.y + ") 脚下格=(" + on.x + "," + on.y + ")"
                    + " 脚下是深水=" + onDeep(beast) + " 距湖心="
                    + String.format("%.0f", Mathf.dst(beast.x, beast.y, destX, destY)) + "px");
                check("指挥模式下悬浮巨兽能移动到液体上（用户报的那条）", enterTick >= 0);
                check("悬浮巨兽最终开到湖心（真的抵达目标，不是刚沾水就停）", arriveTick >= 0);
                check("悬浮巨兽停在湖心附近且脚下仍是深水",
                    onDeep(beast) &&
                    Mathf.dst(beast.x, beast.y, destX, destY) < 24f);
                beast.kill();
                run(5);
            }
        }

        // ---------- ② 对照组：纯地面巨兽（2×dagger）照旧下水不可达 ----------
        {
            Unit beast = mergeAt(startX, startY - 80f, UnitTypes.dagger, UnitTypes.dagger);
            if(beast == null){ check("两只 dagger 能融合（前置）", false); }
            else{
                UnitType t = beast.type;
                System.out.println("[MHP] 地面巨兽（对照）: canDrown=" + t.canDrown
                    + " pathCostId=" + t.pathCostId + " flowfieldPathType=" + t.flowfieldPathType);
                check("地面巨兽的 pathCostId = costIdGround（没被一起改成 hover）",
                    t.pathCostId == mindustry.ai.ControlPathfinder.costIdGround);
                check("地面巨兽的代价表仍把深水当不可通行",
                    costOf(t.pathCost, deepTile.x, deepTile.y) == -1);

                orderMove(beast, destX, destY);
                boolean entered = false;
                for(int i = 0; i < 1200; i++){
                    run(1);
                    if(onDeep(beast)){ entered = true; break; }
                }
                System.out.println("[MHP] 指挥模式下令地面巨兽进湖: 进了深水=" + entered
                    + " 现在位置=(" + (int)beast.x + "," + (int)beast.y + ") 距湖心="
                    + String.format("%.0f", Mathf.dst(beast.x, beast.y, destX, destY)) + "px");
                check("指挥模式下地面巨兽进不了深水（对照：能力没被无差别放开）", !entered);
                beast.kill();
                run(5);
            }
        }

        System.out.println("[MHP] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
