package combineunit.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.core.Logic;
import mindustry.core.NetClient;
import mindustry.core.NetServer;
import mindustry.core.Platform;
import mindustry.core.UI;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.net.Net;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.StaticWall;

/**
 * 普通单位挖矿与核心回收回归。
 *
 * <p>用户报："核心机挖矿时物品不回核心，手拖也直接消失；一切能采集物资的兵都不工作。"
 * combineunit 会把普通单位实体换成镜像类，因此必须单独验证：
 * <ul>
 *   <li>镜像单位仍能按原版 MinerComp 的规则挖到自己的物品背包；</li>
 *   <li>玩家附身挖矿并靠近核心时，物品直接进核心，单位背包不残留；</li>
 *   <li>玩家把单位背包里的物品手动转移到核心时，核心增加、单位减少，总量守恒。</li>
 * </ul>
 */
public class UnitMiningCoreTest {
    static String dataDir = "/tmp/mp_unit/data";
    static int pass;
    static int fail;

    public static void main(String[] args) {
        if (args.length > 0) dataDir = args[0];
        Vars.platform = new Platform() {};
        Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (level, text) -> {
            if (level.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[UMC] " + text);
        };
        new HeadlessApplication(new Listener(), throwable -> throwable.printStackTrace());
    }

    static void check(String name, boolean ok) {
        System.out.println("[UMC] " + (ok ? "PASS " : "FAIL ") + name);
        if (ok) pass++;
        else fail++;
    }

    static void run(int frames) {
        for (int i = 0; i < frames; i++) {
            Time.delta = 1f;
            Vars.logic.update();
        }
    }

    /** Headless 没有 client Control；直接跑单位自身更新，并在每帧前恢复玩家控制器。 */
    static void runPlayerUnit(Unit unit, Player owner, int frames) {
        for (int i = 0; i < frames; i++) {
            unit.controller(owner);
            owner.unit(unit);
            Time.delta = 1f;
            unit.update();
        }
    }

    static Building place(Block block, int x, int y) {
        int size = Math.max(block.size, 1);
        int ax = x + (size - 1) / 2;
        int ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, block, Team.sharded, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(
            Vars.world.tile(ax, ay), block, null, (byte)0, Team.sharded, null);
        Building build = Vars.world.build(ax, ay);
        if (build != null) {
            try { build.created(); } catch (Throwable ignored) {}
            try { build.updateProximity(); } catch (Throwable ignored) {}
        }
        return build;
    }

    static void clearArea() {
        for (int y = 20; y < 180; y++) {
            for (int x = 10; x < 250; x++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile != null && tile.block() != Blocks.air) tile.setBlock(Blocks.air);
            }
        }
    }

    static Unit spawn(UnitType type, float x, float y) {
        Unit unit = type.create(Team.sharded);
        unit.set(x, y);
        unit.add();
        return unit;
    }

    static Item firstMineable(UnitType type) {
        for (Item item : Vars.content.items()) {
            if (item.hardness <= type.mineTier) return item;
        }
        return null;
    }

    static class Listener implements arc.ApplicationListener {
        @Override
        public void init() {
            try {
                Core.settings.setDataDirectory(Core.files.local(dataDir));
                Vars.loadLocales = false;
                Vars.loadSettings();
                Vars.headless = true;
                Vars.init();
                UI.loadColors();
                Fonts.loadContentIconsHeadless();
                Vars.content.createBaseContent();
                Vars.mods.loadScripts();
                Vars.content.createModContent();
                Vars.content.init();
                Vars.mods.eachClass(Mod::init);
                if (Vars.logic == null) Vars.logic = new Logic();
                if (Vars.netServer == null) Vars.netServer = new NetServer();
                if (Vars.netClient == null) Vars.netClient = new NetClient();

                Map map = Vars.maps.all().find(candidate -> candidate.name().contains("Archipelago"));
                Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
                Vars.state.rules.waves = false;
                Vars.state.rules.canGameOver = false;
                Vars.logic.play();
                run(20);
                clearArea();
                run(5);

                Building core = place(Blocks.coreShard, 60, 60);
                check("核心已放置并登记", core != null && Team.sharded.data().core() == core);
                if (core == null || core.items == null) {
                    finish();
                    return;
                }
                for (Item item : Vars.content.items()) core.items.set(item, 0);

                UnitType miner = Vars.content.unit("poly");
                Item oreItem = miner == null ? null : firstMineable(miner);
                check("找到可采矿单位和矿种（" + (miner == null ? "null" : miner.name)
                    + " / " + (oreItem == null ? "null" : oreItem.name) + "）", miner != null && oreItem != null);
                if (miner == null || oreItem == null) {
                    finish();
                    return;
                }

                Block oreBlock = oreItem == Items.copper ? Blocks.oreCopper
                    : oreItem == Items.lead ? Blocks.oreLead
                    : oreItem == Items.coal ? Blocks.oreCoal
                    : oreItem == Items.titanium ? Blocks.oreTitanium
                    : oreItem == Items.thorium ? Blocks.oreThorium
                    : null;
                if (!(oreBlock instanceof Floor)) {
                    System.out.println("[UMC] SKIP " + oreItem.name + " 没有对应矿石地板");
                    finish();
                    return;
                }

                Tile ore = Vars.world.tile(66, 60);
                ore.setFloor((Floor)oreBlock);

                // A：遍历所有"能采集物资"的单位类型，确认镜像实体没有破坏 MinerComp。
                int mineTypes = 0;
                int mineFailures = 0;
                for (UnitType type : Vars.content.units()) {
                    if (type.mineSpeed <= 0f || type.mineTier < 0
                        || !type.mineFloor || type.itemCapacity <= 0)
                        continue;
                    mineTypes++;
                    ore.setBlock(Blocks.air);
                    if (type.mineFloor) {
                        ore.setFloor((Floor)oreBlock);
                    } else {
                        StaticWall wall = mineableWall(type);
                        if (wall == null) {
                            mineFailures++;
                            System.out.println("[UMC] FAIL " + type.name + "：内容表里没有它挖得动的矿墙");
                            continue;
                        }
                        ore.setBlock(wall);
                    }
                    float sx = type.mineFloor ? ore.worldx() : ore.worldx() - Vars.tilesize;
                    Unit worker = spawn(type, sx, ore.worldy());
                    core.items.set(oreItem, 0);
                    run(3);
                    worker.mineTile(ore);
                    run(180);
                    int carried = worker.stack.amount;
                    int delivered = core.items.get(oreItem);
                    boolean ok = carried + delivered > 0;
                    if (!ok) {
                        mineFailures++;
                        System.out.println("[UMC] FAIL " + type.name + "：背包=" + carried
                            + " 核心=" + delivered
                            + " item=" + (worker.item() == null ? "null" : worker.item().name)
                            + " floor=" + type.mineFloor + " walls=" + type.mineWalls
                            + " range=" + type.mineRange + " valid=" + worker.validMine(ore)
                            + " immediate=" + worker.offloadImmediately()
                            + " itemCap=" + worker.itemCapacity());
                    }
                    worker.mineTile(null);
                    worker.remove();
                    run(2);
                }
                System.out.println("[UMC] A 可采集单位=" + mineTypes + "，失败=" + mineFailures);
                check("所有有背包的采集单位都能正常挖矿（" + (mineTypes - mineFailures) + "/" + mineTypes + "）",
                    mineTypes > 0 && mineFailures == 0);
                ore.setBlock(Blocks.air);
                ore.setFloor((Floor)oreBlock);

                // B：所有可采集类型都由玩家附身，靠近核心时直接进核心。
                Player owner = Player.create();
                owner.id = 0;
                owner.name = "tester";
                owner.locale = "en";
                owner.team(Team.sharded);
                int playerTypes = 0;
                int playerFailures = 0;
                for (UnitType type : Vars.content.units()) {
                    if (type.mineSpeed <= 0f || type.mineTier < 0
                        || (!type.mineFloor && !type.mineWalls))
                        continue;
                    playerTypes++;
                    ore.setBlock(Blocks.air);
                    Item targetItem = oreItem;
                    if (type.mineFloor) {
                        ore.setFloor((Floor)oreBlock);
                    } else {
                        StaticWall wall = mineableWall(type);
                        if (wall == null) {
                            playerFailures++;
                            System.out.println("[UMC] FAIL player " + type.name + "：没有可挖矿墙");
                            continue;
                        }
                        ore.setBlock(wall);
                        targetItem = wall.itemDrop;
                    }
                    float sx = type.mineFloor ? ore.worldx() : ore.worldx() - Vars.tilesize;
                    Unit playerUnit = spawn(type, sx, ore.worldy());
                    core.items.set(targetItem, 0);
                    runPlayerUnit(playerUnit, owner, 3);
                    playerUnit.mineTile(ore);
                    runPlayerUnit(playerUnit, owner, 240);
                    playerUnit.controller(owner);
                    owner.unit(playerUnit);
                    boolean ok = core.items.get(targetItem) > 0 && playerUnit.stack.amount == 0;
                    if (!ok) {
                        playerFailures++;
                        System.out.println("[UMC] FAIL player " + type.name
                            + "：核心=" + core.items.get(targetItem)
                            + " 背包=" + playerUnit.stack.amount
                            + " isPlayer=" + playerUnit.isPlayer()
                            + " 距核心=" + (int)playerUnit.dst(core)
                            + " item=" + (targetItem == null ? "null" : targetItem.name)
                            + " valid=" + playerUnit.validMine(ore)
                            + " dead=" + playerUnit.dead);
                    }
                    playerUnit.mineTile(null);
                    playerUnit.remove();
                }
                System.out.println("[UMC] B 玩家可控采集单位=" + playerTypes + "，失败=" + playerFailures);
                check("所有可采集单位由玩家操控时都能直接回核心（"
                    + (playerTypes - playerFailures) + "/" + playerTypes + "）",
                    playerTypes > 0 && playerFailures == 0);

                // C：手动转移（玩家把单位背包里的物品拖进核心）。
                ore.setBlock(Blocks.air);
                ore.setFloor((Floor)oreBlock);
                Unit playerUnit = spawn(miner, ore.worldx(), ore.worldy());
                runPlayerUnit(playerUnit, owner, 3);
                core.items.set(oreItem, 0);
                playerUnit.addItem(oreItem, 5);
                int manualBefore = core.items.get(oreItem);
                int unitBefore = playerUnit.stack.amount;
                Call.transferItemTo(playerUnit, oreItem, unitBefore, playerUnit.x, playerUnit.y, core);
                int manualAfter = core.items.get(oreItem);
                int unitAfter = playerUnit.stack.amount;
                System.out.println("[UMC] C 手动转移：核心 " + manualBefore + " → " + manualAfter
                    + "，单位 " + unitBefore + " → " + unitAfter);
                check("手动转移后核心收到物品", manualAfter == manualBefore + unitBefore);
                check("手动转移后单位背包清空", unitAfter == 0);

                finish();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                System.exit(2);
            }
        }

        static void finish() {
            System.out.println("[UMC] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED"))
                + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }
    }

    static StaticWall mineableWall(UnitType type) {
        for (Block block : Vars.content.blocks()) {
            if (block instanceof StaticWall wall && wall.itemDrop != null
                && wall.itemDrop.hardness <= type.mineTier)
                return wall;
        }
        return null;
    }
}
