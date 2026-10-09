package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * 用户报："把合体单位放在原地、让它自己打路过的敌方单位，它就会左脑攻击右脑一直索敌抽搐不攻击"。
 *
 * <p>本测试把这只巨兽**放在原地不管**（不加任何玩家控制），让一个敌方单位从旁边**匀速路过**，
 * 逐 tick 采样：巨兽机身 rotation（看"抽搐"= 来回摆）、每把武器 mount 的 rotate/shoot/totalShots、
 * 舱里炮台的 totalShots、控制器、速度 —— 把"索敌但不攻击"现场量出来。
 */
public class MegaIdleFireTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_unit/data";
    static ClassLoader ml;
    static Class<?> mergeCls, megaCls;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new mindustry.core.Platform(){};
        Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MIF] " + t); };
        new HeadlessApplication(new MegaIdleFireTest(), t -> t.printStackTrace());
    }

    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }

    static Building place(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, team, null);
        Building bu = Vars.world.build(ax, ay);
        if(bu != null){ try{ bu.created(); }catch(Throwable ignored){} try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu;
    }

    static Seq<Building> bayAll(Unit u){
        try{
            Object bay = u.getClass().getMethod("bay").invoke(u);
            @SuppressWarnings("unchecked")
            Seq<Building> all = (Seq<Building>)bay.getClass().getMethod("all").invoke(bay);
            return all;
        }catch(Throwable t){ return new Seq<>(); }
    }

    static long shots(Unit u){
        long n = 0;
        for(var m : u.mounts()) n += m.totalShots;
        return n;
    }

    @Override public void init(){
        try{
            Core.settings.setDataDirectory(Core.files.local(dataDir));
            Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
            mindustry.core.UI.loadColors(); Fonts.loadContentIconsHeadless();
            Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
            Vars.mods.eachClass(Mod::init);
            if(Vars.logic == null) Vars.logic = new Logic();
            if(Vars.netServer == null) Vars.netServer = new NetServer();
            if(Vars.netClient == null) Vars.netClient = new NetClient();
            ml = Vars.mods.getMod("combineunit").main.getClass().getClassLoader();
            mergeCls = Class.forName("combineunit.units.UnitComboMerge", true, ml);
            megaCls = Class.forName("combineunit.units.mega.MegaUnitEntity", true, ml);

            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.waves = false;
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.unitCap = 600;
            Vars.logic.play();
            run(20);
            for(int y = 30; y < 190; y++) for(int x = 20; x < 240; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(5);
            Building core = place(Blocks.coreShard, 40, 40, Team.sharded);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 100000);
            run(5);

            float bx = 140 * 8f, by = 120 * 8f;
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < 3; i++){                      // 3 只 avert：原版唯一的 rotate=false 固定武器，最能体现"要转机身才打得到"
                Unit u = UnitTypes.avert.create(Team.sharded);
                u.set(bx - 20f + 20f * i, by);
                u.add();
                us.add(u);
            }
            run(2);
            Unit mega = (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, us);
            if(mega == null){ System.out.println("[MIF] 融合失败"); System.exit(3); }
            mega.set(bx, by);
            run(5);
            // 舱里放两座炮台（左右各一），看它们跟巨兽机身是不是"各打各的"
            int mx = World.toTile(mega.x), my = World.toTile(mega.y);
            place(Blocks.duo, mx + 3, my, Team.sharded);
            place(Blocks.scatter, mx - 3, my, Team.sharded);
            run(2);
            mergeCls.getMethod("absorbNearbyTurrets", megaCls).invoke(null, mega);
            run(3);
            System.out.println("[MIF] 巨兽=" + mega.type.name + " hitSize=" + mega.hitSize()
                + " 武器数=" + mega.mounts().length + " 舱=" + bayAll(mega).size
                + " aiController=" + (mega.type.aiController == null ? "null" : "有")
                + " controller=" + mega.controller().getClass().getSimpleName()
                + " rotateSpeed=" + mega.type.rotateSpeed + " omni=" + mega.type.omniMovement
                + " faceTarget=" + mega.type.faceTarget);

            // 敌人从左边匀速路过（不攻击巨兽，只走过）
            Unit walker = UnitTypes.dagger.create(Team.crux);
            walker.set(mega.x - 160f, mega.y - 70f);   // 从侧上方贴近路过（在喷火器射程内、但不在炮口朝向）
            walker.maxHealth(1e9f);
            walker.health(1e9f);
            walker.add();
            long shots0 = shots(mega);
            float rMin = 999f, rMax = -999f, lastR = mega.rotation();
            float totalTurn = 0f;
            int bayShots = 0;
            StringBuilder trace = new StringBuilder();
            for(int i = 0; i < 900; i++){
                walker.vel().set(2.2f, 0f);                     // 匀速向右走（侧上方路过）
                walker.set(walker.x + 0f, walker.y);
                run(1);
                float r = mega.rotation();
                totalTurn += Math.abs(arc.math.Angles.angleDist(lastR, r));
                lastR = r;
                rMin = Math.min(rMin, r);
                rMax = Math.max(rMax, r);
                if(i % 150 == 0){
                    for(Building b : bayAll(mega)){
                        if(b instanceof Turret.TurretBuild tb) bayShots += tb.totalShots;
                    }
                    trace.append("\n       t=").append(i)
                        .append(" 机身=").append((int)r)
                        .append(" 速度=").append(String.format("%.2f", mega.vel().len()))
                        .append(" 敌方@").append((int)walker.x)
                        .append(" 巨兽@").append((int)mega.x).append(",").append((int)mega.y);
                    for(var m : mega.mounts()){
                        trace.append("\n         [").append(m.weapon.name).append("] rotate=").append(m.rotate)
                            .append(" shoot=").append(m.shoot).append(" mountRot=").append((int)m.rotation)
                            .append(" targetRot=").append(m.targetRotation == 0f && !m.rotate ? -1 : (int)m.targetRotation)
                            .append(" shots=").append(m.totalShots);
                    }
                    for(Building b : bayAll(mega)){
                        if(b instanceof Turret.TurretBuild tb){
                            trace.append("\n         舱:").append(b.block.name).append(" 朝向=").append((int)tb.rotation)
                                .append(" 开火=").append(tb.totalShots);
                        }
                    }
                }
            }
            long fired = shots(mega) - shots0;
            int bayFired = 0;
            for(Building b : bayAll(mega)) if(b instanceof Turret.TurretBuild tb) bayFired += tb.totalShots;
            System.out.println("[MIF] 900 tick 结果：机身 rotation 范围 " + (int)rMin + "~" + (int)rMax
                + " 累计转过 " + (int)totalTurn + "°（抽搐=反复来回）");
            System.out.println("[MIF] 巨兽自己武器打出 " + fired + " 发；舱里炮台打出 " + bayFired + " 发");
            System.out.println("[MIF] 采样：" + trace);
            System.exit(0);
        }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
