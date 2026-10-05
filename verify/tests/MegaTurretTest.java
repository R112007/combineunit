package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.io.SaveIO;
import mindustry.type.Item; import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 组合巨兽的炮台舱（合体时把脚下的炮台吸收进体内的小 World）：
 *
 * <ul>
 * <li>合体时附近同队炮台被吸收：从世界里消失、进巨兽的炮台舱；</li>
 * <li>吸收半径外的炮台不被吸收；</li>
 * <li>炮台在巨兽身上照常更新（朝敌对单位开火），物品炮台的弹药从核心库存里扣；</li>
 * <li>追加吸收（absorbNearbyTurrets）与释放（releaseTurrets）来回切换；</li>
 * <li>解体时炮台放回世界；</li>
 * <li>快照字节往返：客户端照着 writeSync 的字节能把炮台构成重建出来。</li>
 * </ul>
 *
 * 测试在只有 combineunit 的数据目录里跑（见 verify/make-dataset.sh）。
 */
public class MegaTurretTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_unit/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){};
        Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MT] " + t); };
        new HeadlessApplication(new MegaTurretTest(), t -> t.printStackTrace());
    }

    static void check(String n, boolean ok){ System.out.println("[MT] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
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

    static int memberCount(Unit u){ try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; } }
    static boolean hasTurrets(Unit u){ try{ return (Boolean)u.getClass().getMethod("hasTurrets").invoke(u); }catch(Throwable t){ return false; } }
    static int baySize(Unit u){
        try{ return (Integer)u.getClass().getMethod("bay").invoke(u).getClass().getMethod("size").invoke(u.getClass().getMethod("bay").invoke(u)); }
        catch(Throwable t){ return -1; }
    }

    static Unit merge(Class<?> mergeCls, float x, float y, UnitType... types){
        try{
            Seq<Unit> units = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = types[i].create(Team.sharded);
                // 竖直排开：别落在炮台格上（单位落到实心建筑格里会被清掉）
                u.set(x, y + i * 16f);
                u.add();
                units.add(u);
            }
            run(10);
            return (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
        }catch(Throwable t){ System.out.println("[MT] 融合失败: " + t); return null; }
    }

    static Class<?> megaCls() throws Throwable{ return Class.forName("combineunit.units.mega.MegaUnitEntity", true, ml); }

    /** 在已清空的区域里找一块干爽的陆地（单位/炮台都不会被淹死）。 */
    static int[] findLand(){
        for(int y = 45; y < 160; y++){
            for(int x = 30; x < 230; x++){
                boolean ok = true;
                for(int dy = -2; dy <= 2 && ok; dy++) for(int dx = -3; dx <= 3; dx++){
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
            Class<?> mergeCls = Class.forName("combineunit.units.UnitComboMerge", true, ml);

            Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.waves = false;
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.unitCap = 600;
            Vars.logic.play();
            run(20);
            for(int y = 25; y < 175; y++) for(int x = 10; x < 250; x++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
            }
            run(5);
            Building core = place(Blocks.coreShard, 60, 60, Team.sharded);
            if(core != null && core.items != null){
                for(Item it : Vars.content.items()) core.items.set(it, 5000);
            }
            run(10);

            int[] land = findLand();
            int lx = land[0], ly = land[1];
            System.out.println("[MT] 测试场地（陆地）: " + lx + "," + ly);

            // ---------- 1) 合体时吸收脚下的炮台 ----------
            Building duo = place(Blocks.duo, lx, ly, Team.sharded);
            Building scatter = place(Blocks.scatter, lx + 1, ly, Team.sharded);
            Building farDuo = place(Blocks.duo, lx + 40, ly + 40, Team.sharded);   // 远处的，不该被吸收
            check("炮台已放好（duo/scatter/far 各一座）",
                Vars.world.build(lx, ly) != null && Vars.world.build(lx + 1, ly) != null && Vars.world.build(lx + 40, ly + 40) != null);

            // 融合点选在炮台左边 3 格（吸收半径 48px 内），别让成员落在炮台格上
            Unit mega = merge(mergeCls, (lx - 3) * 8f + 4, ly * 8f + 4, UnitTypes.dagger, UnitTypes.dagger);
            check("融合成巨兽", mega != null && memberCount(mega) == 2);
            run(5);
            check("合体吸收了脚下的两座炮台", mega != null && baySize(mega) == 2);
            check("被吸收的炮台从世界里消失", Vars.world.build(lx, ly) == null && Vars.world.build(lx + 1, ly) == null);
            check("远处的炮台没被吸收", Vars.world.build(lx + 40, ly + 40) != null);

            // ---------- 2) 炮台跟着巨兽转 + 从核心扣弹药开火 ----------
            int copperBefore = core.items.get(Items.copper);
            Unit enemy = UnitTypes.dagger.create(Team.crux);
            enemy.set(mega.x + 90f, mega.y);
            enemy.maxHealth(1e9f);
            enemy.health(1e9f);
            enemy.add();
            check("巨兽的炮台在巨兽身上（投影在半径内）", bayInRadius(mega));
            run(180);                                // 让炮台转向+开火几轮
            mega.rotation(mega.rotation() + 90f);    // 巨兽转向：炮台的世界坐标必须跟着转
            run(5);
            check("巨兽转向后炮台仍在巨兽身上（坐标随朝向）", bayInRadius(mega));
            int copperAfter = core.items.get(Items.copper);
            // 用 duo/scatter 自己的弹药弹体类型判定（本版 Bullets 里没有 standardCopper 这类静态字段）
            var duoTurret = (mindustry.world.blocks.defense.turrets.ItemTurret)Blocks.duo;
            var scatterTurret = (mindustry.world.blocks.defense.turrets.ItemTurret)Blocks.scatter;
            Seq<mindustry.entities.bullet.BulletType> ammoBullets = new Seq<>();
            for(mindustry.entities.bullet.BulletType bt : duoTurret.ammoTypes.values()) ammoBullets.add(bt);
            for(mindustry.entities.bullet.BulletType bt : scatterTurret.ammoTypes.values()) ammoBullets.add(bt);
            boolean turretBullet = false;
            // 只认"巨兽附近的"这类子弹（远处没被吸收的那座 duo 射出的不算）
            for(Bullet bl : Groups.bullet){
                if(ammoBullets.indexOf(bl.type, true) != -1 && mega.dst(bl) < 260f){ turretBullet = true; break; }
            }
            System.out.println("[MT] 核心铜库存: " + copperBefore + " → " + copperAfter + "  场上有物品炮台子弹=" + turretBullet + "（弹体总数 " + Groups.bullet.size() + "）");
            check("巨兽的物品炮台真的开火了（世界里有它的子弹）", turretBullet);
            check("物品炮台的子弹从核心里扣（铜库存减少）", copperAfter < copperBefore);

            // ---------- 3) 追加吸收 / 释放 ----------
            int released = (Integer)mergeCls.getMethod("releaseTurrets", megaCls()).invoke(null, mega);
            check("释放全部炮台：放出 2 座", released == 2);
            check("释放后炮台舱空了", !hasTurrets(mega));
            // 放出来的炮台得在真实世界里（巨兽附近）
            Seq<Building> nearby = new Seq<>();
            for(int dy = -8; dy <= 8; dy++) for(int dx = -8; dx <= 8; dx++){
                Building b = Vars.world.build(World.toTile(mega.x) + dx, World.toTile(mega.y) + dy);
                // 多格建筑每个格子都指向同一个 Building，按对象去重
                if(b != null && (b.block == Blocks.duo || b.block == Blocks.scatter) && nearby.indexOf(b, true) == -1) nearby.add(b);
            }
            check("炮台放回了巨兽附近的世界里（找到 " + nearby.size + " 座）", nearby.size == 2);

            int absorbed = (Integer)mergeCls.getMethod("absorbNearbyTurrets", megaCls()).invoke(null, mega);
            check("追加吸收把放出去的炮台又吃回来（≥1）", absorbed >= 1);
            run(3);
            check("追加后炮台舱非空", hasTurrets(mega));

            // ---------- 4) 解体：炮台跟着成员一起放回 ----------
            int before = countBuildings(Team.sharded);
            Object splitOk = mergeCls.getMethod("split", Unit.class).invoke(null, mega);
            check("解体成功", Boolean.TRUE.equals(splitOk));
            run(10);
            check("解体后炮台舱清空", !hasTurrets(mega));
            int after = countBuildings(Team.sharded);
            check("解体后世界里的建筑数量没少（炮台都放回来了，" + before + " → " + after + "）", after >= before);

            // ---------- 5) 快照字节往返：客户端照着 writeSync 重建炮台构成 ----------
            int[] land2 = findLand();
            place(Blocks.duo, land2[0], land2[1], Team.sharded);
            Unit mega2 = merge(mergeCls, (land2[0] - 3) * 8f + 4, land2[1] * 8f + 4, UnitTypes.dagger, UnitTypes.dagger);
            run(2);
            mergeCls.getMethod("absorbNearbyTurrets", megaCls()).invoke(null, mega2);
            run(3);
            int bay2 = baySize(mega2);
            check("第二只巨兽吸收了炮台（快照往返的前提）", bay2 >= 1);

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            arc.util.io.Writes w = new arc.util.io.Writes(new java.io.DataOutputStream(bos));
            mega2.writeSync(w);
            byte[] snap = bos.toByteArray();
            Unit client = (Unit)megaCls().getConstructor().newInstance();
            client.type(UnitTypes.dagger);
            arc.util.io.Reads r = new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(snap)));
            String err = "";
            int got = -1, gotBay = -1;
            try{
                client.readSync(r);
                got = memberCount(client);
                gotBay = baySize(client);
            }catch(Throwable t){ err = String.valueOf(t); }
            System.out.println("[MT] 快照字节=" + snap.length + " 客户端重建成员=" + got + " 炮台=" + gotBay + (err.isEmpty() ? "" : " 失败=" + err));
            check("客户端照快照重建出炮台构成（" + gotBay + "/" + bay2 + "）", gotBay == bay2 && got == 2);

            // ---------- 6) 存档字节往返（write/read，含血量+弹药）----------
            // 【为什么必须单独验】炮台段写在成员体内部（不能挂尾随段，否则老存档多读 1 字节就把
            // 实体流读歪）。存档路径 write/read 和快照路径 writeSync/readSync 是两套调用，得分开压。
            java.io.ByteArrayOutputStream bosSave = new java.io.ByteArrayOutputStream();
            arc.util.io.Writes wSave = new arc.util.io.Writes(new java.io.DataOutputStream(bosSave));
            mega2.write(wSave);
            byte[] saveBytes = bosSave.toByteArray();
            Unit loaded = (Unit)megaCls().getConstructor().newInstance();
            loaded.type(UnitTypes.dagger);
            String errSave = "";
            int gotM = -1, gotB = -1;
            try{
                loaded.read(new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(saveBytes))));
                gotM = memberCount(loaded);
                gotB = baySize(loaded);
            }catch(Throwable t){ errSave = String.valueOf(t); }
            System.out.println("[MT] 存档字节=" + saveBytes.length + " 读回成员=" + gotM + " 炮台=" + gotB + (errSave.isEmpty() ? "" : " 失败=" + errSave));
            check("存档字节往返：成员+炮台都读得回来（" + gotM + " 成员 / " + gotB + " 炮台）", gotM == 2 && gotB == bay2);

            // ---------- 7) 空舱快照往返：没有炮台的巨兽也不能把快照读炸 ----------
            // 先把世界里本方非核心建筑清掉（前面释放/放回留下的炮台会落地，否则巨兽会顺手吸收）
            Seq<Building> wipe = new Seq<>();
            for(Building b : Groups.build) if(b.team == Team.sharded && !(b.block instanceof mindustry.world.blocks.storage.CoreBlock)) wipe.add(b);
            for(Building b : wipe){ if(b.tile != null) b.tile.setBlock(Blocks.air); }
            run(3);
            int[] land3 = findLand();
            Unit mega3 = merge(mergeCls, land3[0] * 8f, land3[1] * 8f, UnitTypes.dagger, UnitTypes.dagger);
            check("第三只巨兽合体成功且是空舱（附近没炮台）", mega3 != null && !hasTurrets(mega3));
            java.io.ByteArrayOutputStream bosEmpty = new java.io.ByteArrayOutputStream();
            arc.util.io.Writes wEmpty = new arc.util.io.Writes(new java.io.DataOutputStream(bosEmpty));
            mega3.writeSync(wEmpty);
            byte[] emptySnap = bosEmpty.toByteArray();
            Unit client3 = (Unit)megaCls().getConstructor().newInstance();
            client3.type(UnitTypes.dagger);
            String errEmpty = "";
            int eM = -1, eB = -1;
            try{
                client3.readSync(new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(emptySnap))));
                eM = memberCount(client3);
                eB = baySize(client3);
            }catch(Throwable t){ errEmpty = String.valueOf(t); }
            System.out.println("[MT] 空舱快照字节=" + emptySnap.length + " 成员=" + eM + " 炮台=" + eB + (errEmpty.isEmpty() ? "" : " 失败=" + errEmpty));
            check("空舱快照往返不炸：成员照旧(2)、炮台=0", eM == 2 && eB == 0);
            try{ mega3.kill(); }catch(Throwable ignored){}
            run(3);

            // ---------- 8) 老存档（发布版自带、**没有炮台段**）照样读得进去 ----------
            // 发布版（9/29）写的是"super + 成员块"，没有炮台段。读端必须容得下"成员体到此为止"：
            // 炮台段读不到就 EOF → 保持空舱；绝不能因为多读 1 个字节把整个实体流读歪。
            try{
                java.io.File f = new java.io.File(dataDir + "/saves/17.msav");
                if(f.exists()){
                    mindustry.io.SaveIO.load(Core.files.absolute(f.getAbsolutePath()));
                    run(3);
                    int beasts = 0;
                    for(Unit u : Groups.unit) if(u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")) beasts++;
                    System.out.println("[MT] 老存档 17.msav 读回巨兽=" + beasts);
                    check("老存档（无炮台段）读得进去且巨兽还在（" + beasts + "）", beasts > 0);
                }else{
                    System.out.println("[MT] 没有 " + f + "，跳过老存档回归");
                }
            }catch(Throwable t){
                System.out.println("[MT] 老存档读取异常: " + t);
                check("老存档（无炮台段）读得进去", false);
            }

            try{ mega2.kill(); }catch(Throwable ignored){}
            run(5);

            System.out.println("[MT] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    static int countBuildings(Team team){
        int n = 0;
        for(Building b : Groups.build) if(b.team == team) n++;
        return n;
    }

    /** 炮台舱里每座炮台的世界坐标都在巨兽 hitSize 的合理范围内（没飞出去）。 */
    static boolean bayInRadius(Unit mega){
        try{
            Object bay = mega.getClass().getMethod("bay").invoke(mega);
            @SuppressWarnings("unchecked")
            Seq<Building> all = (Seq<Building>)bay.getClass().getMethod("all").invoke(bay);
            float maxR = Math.max(mega.hitSize() * 2f + 24f, 48f);
            for(Building b : all){
                if(b == null || Math.abs(b.x - mega.x) > maxR || Math.abs(b.y - mega.y) > maxR) return false;
            }
            return true;
        }catch(Throwable t){ return false; }
    }
}
