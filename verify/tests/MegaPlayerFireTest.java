package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.entities.units.UnitController;
import mindustry.game.*; import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报"电脑端合体 3 个饱和火力模组的单位神渎后，在**玩家控制**时无法攻击"（本仓库的回归测试）。
 *
 * <p>做法：模态数据目录里装了饱和火力模组，把 3 只神渎合成巨兽，然后**模拟玩家的开火输入**
 * （DesktopInput.updateMovement 那套：`unit.aim(mouse)` + `unit.controlWeapons(true, player.shooting && !boosted)`），
 * 跑若干 tick，数每把武器打出多少发（`WeaponMount.totalShots`），并与**没合体的单只神渎**对照。
 */
public class MegaPlayerFireTest implements ApplicationListener{
    static String dataDir="/tmp/mp_sf/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaPlayerFireTest(), t->t.printStackTrace()); }
    static void log(String s){ System.out.println("[MPF] " + s); }
    static void check(String n, boolean ok){ System.out.println("[MPF] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
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
    /** 模拟玩家操控：朝 (ax,ay) 瞄准 + 按住开火（DesktopInput 那一套）。 */
    static void playerTick(Unit u, float ax, float ay){
        u.aim(ax, ay, true);
        u.controlWeapons(true, true);
    }
    /** 真正走一遍原版**桌面输入**的那条路（DesktopInput.updateMovement），不是自己拼的模拟。 */
    static java.lang.reflect.Method updMovement;
    static void realDesktopInputTick(Unit u){
        try{
            if(updMovement == null){
                updMovement = Vars.control.input.getClass().getDeclaredMethod("updateMovement", Unit.class);
                updMovement.setAccessible(true);
            }
            Vars.player.shooting = true;   // 相当于鼠标按住左键
            updMovement.invoke(Vars.control.input, u);
        }catch(Throwable t){ log("updateMovement 调用失败: " + t); }
    }
    static long shots(Unit u){
        long n = 0;
        for(var m : u.mounts()) n += m.totalShots;
        return n;
    }
    static void dumpWeapons(String tag, Unit u){
        var ms = u.mounts();
        log(tag + " mounts=" + (ms == null ? "null" : ms.length) + " type=" + u.type.name
            + " hitSize=" + u.hitSize());
        if(ms == null) return;
        for(int i = 0; i < ms.length; i++){
            Weapon w = ms[i].weapon;
            log("   [" + i + "] " + w.name + " x=" + w.x + " y=" + w.y
                + " controllable=" + w.controllable + " autoTarget=" + w.autoTarget + " mirror=" + w.mirror
                + " flipSprite=" + w.flipSprite + " alternate=" + w.alternate + " rotate=" + w.rotate
                + " otherSide=" + w.otherSide + " shoot=" + (w.shoot == null ? "null" : w.shoot.getClass().getSimpleName())
                + " bullet=" + (w.bullet == null ? "null" : w.bullet.getClass().getSimpleName())
                + " | mount: shoot=" + ms[i].shoot + " rotate=" + ms[i].rotate + " warmup=" + ms[i].warmup
                + " reload=" + ms[i].reload + " totalShots=" + ms[i].totalShots);
        }
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
        log("已装模组: " + Vars.mods.list().map(m -> m.name).toString(", "));

        UnitType shen = null;
        for(UnitType t : Vars.content.units()){
            if(t.name.contains("神渎")){ shen = t; break; }
        }
        if(shen == null){
            log("（这个数据集里没装饱和火力，跳过：本测试需要模组单位 神渎）");
            System.exit(0);
        }
        log("神渎: " + shen.name + " hitSize=" + shen.hitSize + " 武器表 " + shen.weapons.size);
        for(Weapon w : shen.weapons){
            log("   " + w.name + " x=" + w.x + " y=" + w.y + " mirror=" + w.mirror + " alternate=" + w.alternate
                + " rotate=" + w.rotate + " controllable=" + w.controllable + " autoTarget=" + w.autoTarget
                + " otherSide=" + w.otherSide + " bullet=" + (w.bullet == null ? "null" : w.bullet.getClass().getSimpleName()));
        }

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
        log("陆地 @" + ox + "," + oy);
        place(Blocks.coreShard, ox + 30, oy + 16, Team.sharded);
        run(10);

        // ---- 对照组：单只神渎，玩家开火 ----
        Unit solo = spawn(shen, ox * 8f, oy * 8f);
        run(2);
        solo.rotation(90f);
        float aimX = solo.x, aimY = solo.y + 200f;
        long s0 = shots(solo);
        for(int i = 0; i < 60; i++){ playerTick(solo, aimX, aimY); run(1); }
        long soloShots = shots(solo) - s0;
        dumpWeapons("单只神渎（玩家开火 60 tick 后）", solo);
        log("单只神渎玩家开火总发数 = " + soloShots);
        check("单只神渎在玩家输入下会开火（" + soloShots + " 发）", soloShots > 0);

        // ---- 实验组：3 只神渎合体，玩家开火 ----
        Seq<Unit> us = new Seq<>();
        for(int i = 0; i < 3; i++) us.add(spawn(shen, ox * 8f + 400f + i * 120f, oy * 8f));
        run(2);
        Unit mega = null;
        try{
            Object o = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("mergeSelected", Seq.class).invoke(null, us);
            if(o instanceof Unit mu) mega = mu;
        }catch(Throwable t){ log("融合失败: " + t); }
        if(mega == null){ log("3 只神渎没能合体"); System.exit(4); }
        mega.controller(new DummyController());
        mega.set(ox * 8f + 400f, oy * 8f);
        run(2);
        mega.rotation(90f);
        dumpWeapons("神渎巨兽（开火前）", mega);
        long m0 = shots(mega);
        float maimX = mega.x, maimY = mega.y + 300f;
        for(int i = 0; i < 60; i++){ playerTick(mega, maimX, maimY); run(1); }
        long megaShots = shots(mega) - m0;
        dumpWeapons("神渎巨兽（玩家开火 60 tick 后）", mega);
        log("神渎巨兽玩家开火总发数 = " + megaShots);
        log("==== 对照：单只=" + soloShots + "，巨兽=" + megaShots + " ====");
        check("神渎巨兽在玩家输入下会开火（" + megaShots + " 发）", megaShots > 0);
        check("巨兽火力按成员数放大（" + megaShots + " ≥ 2×" + soloShots + "）", megaShots >= soloShots * 2);
        int noFire = 0;
        for(var m : mega.mounts()){
            if(m.weapon.controllable && !m.weapon.noAttack && m.totalShots <= 0) noFire++;
        }
        check("巨兽身上每一把可控武器都真的开过火（没开火的 " + noFire + " 把）", noFire == 0);

        // ---- 第三组：真正走原版桌面输入路径 DesktopInput.updateMovement ----
        if(Vars.control == null){
            log("（headless 里没有 Vars.control，真·桌面输入路径这一组跳过 —— 真客户端用 `sf` 模式验："
                + "verify/run-client.sh mx <装了饱和火力+combineunit的数据目录> sf）");
        }else{
            log("---- 走原版 DesktopInput.updateMovement（模拟鼠标按住开火）----");
            // 真输入路径需要"玩家 + 输入处理器"（这两样只有客户端流程会建，headless 得自己搭）
            try{
                if(Vars.player == null){
                    Vars.player = Player.create();
                    Vars.player.name = "probe";
                    Vars.player.team(Team.sharded);
                    Vars.player.add();
                }
                if(Vars.control.input == null){
                    Vars.control.setInput(new mindustry.input.DesktopInput());
                }
            }catch(Throwable t){ log("搭建玩家/输入失败: " + t); }
            Unit solo2 = spawn(shen, ox * 8f, oy * 8f + 600f);
            run(2);
            Vars.player.unit(solo2);
            long sr0 = shots(solo2);
            for(int i = 0; i < 60; i++){ realDesktopInputTick(solo2); run(1); }
            long soloReal = shots(solo2) - sr0;
            log("单只神渎（真输入路径 60 tick）开火 = " + soloReal
                + " mounts[0].shoot=" + solo2.mounts()[0].shoot + " rotation=" + solo2.rotation()
                + " aim=(" + (int)solo2.aimX() + "," + (int)solo2.aimY() + ")");

            if(mega != null){
                mega.set(ox * 8f + 400f, oy * 8f + 600f);
                run(2);
                Vars.player.unit(mega);
                long m0b = shots(mega);
                for(int i = 0; i < 60; i++){ realDesktopInputTick(mega); run(1); }
                long megaReal = shots(mega) - m0b;
                StringBuilder sb = new StringBuilder();
                for(var m : mega.mounts()){
                    sb.append(" [").append(m.weapon.name).append(" shoot=").append(m.shoot)
                      .append(",warm=").append(String.format("%.2f", m.warmup))
                      .append(",targetRot=").append((int)m.targetRotation)
                      .append(",unitRot=").append((int)mega.rotation()).append("]");
                }
                log("神渎巨兽（真输入路径 60 tick）开火 = " + megaReal);
                log("   巨兽 mount 状态:" + sb);
                log("   巨兽 isFlying=" + mega.isFlying() + " elevation=" + mega.elevation()
                    + " instanceof Mechc=" + (mega instanceof mindustry.gen.Mechc)
                    + " omniMovement=" + mega.type.omniMovement + " faceTarget=" + mega.type.faceTarget
                    + " hasWeapons=" + mega.hasWeapons() + " player.shooting=" + Vars.player.shooting);
            }
        }

        System.out.println("[MPF] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(0);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
