package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts;
import mindustry.world.*;

/**
 * 用户报：**"ElevationMoveUnit 的实体（elude）合体后淹死了"**。
 *
 * <p>根因：原版溺水判定是按**实体组件**分派的，不是按类型 ——
 * {@code ElevationMoveUnit}（悬浮单位，如 elude）自己重写了
 * {@code canDrown() = isGrounded() && type.canDrown}，而 {@code isGrounded() = elevation <= 0.001}：
 * 悬浮起来（elevation ≥ 0.09 就是 {@code isFlying()}）的单位根本不进溺水那条分支。
 * 组合巨兽的实体是普通的 {@code MegaUnitEntity}（继承最普通的 {@code UnitEntity}），
 * 按成员构成推导出来的巨兽类型又只认"飞行/海军/陆地"三种形态 ——
 * 悬浮成员（elude）既不是 flying、也不是 WaterMove，被当成**陆地**处理：
 * 类型 {@code canDrown} 默认 true、实体没有 elevation → 一进深水就开始溺水，
 * 而它自己的成员在深水上好端端地悬着（用户看到的"合体后淹死"）。
 *
 * <p>本测试要四件事：
 * <ol>
 *   <li>A 前置：单独一只 elude 在深水上 2 秒不死（说明"悬浮成员本来不淹"）；</li>
 *   <li>B 回归：两只 elude 合体后放深水上，2 秒不死、不掉血、{@code drownTime} 保持 0；</li>
 *   <li>C 对照：纯陆地编组（dagger ×2）合体放同样深水上**照旧会淹** ——
 *       修复只针对"有悬浮成员的编组"，不许把所有巨兽一刀切成免淹；</li>
 *   <li>D 混合编组（elude + dagger）：悬浮能力来自 elude，照样不淹死。</li>
 * </ol>
 */
public class MegaWaterTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaWaterTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MW] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Class<?> mergeCls(){
        try{ return Class.forName("combineunit.units.UnitComboMerge", true, ml); }catch(Throwable t){ return null; }
    }
    static Unit mergeAt(float x, float y, UnitType... types){
        try{
            Seq<Unit> units = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = types[i].create(Team.sharded);
                u.set(x + i * 14f, y);
                u.add();
                units.add(u);
            }
            run(2);
            Object mega = mergeCls().getMethod("mergeSelected", Seq.class).invoke(null, units);
            run(2);
            if(mega instanceof Unit u) u.set(x, y);
            return mega instanceof Unit u ? u : null;
        }catch(Throwable t){ System.out.println("[MW] 融合失败: " + t); return null; }
    }
    static Building place(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
    }
    static boolean openWater(Tile mid, int radius, boolean deep){
        for(int dy = -radius; dy <= radius; dy++) for(int dx = -radius; dx <= radius; dx++){
            Tile t = Vars.world.tile(mid.x + dx, mid.y + dy);
            if(t == null || t.floor() == null || !t.floor().isLiquid || t.block() != Blocks.air) return false;
            if(deep ? !t.floor().isDeep() : !t.floor().shallow) return false;
        }
        return true;
    }
    static String facts(Unit u){
        UnitType t = u.type;
        return "类=" + u.getClass().getSimpleName()
            + " 类型=" + t.name + "(flying=" + t.flying + " canDrown=" + t.canDrown
            + " canBoost=" + t.canBoost + " riseSpeed=" + t.riseSpeed + " descentSpeed=" + t.descentSpeed + ")"
            + " elevation=" + u.elevation + " isFlying=" + u.isFlying() + " isGrounded=" + u.isGrounded()
            + " canDrown=" + u.canDrown() + " onSolid=" + u.onSolid()
            + " 地形=" + (Vars.world.tileWorld(u.x, u.y) == null ? "?" : Vars.world.tileWorld(u.x, u.y).floor().name)
            + " drownTime=" + u.drownTime + " 血=" + (int)u.health + "/" + (int)u.maxHealth() + " 死亡=" + u.dead;
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
        if(mergeCls() == null){ System.out.println("[MW] 找不到 UnitComboMerge"); System.exit(3); }

        UnitType hover = null;
        for(UnitType t : Vars.content.units()) if(t.name.equals("elude")) hover = t;
        if(hover == null){ System.out.println("[MW] 没有 elude（数据目录里没有官方单位？）"); System.exit(3); }
        System.out.println("[MW] elude 实体类=" + hover.constructor.get().getClass().getName()
            + " 是 ElevationMovec=" + (hover.constructor.get() instanceof mindustry.gen.ElevationMovec));

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.waves = false;
        Vars.state.rules.fog = false;
        Vars.state.rules.staticFog = false;
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(10);

        // 找一大片深水（半径 8 格都是深水、无建筑）+ 附近陆地放核心
        Tile deep = null, land = null;
        outer:
        for(int y=45;y<150;y++){
            for(int x=40;x<220;x++){
                Tile t = Vars.world.tile(x, y);
                if(t == null || t.floor() == null || !t.floor().isDeep() || t.floor().drownTime <= 0f) continue;
                if(!openWater(t, 8, true)) continue;
                for(int r = 3; r <= 14 && land == null; r++){
                    for(int dy = -r; dy <= r && land == null; dy++) for(int dx = -r; dx <= r; dx++){
                        Tile lt = Vars.world.tile(x + dx, y + dy);
                        if(lt != null && lt.floor() != null && !lt.floor().isLiquid && lt.block() == Blocks.air){ land = lt; break; }
                    }
                }
                deep = t; break outer;
            }
        }
        if(deep == null || land == null){ System.out.println("[MW] 没找到深水/陆地"); System.exit(3); }
        Building core = place(Blocks.coreShard, land.x, land.y, Team.sharded);
        if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
        run(10);
        float px = deep.worldx(), py = deep.worldy();
        System.out.println("[MW] 深水格子=" + deep.x + "," + deep.y + " 地形=" + deep.floor().name
            + " drownTime(floor)=" + deep.floor().drownTime + " 屏幕坐标=" + (int)px + "," + (int)py);

        // ---------- A 前置：单独一只 elude 在深水上不淹 ----------
        Unit solo = hover.create(Team.sharded);
        solo.set(px, py);
        solo.add();
        run(10);
        System.out.println("[MW] A 单独 elude（刚放上深水）: " + facts(solo));
        float soloHp = solo.health();
        run(120);
        System.out.println("[MW] A 单独 elude（2 秒后）: " + facts(solo));
        check("A 单只 elude 在深水上 2 秒不死（悬浮成员本来就不淹）", !solo.dead && solo.health() >= soloHp - 0.01f);
        solo.remove();
        run(5);

        // ---------- B 回归：两只 elude 合体后放深水 ----------
        Unit beast = mergeAt(px, py, hover, hover);
        if(beast == null) check("B 两只 elude 能融合成巨兽（前置）", false);
        else{
            System.out.println("[MW] B 悬浮编组巨兽（刚放下）: " + facts(beast));
            float hp = beast.health();
            // 原版 deep-water 的 drownTime=200 → drownTime 累到 0.999 才掉血，约 6 秒；
            // 这里跑满 8 秒，既看"有没有进溺水分支"（drownTime 必须一直是 0），也看"真不死"。
            run(480);
            System.out.println("[MW] B 悬浮编组巨兽（8 秒后）: " + facts(beast));
            check("B 两只 elude 能融合成巨兽（前置）", true);
            check("B 悬浮编组巨兽在深水上 8 秒不死（用户报的「合体后淹死」）", !beast.dead && beast.isValid());
            check("B 悬浮编组巨兽 8 秒不掉血（溺水约 6 秒就该掉血）", beast.health() >= hp - 0.01f);
            check("B 悬浮编组巨兽 drownTime 保持 0（没有进溺水分支）", beast.drownTime <= 0.0001f);
            beast.remove();
            run(5);
        }

        // ---------- C 对照：纯陆地编组照旧会淹（不许一刀切成免淹） ----------
        Unit land2 = mergeAt(px, py, UnitTypes.dagger, UnitTypes.dagger);
        if(land2 == null) System.out.println("[MW] C 纯陆地编组没融出来（跳过对照）");
        else{
            float hp = land2.health();
            System.out.println("[MW] C 纯陆地编组巨兽（刚放下）: " + facts(land2));
            run(120);
            System.out.println("[MW] C 纯陆地编组巨兽（2 秒后）: " + facts(land2));
            check("C 纯陆地编组照旧会淹（修复没把所有巨兽一刀切成免淹）",
                land2.dead || land2.health() < hp - 0.01f || land2.drownTime > 0.0001f);
            land2.remove();
            run(5);
        }

        // ---------- D 混合编组：elude + dagger ----------
        Unit mixed = mergeAt(px, py, hover, UnitTypes.dagger);
        if(mixed == null) check("D elude + dagger 能融合成巨兽（前置）", false);
        else{
            float hp = mixed.health();
            System.out.println("[MW] D 混合编组巨兽（刚放下）: " + facts(mixed));
            run(120);
            System.out.println("[MW] D 混合编组巨兽（2 秒后）: " + facts(mixed));
            check("D 混合编组（elude + dagger）在深水上 2 秒不死", !mixed.dead && mixed.isValid());
            check("D 混合编组 2 秒不掉血", mixed.health() >= hp - 0.01f);
            mixed.remove();
        }

        System.out.println("[MW] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
