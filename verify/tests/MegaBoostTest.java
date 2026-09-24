package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.entities.units.UnitController;
import mindustry.game.*; import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报的三条（同一根因）：
 * <ul>
 *     <li>"陆辅有助推，在组合巨兽里会变内鬼。"</li>
 *     <li>"在空中时整个巨兽都不能攻击了。"</li>
 *     <li>"而组了空军后会固定飞天，整个巨兽直接瘫痪。"</li>
 * </ul>
 *
 * <p>根因（原版口径）：
 * <ul>
 *     <li>{@code UnitComp.canShoot() = !disarmed && !(type.canBoost && isFlying())}
 *         —— **带助推的类型只要"离地"就整只不能开火**；</li>
 *     <li>而"离地"的门槛低得离谱：{@code isFlying() = elevation >= 0.09}；</li>
 *     <li>{@code UnitComp.updateBoosting()} 的
 *         {@code shouldBoost = boost || onSolid() || (isFlying() && !canLand())} ——
 *         撞上实心方块、或悬在别的落地单位上方（{@code canLand()=false}）都会自己往上飘；</li>
 *     <li>巨兽派生类型以前把成员的 {@code canBoost} 继承了下来 → ①带助推的成员一进编组，
 *         巨兽就会自己升空、然后整只打不出东西（用户说的"内鬼"）；
 *         ②有飞行成员的编组本来就固定悬空 → 永久 {@code canShoot()=false}（"固定飞天、直接瘫痪"）。</li>
 * </ul>
 *
 * <p>修法：派生类型恒 {@code canBoost=false}，飞不飞完全由巨兽自己的模型决定
 * （有飞行成员才飞，见 {@code MegaUnitEntity.update()}）。
 * 本测试：①对照——原版带助推的单位升空后确实 {@code canShoot()=false}（这条规则有多坑）；
 * ②巨兽派生类型 {@code canBoost=false}；③带助推成员的陆地巨兽：连续按着助推 60 tick 仍落地、能开火；
 * ④有飞行成员的巨兽：一直悬空，但 {@code canShoot()=true} 且真的能开火。
 */
public class MegaBoostTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaBoostTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MB] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
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
        }catch(Throwable t){ System.out.println("[MB] 融合失败: " + t); }
        return null;
    }
    static long shots(Unit u){
        long n = 0;
        for(var m : u.mounts()) n += m.totalShots;
        return n;
    }
    /** 模拟"玩家长按助推 + 按住开火"：原版 updateBoosting(player.boosting) 与 controlWeapons 那一套。 */
    static void playerTick(Unit u, boolean boost){
        u.updateBoosting(boost);
        // 原版输入里玩家是"转身朝准星"（lookAt）再开火；这里把机身钉成朝上、瞄点也放正上方，
        // 免得"武器转动速度"这种无关因素影响（dagger 的炮塔转速只有 ~20°/s，60 tick 转不到 5° 锥角里）
        u.rotation(90f);
        u.aim(u.x, u.y + 200f, true);
        u.controlWeapons(true, true);
    }
    /** 把每把武器的运行时状态打出来（排查"为什么没开火"）。 */
    static String dumpMounts(Unit u){
        StringBuilder sb = new StringBuilder();
        for(var m : u.mounts()){
            sb.append("\n      [").append(m.weapon.name).append("] shoot=").append(m.shoot)
              .append(" rotate=").append(m.rotate).append(" warmup=").append(String.format("%.2f", m.warmup))
              .append(" reload=").append(String.format("%.1f", m.reload))
              .append(" mountRot=").append((int)m.rotation).append(" targetRot=").append((int)m.targetRotation)
              .append(" unitRot=").append((int)u.rotation()).append(" cone=").append((int)m.weapon.shootCone)
              .append(" minWarmup=").append(m.weapon.minWarmup).append(" alternate=").append(m.weapon.alternate)
              .append(" flipSprite=").append(m.weapon.flipSprite).append(" side=").append(m.side)
              .append(" otherSide=").append(m.weapon.otherSide);
        }
        return sb.toString();
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

        // 用户说的"陆辅有助推"：数据集里没有该 mod 单位，就给 dagger 手动加上 canBoost 来模拟。
        UnitTypes.dagger.canBoost = true;
        UnitType air = UnitTypes.flare;      // 空军成员（type.flying = true）
        System.out.println("[MB] 模拟带助推的地面成员: dagger.canBoost=" + UnitTypes.dagger.canBoost
            + "（riseSpeed=" + UnitTypes.dagger.riseSpeed + "）; 空军成员: " + air.name
            + " flying=" + air.flying);

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
        if(ox < 0){ System.out.println("[MB] 没找到陆地"); System.exit(4); }
        place(Blocks.coreShard, ox + 30, oy + 16, Team.sharded);
        run(10);

        // ---------- ① 对照：原版"带助推的单位"升空后确实打不出东西 ----------
        {
            Unit solo = spawn(UnitTypes.dagger, ox * 8f, oy * 8f);
            run(2);
            // 同型单只（不着地那一只的对照组）：证明"我这套模拟输入"对 dagger 本来就能打出子弹
            Unit soloGround = spawn(UnitTypes.dagger, ox * 8f + 120f, oy * 8f);
            run(2);
            long g0 = shots(soloGround);
            for(int i = 0; i < 60; i++){ playerTick(soloGround, false); run(1); }
            long groundFired = shots(soloGround) - g0;
            System.out.println("[MB] 对照（原版单只 dagger 不按助推）: mounts=" + soloGround.mounts().length
                + " elevation=" + String.format("%.2f", soloGround.elevation())
                + " canShoot=" + soloGround.canShoot() + " 开火=" + groundFired
                + dumpMounts(soloGround));
            soloGround.kill();
            for(int i = 0; i < 60; i++){ playerTick(solo, true); run(1); }
            System.out.println("[MB] 对照（原版单只带助推单位，按着助推 60 tick）: elevation="
                + String.format("%.2f", solo.elevation()) + " isFlying=" + solo.isFlying()
                + " canShoot=" + solo.canShoot());
            check("对照：原版带助推单位升空后 canShoot()=false（这就是那条坑）",
                solo.isFlying() && !solo.canShoot());
            solo.kill();
        }

        // ---------- ②③ 带助推成员的陆地巨兽：不继承 canBoost、按着助推也落在地上、能开火 ----------
        {
            Unit mega = mergeAt(ox * 8f + 300f, oy * 8f, UnitTypes.dagger, UnitTypes.dagger);
            if(mega == null){ check("带助推成员的巨兽能融合（前置）", false); }
            else{
                System.out.println("[MB] 陆地巨兽: type.canBoost=" + mega.type.canBoost
                    + " elevation=" + String.format("%.2f", mega.elevation()) + " isFlying=" + mega.isFlying());
                check("派生类型不再继承 canBoost（" + mega.type.canBoost + "）", !mega.type.canBoost);
                long s0 = shots(mega);
                for(int i = 0; i < 60; i++){ playerTick(mega, true); run(1); }
                long fired = shots(mega) - s0;
                System.out.println("[MB] 陆地巨兽按着助推 60 tick: elevation="
                    + String.format("%.2f", mega.elevation()) + " isFlying=" + mega.isFlying()
                    + " canShoot=" + mega.canShoot() + " 开火=" + fired);
                check("陆地巨兽按着助推也不会被顶上天（elevation=" + String.format("%.2f", mega.elevation()) + "）",
                    !mega.isFlying());
                check("陆地巨兽按着助推时照样能开火（canShoot=" + mega.canShoot() + "）", mega.canShoot());
                check("陆地巨兽按着助推时真的打出了子弹（" + fired + " 发）", fired > 0);
                mega.kill();
            }
        }

        // ---------- ④ 空军为主的巨兽：一直悬空，但能开火（修前：canShoot=false，整只瘫痪） ----------
        // 注意构成：按设计稿"飞行成员 hitSize 之和 > 地面成员 hitSize 之和"才能飞，所以这里用
        // 2×flare + 1×dagger（18 > 8）而不是"随便带一架飞机"；地面为主的混编见 MegaFlightRuleTest。
        {
            Unit mega = mergeAt(ox * 8f + 600f, oy * 8f, air, air, UnitTypes.dagger);
            if(mega == null){ check("带空军成员的巨兽能融合（前置）", false); }
            else{
                Object hf = field(mega, "hasFlyer"), cf = field(mega, "canFly");
                System.out.println("[MB] 空军巨兽: hasFlyer=" + hf + " canFly=" + cf + " type.canBoost=" + mega.type.canBoost
                    + " elevation=" + String.format("%.2f", mega.elevation()) + " isFlying=" + mega.isFlying());
                check("空军为主的巨兽悬空（Σ飞行hitSize>Σ地面hitSize，isFlying=" + mega.isFlying() + "）", mega.isFlying());
                check("空军巨兽的 canBoost 也是 false（" + mega.type.canBoost + "）", !mega.type.canBoost);
                check("空军巨兽在空中 canShoot()=true（修前这里是 false = 整只瘫痪）", mega.canShoot());
                long s0 = shots(mega);
                for(int i = 0; i < 60; i++){ playerTick(mega, true); run(1); }
                long fired = shots(mega) - s0;
                System.out.println("[MB] 空军巨兽空中开火 60 tick = " + fired + " 发（isFlying="
                    + mega.isFlying() + " canShoot=" + mega.canShoot() + "）");
                check("空军巨兽在空中真的能打出子弹（" + fired + " 发）", fired > 0);
                mega.kill();
            }
        }

        System.out.println("[MB] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
