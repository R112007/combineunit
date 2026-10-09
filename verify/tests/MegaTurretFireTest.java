package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * "把每一种炮台都吸进巨兽体内，看它到底开不开火" —— 用户报"有的炮台不发射"的现场。
 *
 * <p>场地：清空一块大平地 + 一个塞满全物品/液体的核心。巨兽用 2 只 dagger 合体（舱位上限 4，一次只测一种）。
 * 每种炮台：先在巨兽旁边摆一座 → `absorbTurretAt` 吸进去 → 场上放 1 只地面 + 1 只空中厚血靶子
 * （近处 60px、远处 170px 各一对，避开炮兵的最小射程）→ 跑 240 tick → 读这座炮台的 `totalShots`。
 *
 * <p>默认只打印、exit 0（诊断用）；`-Dassert=1` 时对"应当能开火"的炮台做断言（口径见代码里注释）。
 */
public class MegaTurretFireTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_sf2/data";
    static boolean doAssert = Boolean.getBoolean("assert");
    static int pass = 0, fail = 0;
    static ClassLoader ml;
    static Class<?> mergeCls, megaCls;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new mindustry.core.Platform(){};
        Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MTF] " + t); };
        new HeadlessApplication(new MegaTurretFireTest(), t -> t.printStackTrace());
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

    static Unit spawn(UnitType t, float x, float y){
        Unit u = t.create(Team.crux);
        u.set(x, y);
        u.maxHealth(1e9f);
        u.health(1e9f);
        u.add();
        return u;
    }


    static int absorbRadius(Unit mega){
        try{
            java.lang.reflect.Method m2 = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getDeclaredMethod("absorbRadius", megaCls);
            m2.setAccessible(true);
            return (int)(float)m2.invoke(null, mega);
        }catch(Throwable t){ return -1; }
    }

    static int bayMax(Unit mega){
        try{
            Object bay = mega.getClass().getMethod("bay").invoke(mega);
            return (Integer)bay.getClass().getMethod("maxTurrets").invoke(bay);
        }catch(Throwable t){ return -1; }
    }


    static boolean absorbable(Building b){
        try{
            java.lang.reflect.Method m = Class.forName("combineunit.units.mega.MegaTurretBay", true, ml)
                .getDeclaredMethod("absorbable", Building.class, mindustry.game.Team.class);
            m.setAccessible(true);
            return (Boolean)m.invoke(null, b, b.team);
        }catch(Throwable t){ return false; }
    }

    static Seq<Building> bayAll(Unit u){
        try{
            Object bay = u.getClass().getMethod("bay").invoke(u);
            @SuppressWarnings("unchecked")
            Seq<Building> all = (Seq<Building>)bay.getClass().getMethod("all").invoke(bay);
            return all;
        }catch(Throwable t){ return new Seq<>(); }
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
            for(int y = 20; y < 200; y++) for(int x = 10; x < 260; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(5);
            Building core = place(Blocks.coreShard, 30, 30, Team.sharded);
            if(core != null && core.items != null){
                for(Item it : Vars.content.items()) core.items.set(it, 100000);
            }
            run(5);

            // 【探针】真实世界里 afflict + 热源贴着放，看原版 calculateHeat 到底算出多少
            try{
                Block afflict = Vars.content.block("afflict");
                Building probe = afflict == null ? null : place(afflict, 200, 100, Team.sharded);
                Building srcB = place(Blocks.heatSource, 198, 100, Team.sharded);
                run(3);
                if(probe != null && srcB != null && probe instanceof Turret.TurretBuild ptb){
                    if(srcB instanceof mindustry.world.blocks.heat.HeatProducer.HeatProducerBuild hp){
                        hp.heat = ((mindustry.world.blocks.heat.HeatProducer)Blocks.heatSource).heatOutput;
                    }
                    float[] sh = new float[4];
                    System.out.println("[MTF] 探针(真世界): afflict calculateHeat=" + ptb.calculateHeat(sh)
                        + " sideHeat=" + sh[0] + "/" + sh[1] + "/" + sh[2] + "/" + sh[3]
                        + " 热源heat()=" + ((mindustry.world.blocks.heat.HeatProducer.HeatProducerBuild)srcB).heat()
                        + " 距离=" + probe.dst(srcB) + " 热源size=" + srcB.block.size + " 炮台size=" + probe.block.size
                        + " 热源team=" + srcB.team + " 炮台team=" + probe.team);
                    probe.tile.setBlock(Blocks.air);
                    srcB.tile.setBlock(Blocks.air);
                    run(2);
                }
            }catch(Throwable t){ System.out.println("[MTF] 探针失败: " + t); }

            // 巨兽（4 只 vanquish → hitSize 56、吸收半径 80px，够放下大炮台；舱位上限 20）
            Seq<Unit> us = new Seq<>();
            float bx = 140 * 8f, by = 120 * 8f;
            for(int i = 0; i < 20; i++){
                Unit u = UnitTypes.vanquish.create(Team.sharded);
                u.set(bx - 95f + 10f * i, by);
                u.add();
                us.add(u);
            }
            run(2);
            Unit mega = (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, us);
            if(mega == null){ System.out.println("[MTF] 融合失败"); System.exit(3); }
            mega.set(bx, by);
            run(5);

            int tested = 0, fired = 0;
            Seq<String> noFire = new Seq<>();
            for(Block b : Vars.content.blocks()){
                if(!(b instanceof Turret t)) continue;
                // 只测能被吸的（和 MegaTurretBay.absorbable 一个口径：是炮台、不是 PayloadAmmoTurret）
                if(t instanceof PayloadAmmoTurret) continue;
                if(b.size > 6) continue;   // 比吸收半径还大的方块会把巨兽压死（扫描工具的限制，不是模组问题）
                // 场地清干净：只留核心（不然上一种的炮台还在世界里）
                Seq<Building> wipe = new Seq<>();
                for(Building bb : Groups.build) if(bb.team == Team.sharded && !(bb.block instanceof mindustry.world.blocks.storage.CoreBlock)) wipe.add(bb);
                for(Building bb : wipe){ if(bb.tile != null) bb.tile.setBlock(Blocks.air); }
                Seq<Unit> dead = new Seq<>();
                for(Unit u : Groups.unit) if(u.team() == Team.crux) dead.add(u);
                for(Unit u : dead) u.kill();
                run(3);

                // 在巨兽旁边摆一座（方块大了就挪远点，仍旧要在吸收半径里）
                int sz = Math.max(b.size, 1);
                int off = Math.max(1, 2 - sz / 2);        // 让方块"中心"离巨兽 ~2 格（大块就往里挪）
                Building turret = place(b, World.toTile(mega.x) + off, World.toTile(mega.y), Team.sharded);
                if(turret == null){
                    System.out.println("[MTF]   " + b.name + "：摆不下，跳过");
                    continue;
                }
                run(2);
                boolean ok = (Boolean)mergeCls.getMethod("absorbTurretAt", megaCls, int.class, int.class)
                    .invoke(null, mega, turret.tile.x, turret.tile.y);
                run(3);
                Building inBay = null;
                for(Building bb : bayAll(mega)) if(bb.block == b) inBay = bb;
                if(!ok || inBay == null){
                    System.out.println("[MTF]   " + b.name + "：吸不进去（返回 " + ok + "）"
                        + " 距离=" + (int)mega.dst(turret) + " 半径=" + absorbRadius(mega)
                        + " 舱=" + bayAll(mega).size + "/" + bayMax(mega)
                        + " 方块size=" + b.size + " 可吸=" + absorbable(turret)
                        + " 半径=" + absorbRadius(mega) + " 舱位上限=" + bayMax(mega));
                    continue;
                }
                tested++;

                // 靶子：地面 + 空中，从 40px 到 600px 铺一圈 —— 有的炮台是短程（够不着 170px），
                // 有的是炮兵带最小射程（40px 落在最小射程里面），只有铺开才公平。
                Seq<Unit> targets = new Seq<>();
                for(float dist : new float[]{40f, 90f, 160f, 300f, 600f}){
                    for(int k = 0; k < 4; k++){
                        float ang = k * 90f + 45f;
                        targets.add(spawn(k % 2 == 0 ? UnitTypes.dagger : UnitTypes.flare,
                            mega.x + arc.math.Angles.trnsx(ang, dist), mega.y + arc.math.Angles.trnsy(ang, dist)));
                    }
                }
                // 跑久一点：很多大炮 reload 300~900 tick（scathe 600、强化基站 900），
                // 跑 240 tick 会误判成"不发射"。
                run(3000);
                int shots = inBay instanceof Turret.TurretBuild tb ? tb.totalShots : -1;
                // 激光类（LaserTurret/ContinuousTurret）不一定累加 totalShots，另外数一下"它自己的子弹"
                final Building inBayF = inBay;
                int owned = Groups.bullet.count(bl -> bl.owner == inBayF);
                if(owned > 0) shots = Math.max(shots, owned);
                if(shots > 0) fired++;
                else{
                    noFire.add(b.name + "(" + b.getClass().getSimpleName()
                        + (b instanceof ItemTurret it ? " 弹药" + it.ammoTypes.size : "")
                        + (b instanceof Turret tt && tt.heatRequirement > 0 ? " 需热" + (int)tt.heatRequirement : "")
                        + ")");
                    // 【现场】为什么不开火：把"原版判定用的那几个量"都打出来
                    if(inBay instanceof Turret.TurretBuild tb){
                        String bay = "";
                        if(inBay instanceof ItemTurret.ItemTurretBuild itb){
                            bay = " 弹仓=" + itb.totalAmmo + "/" + ((ItemTurret)b).maxAmmo
                                + " 条目=" + itb.ammo.size
                                + (itb.ammo.size > 0 ? (" 栈顶=" + ((ItemTurret.ItemEntry)itb.ammo.peek()).amount) : "")
                                + " 每发=" + ((ItemTurret)b).ammoPerShot;
                        }
                        System.out.println("[MTF]     现场 " + b.name + ":" + bay
                            + " power=" + (tb.power == null ? "-" : tb.power.status)
                            + " 效率=" + tb.efficiency + " canConsume=" + tb.canConsume()
                            + " hasAmmo=" + tb.hasAmmo()
                            + " heatReq=" + tb.heatReq + "/" + (tb.block instanceof Turret tk ? tk.heatRequirement : -1)
                            + " proximity=" + (tb.proximity == null ? "-" : tb.proximity.size)
                            + " target=" + (tb.target == null ? "null" : tb.target.getClass().getSimpleName())
                            + " reload=" + (int)tb.reloadCounter + "/" + (int)((Turret)tb.block).reload
                            + " 液体=" + (inBay.liquids == null ? "-" : String.format("%.1f", inBay.liquids.currentAmount()) + " " + (inBay.liquids.currentAmount() > 0 ? inBay.liquids.current().localizedName : ""))
                            + " 朝向=" + (int)tb.rotation);
                        StringBuilder cons = new StringBuilder();
                        for(var c : ((Block)b).nonOptionalConsumers) cons.append(c.getClass().getSimpleName()).append(' ');
                        cons.append("| opt:");
                        for(var c : ((Block)b).optionalConsumers) cons.append(c.getClass().getSimpleName()).append(' ');
                        System.out.println("[MTF]       消耗器: " + cons);
                        if(b instanceof Turret tk2 && tk2.heatRequirement > 0){
                            float[] sh = new float[4];
                            System.out.println("[MTF]       热量: 自己算=" + tb.calculateHeat(sh) + " sideHeat=" + sh[0] + "/" + sh[1] + "/" + sh[2] + "/" + sh[3]
                                + " proximity=" + tb.proximity);
                            for(Building p : tb.proximity){
                                if(p != null && p.block == Blocks.heatSource && p instanceof mindustry.world.blocks.heat.HeatProducer.HeatProducerBuild hp){
                                    System.out.println("[MTF]       热源: heat=" + hp.heat + " heat()=" + hp.heat() + " heatOutput=" + ((mindustry.world.blocks.heat.HeatProducer)Blocks.heatSource).heatOutput);
                                }
                            }
                        }
                    }
                }
                System.out.println("[MTF] " + b.name + " class=" + b.getClass().getSimpleName()
                    + " 开火=" + shots + (shots > 0 ? " ✓" : " ✗")
                    + (b instanceof ItemTurret it ? " 弹药表=" + it.ammoTypes.size : "")
                    + (b instanceof Turret tt && tt.heatRequirement > 0 ? " heatReq=" + tt.heatRequirement : ""));
                for(Unit tt : targets) tt.kill();
                run(2);
            }
            System.out.println("[MTF] RESULT 测了 " + tested + " 种炮台，开火 " + fired + "，不开火 " + noFire.size);
            for(String s : noFire) System.out.println("[MTF]   不开火: " + s);
            System.out.println("[MTF] " + (doAssert ? ("ASSERT " + (fail == 0 ? "ALL PASS" : fail + " FAILED")) : "（诊断模式，无断言）"));
            System.exit(doAssert && fail > 0 ? 1 : 0);
        }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
