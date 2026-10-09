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

    /** 巨兽的炮台舱对象（反射，测试类是另一个类加载器加载的）。 */
    static Object bayOf(Unit u){
        try{
            return u.getClass().getMethod("bay").invoke(u);
        }catch(Throwable t){ return null; }
    }

    /** 设置/取消"某种弹药不用"（炮台舱的 bannedAmmo 集合）。 */
    static void baySetBanned(Unit u, Item item, boolean banned){
        try{
            Object bay = bayOf(u);
            Object set = bay.getClass().getMethod("bannedAmmo").invoke(bay);
            set.getClass().getMethod(banned ? "add" : "remove", Object.class).invoke(set, item);
        }catch(Throwable t){ System.out.println("[MT] baySetBanned 失败: " + t); }
    }

    static boolean bayIsBanned(Unit u, Item item){
        try{
            Object bay = bayOf(u);
            Object set = bay.getClass().getMethod("bannedAmmo").invoke(bay);
            return (Boolean)set.getClass().getMethod("contains", Object.class).invoke(set, item);
        }catch(Throwable t){ return false; }
    }

    static int bayAmmoListSize(Unit u){
        try{
            Object bay = bayOf(u);
            Object list = bay.getClass().getMethod("ammoList").invoke(bay);
            return (Integer)list.getClass().getField("size").get(list);
        }catch(Throwable t){ return -1; }
    }

    /**
     * 炮台相对巨兽的"横向 / 前后"（内部世界口径，和武器同一套）。
     * 巨兽朝向会变，所以按当前 rotation 反变换一次，别假设 rotation=0。
     * @param out out[0]=横向、out[1]=前后
     */
    static void bayInner(Building b, Unit mega, float[] out){
        float dx = b.x - mega.x, dy = b.y - mega.y;
        float a = mega.rotation() - 90f;
        float c = arc.math.Mathf.cosDeg(a), s = arc.math.Mathf.sinDeg(a);
        out[0] = dx * c + dy * s;      // 横向（内部 x）
        out[1] = -dx * s + dy * c;     // 前后（内部 y）
    }

    static final float[] innerTmp = new float[2];

    /** 炮台舱里的炮台列表。 */
    @SuppressWarnings("unchecked")
    static Seq<Building> bayAll(Unit u){
        try{
            Object bay = u.getClass().getMethod("bay").invoke(u);
            return (Seq<Building>)bay.getClass().getMethod("all").invoke(bay);
        }catch(Throwable t){ return new Seq<>(); }
    }

    /** 点某座炮台的"添加"：手动吸收**指定格**上的那一座（选取炮台那条路）。 */
    static boolean absorbTurretAt(Class<?> mergeCls, Unit mega, int tx, int ty){
        try{
            return (Boolean)mergeCls.getMethod("absorbTurretAt", megaCls(), int.class, int.class).invoke(null, mega, tx, ty);
        }catch(Throwable t){ System.out.println("[MT] absorbTurretAt 失败: " + t); return false; }
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
            // 【用 4 只 dagger】舱位上限 = 成员武器数之和（用户 2026-10-08 口径）：
            // 4 只 dagger = 4 把武器 → 最多 4 座炮台，下面要吸 3~4 座刚好在限内。
            Unit mega = merge(mergeCls, (lx - 3) * 8f + 4, ly * 8f + 4,
                UnitTypes.dagger, UnitTypes.dagger, UnitTypes.dagger, UnitTypes.dagger);
            check("融合成巨兽（4 名成员）", mega != null && memberCount(mega) == 4);
            Object bay0 = megaCls().getMethod("bay").invoke(mega);
            int cap0 = (Integer)bay0.getClass().getMethod("maxTurrets").invoke(bay0);
            int mounts0 = mega.mounts().length;
            check("舱位上限 = 成员武器数之和（mounts=" + mounts0 + " → 上限=" + cap0
                + "，够下面吸 4 座）", cap0 == mounts0 && cap0 >= 4);
            run(5);
            // 【用户要求：改成手动选取】合体本身不再自动吸走脚下的炮台
            check("合体不再自动吸收炮台（炮台舱 0 座）", mega != null && baySize(mega) == 0);
            check("没被点的炮台都还在世界里", Vars.world.build(lx, ly) != null && Vars.world.build(lx + 1, ly) != null);
            check("远处的炮台没被吸收", Vars.world.build(lx + 40, ly + 40) != null);

            // ---------- 1b) 手动"添加"：点哪座吸哪座 ----------
            check("点第一座炮台的\"添加\"：那一座被吸进去",
                absorbTurretAt(mergeCls, mega, lx, ly) && baySize(mega) == 1);
            check("没点的那座还在世界里", Vars.world.build(lx + 1, ly) != null);
            check("再点第二座：也吸进去", absorbTurretAt(mergeCls, mega, lx + 1, ly) && baySize(mega) == 2);
            check("被吸收的炮台从世界里消失", Vars.world.build(lx, ly) == null && Vars.world.build(lx + 1, ly) == null);
            check("半径外的炮台点不动（服务端重新校验）", !absorbTurretAt(mergeCls, mega, lx + 40, ly + 40));

            // ---------- 1c) 炮台布局：偶数分两边、奇数多的一座在中间（和武器同一套口径） ----------
            Building wave = place(Blocks.wave, lx, ly + 2, Team.sharded);   // 2x2，得在吸收半径内
            run(2);
            check("第三座炮台（wave）摆好了", wave != null);
            check("点第三座：也吸进去（现在 3 座 = 奇数）",
                absorbTurretAt(mergeCls, mega, lx, ly + 2) && baySize(mega) == 3);
            mega.rotation(0f);
            run(3);
            // rotation = 0 时投影是 world = (ly, -lx)：横向 = -(b.y - mega.y)，前后 = (b.x - mega.x)
            // 【环形（用户 2026-10-08："两边的位置和武器一样是环形的"）】两侧的炮台到巨兽中心
            // 的距离应当基本一致（= 圆环半径）、左右互为镜像，而不是挤在左右两根竖列上。
            Seq<Float> rad3 = new Seq<>();
            int atCenter = 0;
            for(Building b : bayAll(mega)){
                bayInner(b, mega, innerTmp);
                float rr = (float)Math.hypot(innerTmp[0], innerTmp[1]);
                if(rr <= 8f) atCenter++; else rad3.add(rr);
            }
            rad3.sort();
            System.out.println("[MT] 3 座炮台: 到中心距离 " + rad3 + "（另有 " + atCenter + " 座在正中间）");
            check("奇数 3 座：1 座在正中间、两侧各 1 座", atCenter == 1 && rad3.size == 2);
            check("两侧炮台落在同一个圆环上（半径 " + rad3 + "，极差 ≤ 24px）",
                rad3.size == 2 && rad3.peek() - rad3.first() <= 24f);
            check("两侧是左右镜像（横向相反、前后相同）", mirrored(bayAll(mega), mega));

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

            // ---------- 2b) 玩家操控巨兽时，舱里的炮台听玩家的鼠标（转向 + 开火）----------
            // 用户问："为什么不能控制炮台转向和开火？" —— 原版 TurretBuild.updateTile() 见
            // unit.controller() 是 Player 就走玩家分支（瞄 unit.aimX/aimY、开火 unit.isShooting()），
            // 所以舱里的炮台也照这套接上巨兽的操控者（见 MegaTurretBay.driveByPlayer）。
            Player pilot = Player.create();
            pilot.name = "pilot";
            pilot.team(Team.sharded);
            pilot.add();
            pilot.unit(mega);
            run(3);
            Building warm = null;
            for(Building b : bayAll(mega)){
                if(b.block == Blocks.duo && b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild){ warm = b; break; }
            }
            check("炮台舱里有 duo 物品炮台（前置）", warm != null);
            int shotsBefore = warm == null ? -1 : ((mindustry.world.blocks.defense.turrets.Turret.TurretBuild)warm).totalShots;
            // 玩家朝"正上"（-90°）瞄 + 按住开火；mega.aimX/aimY 就是 DesktopInput 每帧写进去的那个。
            // 选正上是为了和"炮台自己的 AI 目标（section 2 那只在巨兽右边 90px 的敌人）"区分开 ——
            // 只看开火数分不清是玩家在开火还是 AI 在开火，看朝向才分得清。
            final float aimX = mega.x, aimY = mega.y - 300f;
            for(int i = 0; i < 150; i++){
                mega.aim(aimX, aimY, true);
                pilot.shooting = true;
                run(1);
            }
            float tRot = warm == null ? -999f : ((mindustry.world.blocks.defense.turrets.Turret.TurretBuild)warm).rotation;
            int shotsAfter = warm == null ? -1 : ((mindustry.world.blocks.defense.turrets.Turret.TurretBuild)warm).totalShots;
            float want = warm == null ? -999f : arc.math.Angles.angle(warm.x, warm.y, aimX, aimY);
            System.out.println("[MT] 玩家操控炮台: duo 朝向=" + tRot + "°（指到玩家鼠标应≈" + want
                + "°；自己的 AI 会指向右边 90px 的敌人 ≈" + (warm == null ? 0 : (int)arc.math.Angles.angle(warm.x, warm.y, mega.x + 90f, mega.y)) + "°）"
                + " 开火 " + shotsBefore + " → " + shotsAfter);
            check("炮台跟着玩家鼠标转向（朝向 " + (int)tRot + "° 应≈玩家鼠标方向 " + (int)want + "°）",
                warm != null && arc.math.Angles.angleDist(tRot, want) < 12f);
            check("炮台按玩家的开火键开火（" + shotsBefore + " → " + shotsAfter + "）", shotsAfter > shotsBefore);
            // 玩家松手：炮台回到自己的 AI
            pilot.shooting = false;
            pilot.unit(null);
            pilot.remove();
            run(10);
            boolean stillPlayer = warm != null
                && ((mindustry.world.blocks.defense.turrets.Turret.TurretBuild)warm).unit.controller() instanceof Player;
            check("玩家松手后炮台回到自己的 AI（不再挂在玩家控制器上）", !stillPlayer);
            // 放一只**正下方**的新敌人（玩家刚才瞄的是正上方，方向正好相反，能分得清是谁在控制）
            Unit enemy2 = UnitTypes.dagger.create(Team.crux);
            enemy2.set(mega.x, mega.y + 120f);
            enemy2.maxHealth(1e9f);
            enemy2.health(1e9f);
            enemy2.add();
            run(200);   // 逻辑控制 2 秒后过期，炮台应当自己转回去打它自己的 AI 目标
            var wtb = warm == null ? null : (mindustry.world.blocks.defense.turrets.Turret.TurretBuild)warm;
            float back = wtb == null ? -999f : wtb.rotation;
            float want2 = wtb == null ? -999f : arc.math.Angles.angle(wtb.x, wtb.y, enemy2.x, enemy2.y);
            System.out.println("[MT] 松手 2 秒后: duo 朝向=" + back + "°（自己的 AI 目标 ≈" + want2 + "°）"
                + " logicControlTime=" + (wtb == null ? -1 : wtb.logicControlTime)
                + " target=" + (wtb == null || wtb.target == null ? "null" : wtb.target.getClass().getSimpleName()));
            check("玩家松手 2 秒后炮台回到自己的 AI 索敌（朝向 " + (int)back + "° 应≈" + (int)want2 + "°）",
                wtb != null && arc.math.Angles.angleDist(back, want2) < 15f);

            // ---------- 2c) 弹药：按弹药表循环 / 核心没货就不打 / 面板可禁用 ----------
            // 用户 2026-10-08 口径："发射的弹药为其弹药列表循环发射，当然如果核心没有对应物品就不发射了，
            // 在解体界面可以选择哪些弹药不被使用。"
            {
                mindustry.world.blocks.defense.turrets.ItemTurret duoT =
                    (mindustry.world.blocks.defense.turrets.ItemTurret)Blocks.duo;
                Building duoB = null;
                for(Building b : bayAll(mega)){
                    if(b.block == Blocks.duo && b instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild){
                        duoB = b;
                        break;
                    }
                }
                check("炮台舱里有 duo 物品炮台（弹药循环的前置）", duoB != null);
                Seq<Item> keys = duoT.ammoTypes.keys().toSeq();
                check("duo 的弹药表 ≥2 种（才谈得上循环）", keys.size >= 2);
                var itb = (mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild)duoB;
                Item am1 = keys.get(0), am2 = keys.get(1);

                // ① 核心两种都有 → 循环喂出来的弹仓里两种都出现
                core.items.set(am1, 5000);
                core.items.set(am2, 5000);
                itb.ammo.clear(); itb.totalAmmo = 0;
                run(60);
                Seq<Item> fed = new Seq<>();
                for(var e : itb.ammo) fed.add(((mindustry.world.blocks.defense.turrets.ItemTurret.ItemEntry)e).item);
                System.out.println("[MT] 弹药循环: duo 弹药表=" + keys + " 弹仓里=" + fed);
                check("核心有两种时按弹药表循环（弹仓里两种都出现）", fed.contains(am1, true) && fed.contains(am2, true));

                // ② 核心只剩一种 → 只补那一种
                core.items.set(am2, 0);
                itb.ammo.clear(); itb.totalAmmo = 0;
                run(60);
                fed.clear();
                for(var e : itb.ammo) fed.add(((mindustry.world.blocks.defense.turrets.ItemTurret.ItemEntry)e).item);
                check("核心只有一种时只补那一种（" + fed + "）", fed.contains(am1, true) && !fed.contains(am2, true));

                // ③ 核心里 duo 弹药表的所有物品都没有 → 一颗都不补（自然打不出去）
                for(Item k : keys) core.items.set(k, 0);
                itb.ammo.clear(); itb.totalAmmo = 0;
                run(60);
                System.out.println("[MT] 核心没货时 duo 弹仓=" + itb.totalAmmo + " 发");
                check("核心没有对应物品就不发射（一颗都不补）", itb.totalAmmo == 0);

                // ④ 面板里勾掉一种 → 那种不再被喂
                core.items.set(am1, 5000);
                core.items.set(am2, 5000);
                baySetBanned(mega, am1, true);
                itb.ammo.clear(); itb.totalAmmo = 0;
                run(60);
                fed.clear();
                for(var e : itb.ammo) fed.add(((mindustry.world.blocks.defense.turrets.ItemTurret.ItemEntry)e).item);
                System.out.println("[MT] 勾掉 " + am1.localizedName + " 之后 duo 弹仓=" + fed);
                check("面板勾掉的弹药不再被喂（" + fed + "）", !fed.contains(am1, true) && fed.contains(am2, true));
                check("弹药禁用表能被面板读到（ammoList ≥2 种、banned 含勾掉的那种）",
                    bayAmmoListSize(mega) >= 2 && bayIsBanned(mega, am1));
                baySetBanned(mega, am1, false);
                run(5);
            }

            // ---------- 2d) 联机包（MegaOrderPacket）字节往返：客户端发"吸收某一格"那条路 ----------
            // 【为什么要单独钉】"点添加"在单机走的是本地 absorbTurretAt，联机才走 MegaOrderPacket；
            // 包格式改过（多了 absorbTurretAt/turretX/turretY 三个字段），写读不对齐的话
            // 客户端发的包服务端会读歪（表现为"点了没反应"甚至走错分支）。
            try{
                Class<?> pkt = Class.forName("combineunit.units.MegaOrderPacket", true, ml);
                Object p = pkt.getMethod("turretAt", Unit.class, int.class, int.class).invoke(null, mega, lx + 1, ly + 2);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                arc.util.io.Writes w = new arc.util.io.Writes(new java.io.DataOutputStream(bos));
                pkt.getMethod("write", arc.util.io.Writes.class).invoke(p, w);
                byte[] bytes = bos.toByteArray();
                Object backPkt = pkt.getConstructor().newInstance();
                pkt.getMethod("read", arc.util.io.Reads.class).invoke(backPkt,
                    new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))));
                boolean at = (Boolean)pkt.getField("absorbTurretAt").get(backPkt);
                int bx = (Integer)pkt.getField("turretX").get(backPkt), by = (Integer)pkt.getField("turretY").get(backPkt);
                int uid = (Integer)pkt.getField("unitId").get(backPkt);
                System.out.println("[MT] MegaOrderPacket turretAt 字节=" + bytes.length
                    + " 读回 absorbTurretAt=" + at + " 目标格=" + bx + "," + by + " unitId=" + uid);
                check("MegaOrderPacket.turretAt 写读一致（" + bytes.length + " 字节：格 " + bx + "," + by + " / unitId " + uid + "）",
                    at && bx == lx + 1 && by == ly + 2 && uid == mega.id());
            }catch(Throwable t){
                System.out.println("[MT] MegaOrderPacket 往返失败: " + t);
                check("MegaOrderPacket.turretAt 写读一致", false);
            }

            // ---------- 3) 追加吸收 / 释放 ----------
            int released = (Integer)mergeCls.getMethod("releaseTurrets", megaCls()).invoke(null, mega);
            check("释放全部炮台：放出 3 座", released == 3);
            check("释放后炮台舱空了", !hasTurrets(mega));
            // 放出来的炮台得在真实世界里（巨兽附近）
            Seq<Building> nearby = new Seq<>();
            for(int dy = -8; dy <= 8; dy++) for(int dx = -8; dx <= 8; dx++){
                Building b = Vars.world.build(World.toTile(mega.x) + dx, World.toTile(mega.y) + dy);
                // 多格建筑每个格子都指向同一个 Building，按对象去重
                if(b != null && (b.block == Blocks.duo || b.block == Blocks.scatter || b.block == Blocks.wave) && nearby.indexOf(b, true) == -1) nearby.add(b);
            }
            check("炮台放回了巨兽附近的世界里（找到 " + nearby.size + " 座）", nearby.size == 3);

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
            // 先把前面释放/放回留下的炮台清掉：这一节要的炮台构成就是下面这 4 座 duo
            Seq<Building> wipe0 = new Seq<>();
            for(Building b : Groups.build) if(b.team == Team.sharded && !(b.block instanceof mindustry.world.blocks.storage.CoreBlock)) wipe0.add(b);
            for(Building b : wipe0){ if(b.tile != null) b.tile.setBlock(Blocks.air); }
            run(3);
            int[] land2 = findLand();
            // 四座**同尺寸**炮台（duo）：偶数的镜像关系可以逐像素核对
            place(Blocks.duo, land2[0], land2[1], Team.sharded);
            place(Blocks.duo, land2[0] + 1, land2[1], Team.sharded);
            place(Blocks.duo, land2[0], land2[1] + 2, Team.sharded);
            place(Blocks.duo, land2[0] + 1, land2[1] + 2, Team.sharded);
            // 同样是"成员武器数 = 4"的编组（4 只 dagger），下面要吸 4 座炮台
            Unit mega2 = merge(mergeCls, (land2[0] - 3) * 8f + 4, land2[1] * 8f + 4,
                UnitTypes.dagger, UnitTypes.dagger, UnitTypes.dagger, UnitTypes.dagger);
            run(2);
            mergeCls.getMethod("absorbNearbyTurrets", megaCls()).invoke(null, mega2);
            run(3);
            int bay2 = baySize(mega2);
            check("第二只巨兽吸收了 4 座炮台（快照往返的前提）", bay2 == 4);

            // ---------- 5b) 偶数 4 座：分成两边、都在同一个圆环上，左右严格镜像，没人留在中间 ----------
            mega2.rotation(0f);
            run(3);
            Seq<Float> rad2 = new Seq<>();
            for(Building b : bayAll(mega2)){
                bayInner(b, mega2, innerTmp);
                rad2.add((float)Math.hypot(innerTmp[0], innerTmp[1]));
            }
            rad2.sort();
            System.out.println("[MT] 4 座同尺寸炮台到巨兽中心的距离(px): " + rad2);
            check("偶数 4 座：没人留在正中间（都离中心 " + (int)(float)rad2.first() + "px+）", rad2.first() >= 8f);
            check("偶数 4 座：都落在同一个圆环上（半径 " + rad2 + "，极差 ≤ 16px）",
                rad2.peek() - rad2.first() <= 16f);
            check("偶数 4 座：左右严格镜像（横向相反、前后相同）", mirrored(bayAll(mega2), mega2));

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
            check("客户端照快照重建出炮台构成（" + gotBay + "/" + bay2 + "）", gotBay == bay2 && got == 4);

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
            check("存档字节往返：成员+炮台都读得回来（" + gotM + " 成员 / " + gotB + " 炮台）", gotM == 4 && gotB == bay2);

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

            // ---------- 7b) 舱位上限 = 成员武器数之和：2 只 dagger → 只能带 2 座炮台 ----------
            // （用户 2026-10-08 口径："把最大舱位调成组合巨兽里所有单位的武器和"）
            {
                int[] land4 = findLand();
                Unit mega4 = merge(mergeCls, land4[0] * 8f, land4[1] * 8f, UnitTypes.dagger, UnitTypes.dagger);
                check("第四只巨兽（2 只 dagger）合体成功", mega4 != null && memberCount(mega4) == 2);
                Object bay4 = megaCls().getMethod("bay").invoke(mega4);
                int cap4 = (Integer)bay4.getClass().getMethod("maxTurrets").invoke(bay4);
                int mounts4 = mega4.mounts().length;
                check("舱位上限 = 成员武器数之和（这具巨兽 mounts=" + mounts4 + "，上限=" + cap4 + "）", cap4 == mounts4);
                int[][] spots = {{1, 0}, {2, 0}, {3, 0}, {2, 1}, {3, 1}};
                for(int[] sp : spots) place(Blocks.duo, land4[0] + sp[0], land4[1] + sp[1], Team.sharded);
                run(3);
                boolean[] ok = new boolean[spots.length];
                for(int i = 0; i < spots.length; i++){
                    ok[i] = absorbTurretAt(mergeCls, mega4, land4[0] + spots[i][0], land4[1] + spots[i][1]);
                }
                run(3);
                int gotCap = 0;
                for(boolean b : ok) if(b) gotCap++;
                System.out.println("[MT] 舱位上限实测: 逐座点 " + spots.length + " 座 → 吸进去 " + gotCap
                    + " 座（上限 " + cap4 + "），炮台舱=" + baySize(mega4) + " 座，最后一座还在世界="
                    + (Vars.world.build(land4[0] + spots[spots.length - 1][0], land4[1] + spots[spots.length - 1][1]) != null));
                check("上限内能吸（" + cap4 + " 座都进去了）", gotCap == cap4 && baySize(mega4) == cap4);
                check("超过上限就吸不进去（第 " + (cap4 + 1) + " 座返回 false、炮台舱仍是 " + cap4 + "）",
                    !ok[spots.length - 1] && baySize(mega4) == cap4);
                check("吸不进去的那座还留在世界里",
                    Vars.world.build(land4[0] + spots[spots.length - 1][0], land4[1] + spots[spots.length - 1][1]) != null);
                try{ mega4.kill(); }catch(Throwable ignored){}
                run(3);
            }

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

    /**
     * 舱里"不在正中间"的炮台是不是左右成对镜像（横向相反、前后相同）。
     * 巨兽 rotation 必须是 0（此时 横向 = -(b.y - mega.y)、前后 = b.x - mega.x）。
     */
    static boolean mirrored(Seq<Building> all, Unit mega){
        Seq<Building> side = new Seq<>();
        for(Building b : all){
            bayInner(b, mega, innerTmp);
            if(Math.hypot(innerTmp[0], innerTmp[1]) > 8f) side.add(b);
        }
        if(side.size == 0) return true;
        boolean[] used = new boolean[side.size];
        int pairs = 0;
        for(int i = 0; i < side.size; i++){
            if(used[i]) continue;
            bayInner(side.get(i), mega, innerTmp);
            float li = innerTmp[0], fi = innerTmp[1];
            for(int k = i + 1; k < side.size; k++){
                if(used[k]) continue;
                bayInner(side.get(k), mega, innerTmp);
                float lk = innerTmp[0], fk = innerTmp[1];
                // 容差 4.5px（半格）：偶数尺寸方块的"中心"落在两格之间，混编（1x1 配 2x2）
                // 时按格对齐最多差半格，这是格子的物理下限，不是不对称。
                if(Math.abs(li + lk) <= 4.5f && Math.abs(fi - fk) <= 4.5f){
                    used[i] = used[k] = true;
                    pairs++;
                    break;
                }
            }
        }
        return pairs * 2 == side.size;
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
