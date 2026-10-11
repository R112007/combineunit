package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * 用户报：<b>"装在组合巨兽上面的炮台有概率无法攻击，退出重进又好了，这个情况只会在单人模式
 * 或者服务端出现，如果是联机的话客户端一切正常"</b>。
 *
 * <p>根因：炮台舱以前是**把世界里那栋真实建筑整个搬进去**。客户端（收快照 rebuild）和读档都是
 * <b>重建</b>出来的全新实例 —— BlockUnit 是默认控制器、目标 / 逻辑操控时间 / 激活计时全是初值；
 * 只有服务端/单人搬的是真建筑，会把它在世界的运行时状态一并带进舱。最要命的是 BlockUnit 的
 * controller：玩家在合体前用原版"操控建筑"（附身）过这座炮台时，它的 unit.controller 就是这个
 * Player。进舱后 {@code TurretBuild.updateTile()} 一直走"玩家操控"分支（开不开火只看那个玩家的
 * 扳机、efficiency 也是 0），而模组的 {@code driveByPlayer} 用 {@code TurretBuild.control()}
 * 驱动又会被 {@code unit.isPlayer()} 挡掉 —— 于是这座炮台**永远不攻击**；退出重进（走 rebuild）
 * 才会好。客户端本来就用重建实例，所以"联机客户端一切正常"。
 *
 * <p>判定：摆一座 duo、喂满弹、让一个 Player 附身它 → 合体成巨兽 → 把这座 duo 吸进舱 →
 * 场上放一圈敌方靶子跑 1500 tick：舱里这座 duo 必须有 {@code totalShots > 0}，而且它的
 * BlockUnit 不能还是那个玩家在操控（得是重建出来的默认控制器）。
 * 旧代码（直接搬真实建筑）实测 0 发 → FAIL。
 */
public class MegaTurretPossessTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_unit/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml;
    static Class<?> mergeCls, megaCls;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new mindustry.core.Platform(){};
        Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[MTP] " + t); };
        new HeadlessApplication(new MegaTurretPossessTest(), t -> t.printStackTrace());
    }

    static void check(String n, boolean ok){ System.out.println("[MTP] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
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
        Unit u = t.create(Team.crux); u.set(x, y); u.maxHealth(1e9f); u.health(1e9f); u.add(); return u;
    }

    static Seq<Building> bayAll(Unit u){
        try{
            Object bay = u.getClass().getMethod("bay").invoke(u);
            @SuppressWarnings("unchecked")
            Seq<Building> all = (Seq<Building>)bay.getClass().getMethod("all").invoke(bay);
            return all;
        }catch(Throwable t){ return new Seq<>(); }
    }

    static int[] findLand(){
        for(int y = 45; y < 170; y++){
            for(int x = 30; x < 240; x++){
                boolean ok = true;
                for(int dy = -3; dy <= 3 && ok; dy++) for(int dx = -4; dx <= 4; dx++){
                    Tile t = Vars.world.tile(x + dx, y + dy);
                    if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                }
                if(ok) return new int[]{x, y};
            }
        }
        return new int[]{100, 100};
    }

    @Override public void init(){
        try{
            Core.settings.setDataDirectory(Core.files.local(dataDir));
            Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
            UI.loadColors(); Fonts.loadContentIconsHeadless();
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
            Vars.state.rules.unitCap = 900;
            Vars.logic.play();
            run(20);
            for(int y = 20; y < 210; y++) for(int x = 10; x < 260; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(5);
            int[] land = findLand();
            int lx = land[0], ly = land[1];
            System.out.println("[MTP] 测试场地（陆地）: " + lx + "," + ly);
            Building core = place(Blocks.coreShard, 30, 30, Team.sharded);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 100000);
            if(core != null){ core.maxHealth(1e9f); core.health(1e9f); }
            run(10);

            // 摆一座 duo、喂满弹
            Building duo = place(Blocks.duo, lx, ly, Team.sharded);
            check("duo 摆好", duo instanceof ItemTurret.ItemTurretBuild);
            try{ duo.maxHealth(1e9f); duo.health(1e9f); }catch(Throwable ignored){}
            ItemTurret.ItemTurretBuild itb = (ItemTurret.ItemTurretBuild)duo;
            Item fed = ((ItemTurret)duo.block).ammoTypes.keys().next();
            int guard = 0;
            while(itb.acceptItem(itb, fed) && guard++ < 4096) itb.handleItem(itb, fed);
            run(3);

            // 一个玩家附身这座炮台（原版"操控建筑"：player.unit(炮台的 BlockUnit)）
            Player pl = Player.create();
            pl.name = "probe";
            pl.team(Team.sharded);
            pl.add();
            Unit turretUnit = ((Turret.TurretBuild)duo).unit();
            pl.unit(turretUnit);
            run(3);
            check("玩家附身在这座 duo 上（前置条件）", turretUnit.isPlayer() && pl.unit() == turretUnit);

            // 合体成巨兽，再把这座 duo 吸进舱
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < 4; i++){
                Unit u = UnitTypes.dagger.create(Team.sharded);
                u.set(lx * 8f, ly * 8f + i * 16f);
                u.add(); us.add(u);
            }
            run(5);
            Unit mega = (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, us);
            check("合体成巨兽", mega != null);
            mega.set(duo.x - 24, duo.y);
            run(2);
            boolean ok = (Boolean)mergeCls.getMethod("absorbTurretAt", megaCls, int.class, int.class)
                .invoke(null, mega, duo.tile.x, duo.tile.y);
            run(3);
            Building inBay = null;
            for(Building x : bayAll(mega)) if(x.block == Blocks.duo) inBay = x;
            check("附身过的 duo 也能吸进炮台舱", ok && inBay != null);
            if(!(inBay instanceof Turret.TurretBuild bt)){
                System.out.println("[MTP] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + " fail=" + fail + ")");
                System.exit(fail == 0 ? 0 : 1);
                return;
            }
            check("舱里这座 duo 的 BlockUnit 不再被玩家操控（是重建出来的默认控制器）", !bt.unit().isPlayer());

            // 场上放一圈敌方靶子，跑一会儿看它到底开不开火
            for(float dist : new float[]{50f, 90f, 150f, 250f}){
                for(int k = 0; k < 4; k++){
                    float ang = k * 90f + 45f;
                    spawn(k % 2 == 0 ? UnitTypes.dagger : UnitTypes.flare,
                        mega.x + arc.math.Angles.trnsx(ang, dist), mega.y + arc.math.Angles.trnsy(ang, dist));
                }
            }
            int before = bt.totalShots;
            run(1500);
            int delta = bt.totalShots - before;
            System.out.println("[MTP] 舱里 duo 开火=" + delta + " hasAmmo=" + bt.hasAmmo()
                + " canConsume=" + bt.canConsume() + " eff=" + bt.efficiency
                + " ctrl=" + bt.unit().controller() + " isPlayer=" + bt.unit().isPlayer());
            check("附身过的炮台进舱后**自己**照常开火（" + delta + " 发）", delta > 0);

            System.out.println("[MTP] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + " fail=" + fail + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            System.out.println("[MTP] 崩了: " + t);
            t.printStackTrace();
            System.exit(2);
        }
    }
}
