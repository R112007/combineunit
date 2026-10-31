package combineunit.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.core.Logic;
import mindustry.core.NetClient;
import mindustry.core.NetServer;
import mindustry.core.Platform;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.net.Net;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.meta.BuildVisibility;

/**
 * 巨兽炮台舱的双物品输入回归：炮台除了弹药，还要同时拿到硅和煤才会开火。
 *
 * <p>这个形状和模组里 {@code consumeItems(物品A, 物品B)} 的炮台一致。只补
 * {@code ItemTurret.ammoTypes} 时弹仓有铜但消费效率恒为 0，修复后必须开火且从核心扣两种输入。
 */
public class MegaDualItemTurretTest implements arc.ApplicationListener {
    static String dataDir = "C:/Users/RHN/AppData/Local/Temp/mega-dual/data";
    static ClassLoader modLoader;

    public static void main(String[] args) {
        if (args.length > 0)
            dataDir = args[0];
        Vars.platform = new Platform() {};
        Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (level, text) -> {
            if (level.ordinal() >= Log.LogLevel.warn.ordinal())
                System.out.println("[MDI] " + text);
        };
        new HeadlessApplication(new MegaDualItemTurretTest(), Throwable::printStackTrace);
    }

    static void run(int frames) {
        for (int i = 0; i < frames; i++) {
            Time.delta = 1f;
            Vars.logic.update();
        }
    }

    static Building place(Block block, int x, int y, Team team) {
        mindustry.world.Build.beginPlace(null, block, team, x, y, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x, y), block, null,
            (byte) 0, team, null);
        Building build = Vars.world.build(x, y);
        if (build != null)
            try {
                build.updateProximity();
            } catch (Throwable ignored) {
            }
        return build;
    }

    @Override
    public void init() {
        try {
            Core.settings.setDataDirectory(Core.files.local(dataDir));
            Vars.loadLocales = false;
            Vars.loadSettings();
            Vars.headless = true;
            Vars.init();
            mindustry.core.UI.loadColors();
            Fonts.loadContentIconsHeadless();
            Vars.content.createBaseContent();
            Vars.mods.loadScripts();
            Vars.content.createModContent();
            Vars.content.init();
            Vars.mods.eachClass(Mod::init);
            if (Vars.logic == null)
                Vars.logic = new Logic();
            if (Vars.netServer == null)
                Vars.netServer = new NetServer();
            if (Vars.netClient == null)
                Vars.netClient = new NetClient();

            modLoader = Vars.mods.getMod("combineunit").main.getClass().getClassLoader();
            Class<?> merge = Class.forName("combineunit.units.UnitComboMerge", true, modLoader);
            Class<?> megaClass = Class.forName("combineunit.units.mega.MegaUnitEntity", true, modLoader);

            // 测试专用双输入炮台：弹药是铜，另有硅/煤两个普通物品消费者。
            ItemTurret originalDuo = (ItemTurret) Vars.content.block("duo");
            ItemTurret dual = new ItemTurret("dual-input-probe");
            dual.localizedName = "双输入测试炮台";
            dual.description = "测试用：同时需要硅和煤才会开火。";
            dual.health = 400;
            dual.size = 1;
            dual.itemCapacity = 40;
            dual.maxAmmo = 30;
            dual.range = 180f;
            dual.reload = 12f;
            dual.targetAir = true;
            dual.targetGround = true;
            dual.requirements(Category.turret, BuildVisibility.hidden, new ItemStack[0]);
            dual.ammo(Items.copper, originalDuo.ammoTypes.get(Items.copper));
            dual.consumeItems(new ItemStack(Items.silicon, 5), new ItemStack(Items.coal, 5));
            dual.alwaysUnlocked = true;
            dual.init();
            dual.postInit();

            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.waves = false;
            Vars.state.rules.canGameOver = false;
            Vars.logic.play();
            run(20);
            for (int y = 30; y < 170; y++)
                for (int x = 20; x < 240; x++) {
                    Tile tile = Vars.world.tile(x, y);
                    if (tile != null && tile.block() != Blocks.air)
                        tile.setBlock(Blocks.air);
                }
            run(5);

            Team team = Vars.state.rules.defaultTeam;
            Building core = place(Blocks.coreShard, 40, 40, team);
            for (var item : Vars.content.items())
                core.items.set(item, 5000);
            int silicon0 = core.items.get(Items.silicon);
            int coal0 = core.items.get(Items.coal);

            Seq<Unit> members = new Seq<>();
            for (int i = 0; i < 2; i++) {
                Unit unit = UnitTypes.dagger.create(team);
                unit.set(96 * Vars.tilesize, 100 * Vars.tilesize + i * 16f);
                unit.add();
                members.add(unit);
            }
            run(10);
            Unit mega = (Unit) merge.getMethod("mergeSelected", Seq.class).invoke(null, members);
            Building turret = place(dual, 100, 100, team);
            boolean absorbed = (Boolean) merge
                .getMethod("absorbTurretAt", megaClass, int.class, int.class)
                .invoke(null, mega, turret.tile.x, turret.tile.y);
            run(3);

            Object bay = megaClass.getMethod("bay").invoke(mega);
            @SuppressWarnings("unchecked")
            Seq<Building> all = (Seq<Building>) bay.getClass().getMethod("all").invoke(bay);
            Building inBay = all.isEmpty() ? null : all.first();

            Unit enemy = UnitTypes.dagger.create(Team.crux);
            enemy.set(mega.x + 100f, mega.y);
            enemy.maxHealth(1e9f);
            enemy.health(1e9f);
            enemy.add();
            run(600);

            int shots = inBay instanceof ItemTurret.ItemTurretBuild itb ? itb.totalShots : -1;
            int ammo = inBay instanceof ItemTurret.ItemTurretBuild itb ? itb.totalAmmo : -1;
            int silicon1 = core.items.get(Items.silicon);
            int coal1 = core.items.get(Items.coal);
            boolean ok = absorbed && shots > 0 && ammo > 0
                && silicon1 < silicon0 && coal1 < coal0
                && inBay != null && inBay.items.get(Items.silicon) > 0 && inBay.items.get(Items.coal) > 0;
            System.out.println("[MDI] absorbed=" + absorbed + " shots=" + shots + " ammo=" + ammo
                + " core silicon " + silicon0 + "->" + silicon1
                + " coal " + coal0 + "->" + coal1
                + " bay silicon=" + (inBay == null ? -1 : inBay.items.get(Items.silicon))
                + " coal=" + (inBay == null ? -1 : inBay.items.get(Items.coal)));
            System.out.println("[MDI] " + (ok ? "PASS " : "FAIL ")
                + " 双物品炮台在巨兽舱内开火，且硅/煤都由核心补入");
            System.exit(ok ? 0 : 1);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(2);
        }
    }
}
