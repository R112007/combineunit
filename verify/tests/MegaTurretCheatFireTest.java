package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * 巨兽炮台舱的**弹药补给**回归：用户报"有的炮台不发射，哪怕核心有弹药，比如 cyclone 等，
 * 所有的炮都是有的发射有的不发射"。
 *
 * <p>根因（两条，分开测）：
 * <ol>
 *   <li><b>沙盒/作弊规则</b>（{@code team.rules().cheat} = "方块不耗资源"）：{@code feedFromCore}
 *       以前直接 return（以为"原版自己管"），而原版给作弊炮台塞第一份弹药的地方是
 *       {@code ItemTurretBuild.onProximityAdded()} —— 巨兽炮台是手工 {@code create()} + 挂假格造出来的，
 *       永远走不到那条路 → 物品炮台弹仓恒空、{@code hasAmmo()} 恒 false、**一发都不打**，
 *       而液体/电力炮台（{@code supply()} 直接灌满/给电）照常开火 = 用户看到的"有的发射有的不发射"。
 *       现在是"作弊模式不扣核心库存、直接把弹仓补满"。</li>
 *   <li>普通规则下本来就要能打（这条是防回归：修沙盒那条不许把普通路径弄坏）。</li>
 * </ol>
 *
 * <p>判定（每种规则各跑 300 tick）：
 * 舱里每一座物品炮台（cyclone×2 + duo×2）都必须有 {@code totalShots > 0}；
 * 液体炮台（wave）作为对照也必须开火（它本来就没坏过）。
 */
public class MegaTurretCheatFireTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_unit/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml;
    static Class<?> mergeCls, megaCls;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new mindustry.core.Platform(){};
        Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MCF] " + t); };
        new HeadlessApplication(new MegaTurretCheatFireTest(), t -> t.printStackTrace());
    }

    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }

    static void check(String n, boolean ok){
        System.out.println("[MCF] " + (ok ? "PASS " : "FAIL ") + n);
        if(ok) pass++; else fail++;
    }

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

    @SuppressWarnings("unchecked")
    static Seq<Building> bayAll(Unit u){
        try{
            Object bay = u.getClass().getMethod("bay").invoke(u);
            return (Seq<Building>)bay.getClass().getMethod("all").invoke(bay);
        }catch(Throwable t){ return new Seq<>(); }
    }

    static int shots(Building b){
        return b instanceof Turret.TurretBuild tb ? tb.totalShots : -1;
    }

    static void resetAmmo(Building b){
        if(b instanceof ItemTurret.ItemTurretBuild itb){ itb.ammo.clear(); itb.totalAmmo = 0; }
        if(b instanceof Turret.TurretBuild tb) tb.totalShots = 0;
    }

    /** 每一段都现铺一圈靶子（上一段结束会把它们打掉，不然下一段没有目标 = 假 0 发）。 */
    static Seq<Unit> spawnTargets(Unit mega){
        Seq<Unit> targets = new Seq<>();
        for(float dist : new float[]{60f, 150f, 300f}){
            for(int k = 0; k < 4; k++){
                float ang = k * 90f + 45f;
                targets.add(spawn(k % 2 == 0 ? UnitTypes.dagger : UnitTypes.flare,
                    mega.x + arc.math.Angles.trnsx(ang, dist), mega.y + arc.math.Angles.trnsy(ang, dist)));
            }
        }
        return targets;
    }

    /** 跑一段并返回 "每种炮台名的开火数" 摘要。 */
    static String phase(String tag, Unit mega, int ticks){
        for(Building b : bayAll(mega)) resetAmmo(b);
        Seq<Unit> targets = spawnTargets(mega);
        run(ticks);
        StringBuilder sb = new StringBuilder();
        boolean allItemsFire = true, liquidFires = false, anyLiquid = false;
        int items = 0;
        for(Building b : bayAll(mega)){
            int s = shots(b);
            sb.append(b.block.name).append('=').append(s).append(' ');
            if(b instanceof ItemTurret.ItemTurretBuild){
                items++;
                if(s <= 0) allItemsFire = false;
            }else if(b.liquids != null){
                anyLiquid = true;
                if(s > 0) liquidFires = true;
            }
        }
        System.out.println("[MCF] " + tag + " 开火: " + sb);
        check(tag + "：舱里 " + items + " 座物品炮台全部开火（哪怕核心有弹药却打不出来就是这条挂了）",
            items > 0 && allItemsFire);
        if(anyLiquid) check(tag + "：液体炮台作为对照也开火（它本来就没坏）", liquidFires);
        for(Unit u : targets) u.kill();
        run(3);
        return sb.toString();
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

            // 队伍核心（真实登记进 TeamData.cores：feedFromCore 从 team.core() 扣料）
            Building core = place(Blocks.coreShard, 30, 30, Team.sharded);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 100000);
            run(5);
            check("队核已登记（team.core() 非空）", Team.sharded.core() != null);

            Seq<Unit> us = new Seq<>();
            float bx = 140 * 8f, by = 120 * 8f;
            for(int i = 0; i < 4; i++){
                Unit u = UnitTypes.vanquish.create(Team.sharded);
                u.set(bx - 30f + 20f * i, by);
                u.add();
                us.add(u);
            }
            run(2);
            Unit mega = (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, us);
            if(mega == null){ System.out.println("[MCF] 融合失败"); System.exit(3); }
            mega.set(bx, by);
            run(5);

            // 舱里放 2 cyclone（物品炮台，用户点名的）+ 2 duo + 1 wave（液体炮台，对照）
            int bxT = World.toTile(bx), byT = World.toTile(by);
            // 全部摆在吸收半径（hitSize+24 = 80px = 10 格）以内，否则吸不进去
            place(Blocks.cyclone, bxT + 4, byT - 4, Team.sharded);
            place(Blocks.cyclone, bxT + 4, byT + 1, Team.sharded);
            place(Blocks.duo, bxT + 4, byT + 6, Team.sharded);
            place(Blocks.duo, bxT + 1, byT + 7, Team.sharded);
            place(Blocks.wave, bxT + 1, byT - 6, Team.sharded);
            run(3);
            Object n = mergeCls.getMethod("absorbNearbyTurrets", megaCls).invoke(null, mega);
            run(5);
            Seq<Building> bay = bayAll(mega);
            StringBuilder names = new StringBuilder();
            int itemTurrets = 0;
            for(Building b : bay){
                names.append(b.block.name).append(' ');
                if(b instanceof ItemTurret.ItemTurretBuild) itemTurrets++;
            }
            System.out.println("[MCF] 吸收=" + n + " 舱=" + bay.size + " 座: " + names);
            check("吸收进舱（含 2 cyclone + 2 duo 物品炮台）", itemTurrets >= 4);

            phase("普通规则", mega, 600);

            // 【用户报的那条】沙盒/作弊规则：方块不耗资源
            Team.sharded.rules().cheat = true;
            String cheat = phase("cheat=true（沙盒作弊规则）", mega, 600);
            Team.sharded.rules().cheat = false;

            // 沙盒无限资源（另一条常见沙盒规则）：必须照常打
            Vars.state.rules.infiniteResources = true;
            phase("infiniteResources=true（沙盒）", mega, 600);
            Vars.state.rules.infiniteResources = false;

            System.out.println("[MCF] cheat 阶段开火: " + cheat.trim());
            System.out.println("[MCF] 结果: PASS=" + pass + " FAIL=" + fail);
            System.exit(fail > 0 ? 1 : 0);
        }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
