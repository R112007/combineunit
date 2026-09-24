package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.entities.units.UnitController;
import mindustry.game.*; import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户设计稿（{@code ~/sd/组合单位.txt}）里的口径：
 * <pre>
 *   超级组合：合成一个大单位，保留所有能力、武器、血量、护盾、护甲叠加……
 *   **如果飞行单位的 hitsize 总和大于地面单位的话就可以飞。**
 * </pre>
 *
 * <p>以前实现的是"有飞行成员就能飞"：一架小飞机搭两台坦克也会整体固定飞天 ——
 * 用户报的"组了空军后会固定飞天，整个巨兽直接瘫痪"就是这么来的。
 * 现在按设计稿算：{@code Σ(飞行成员 hitSize) > Σ(地面成员 hitSize)} 才能飞；
 * 派生类型的 {@code flying}、引擎、寻路代价、{@code moveMode()}、每帧 elevation 驱动全部用同一个判定。
 *
 * <p>本测试把四种构成都跑一遍（命中数/能不能飞/是不是真的落地/能不能开火）。
 */
public class MegaFlightRuleTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaFlightRuleTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MFR] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
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
            if(mega instanceof Unit mu){
                mu.controller(new DummyController());
                mu.set(x, y);
                return mu;
            }
        }catch(Throwable t){ System.out.println("[MFR] 融合失败: " + t); }
        return null;
    }
    static Object field(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                }catch(Throwable ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){ }
        return null;
    }
    static int moveMode(Unit u){ Object v = null; try{ v = u.getClass().getMethod("moveMode").invoke(u); }catch(Throwable ignored){} return v instanceof Integer i ? i : -1; }
    static boolean canFly(Unit u){ Object v = null; try{ v = u.getClass().getMethod("canFly").invoke(u); }catch(Throwable ignored){} return Boolean.TRUE.equals(v); }
    static long shots(Unit u){ long n = 0; for(var m : u.mounts()) n += m.totalShots; return n; }
    static void fireTick(Unit u){
        u.aim(u.x + arc.math.Angles.trnsx(u.rotation(), 200f), u.y + arc.math.Angles.trnsy(u.rotation(), 200f), true);
        u.controlWeapons(true, true);
    }

    /** 一种构成：融合 → 看命中数/能不能飞/落地开车 → 能不能开火。 */
    static void scenario(String tag, float x, float y, boolean expectFly, UnitType... types){
        float air = 0f, ground = 0f;
        for(UnitType t : types) if(t.flying) air += t.hitSize; else ground += t.hitSize;
        Unit mega = mergeAt(x, y, types);
        if(mega == null){ check(tag + "：能融合（前置）", false); return; }
        run(30);   // 让它按自己的规则升空/落地
        boolean flying = mega.isFlying();
        System.out.println("[MFR] " + tag + ": 飞行hitSize和=" + air + " 地面hitSize和=" + ground
            + " → canFly=" + canFly(mega) + " type.flying=" + mega.type.flying
            + " isFlying=" + flying + " elevation=" + String.format("%.2f", mega.elevation())
            + " moveMode=" + moveMode(mega) + "（MODE_FLY=2）"
            + " 引擎=" + (mega.type.engines == null ? -1 : mega.type.engines.size));
        check(tag + "：按设计稿判定能不能飞（期望 " + expectFly + "，实际 canFly=" + canFly(mega) + "）",
            canFly(mega) == expectFly);
        check(tag + "：type.flying 与判定一致（" + mega.type.flying + "）", mega.type.flying == expectFly);
        check(tag + "：真的（没）升空（isFlying=" + flying + "）", flying == expectFly);
        check(tag + "：moveMode " + (expectFly ? "= MODE_FLY" : "≠ MODE_FLY") + "（实际 " + moveMode(mega) + "）",
            expectFly ? moveMode(mega) == 2 : moveMode(mega) != 2);
        long s0 = shots(mega);
        for(int i = 0; i < 120; i++){ fireTick(mega); run(1); }
        long fired = shots(mega) - s0;
        // 能不能"打出子弹"还跟武器转速/固定炮口朝向有关（flare 的炮是固定朝后的），
        // 这里只钉"引擎级开火许可"——正是 canBoost 那条规则会掐掉的东西（打出子弹的专项测试见
        // MegaBoostTest / MegaPlayerFireTest / MegaTankTest）。
        System.out.println("[MFR] " + tag + "：120 tick 模拟开火 = " + fired + " 发（canShoot=" + mega.canShoot() + "）");
        check(tag + "：引擎级开火许可 canShoot=true（" + mega.canShoot() + "）", mega.canShoot());
        mega.kill();
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

        UnitType ground = UnitTypes.dagger;      // 地面（机甲）
        UnitType air = UnitTypes.flare;          // 飞行
        System.out.println("[MFR] 成员 hitSize: " + ground.name + "=" + ground.hitSize
            + "、" + air.name + "=" + air.hitSize + "（flying=" + air.flying + "）");

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.waves = false;
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.disableUnitCap = true;
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(10);
        int ox=-1, oy=-1;
        outer:
        for(int y=45;y<120;y++) for(int x=40;x<200;x++){
            boolean ok = true;
            for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-4;dx<=4;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ System.out.println("[MFR] 没找到陆地"); System.exit(4); }
        place(Blocks.coreShard, ox + 30, oy + 16, Team.sharded);
        run(10);

        float bx = ox * 8f + 60f, by = oy * 8f;
        // ① 纯地面 → 不飞
        scenario("2×" + ground.name + "（纯地面）", bx, by, false, ground, ground);
        // ② 地面为主的混编（1 架小飞机 + 2 台坦克）→ 按设计稿不飞（旧口径会被拖上天）
        scenario("2×" + ground.name + " + 1×" + air.name + "（地面为主）", bx + 200f, by, false, ground, ground, air);
        // ③ 空军为主的混编 → 飞
        scenario("2×" + air.name + " + 1×" + ground.name + "（空军为主）", bx + 400f, by, true, air, air, ground);
        // ④ 纯空军 → 飞
        scenario("2×" + air.name + "（纯空军）", bx + 600f, by, true, air, air);

        System.out.println("[MFR] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
