package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 联机同步回归（用户报：<b>"多人游戏时客户端让组合巨兽和炮台合体后炮台还会留在原地"</b>）。
 *
 * <p>根因：巨兽炮台舱吸收/放回炮台时走的是**裸 {@code Tile.setBlock}** —— 它只改服务端本地，
 * 客户端那份世界收不到任何通知。于是客户端上那座炮台既留在原地、又出现在巨兽体内。
 * 正确做法是走原版的**跨网络同步**接口：吸收用 {@code Tile.removeNet()}（= {@code Call.removeTile}
 * → {@code RemoveTileCallPacket}），放回用 {@code Call.constructFinish}（→ {@code ConstructFinishCallPacket}）——
 * 这两个接口在服务端会本地改 + 广播给所有客户端（见原版 {@code Tile} 的 setNet/removeNet 注释）。
 *
 * <p>判定：把 {@code Vars.net} 换成一个"自称服务端、只把发出去的包记下来"的探针（{@link SpyNet}），
 * 吸收一座炮台后必须发出过 <b>RemoveTileCallPacket</b>（且格子对得上），放回后必须发出过
 * <b>ConstructFinishCallPacket</b>。用裸 setBlock 的旧代码两个包都不发，本测试两条都会 FAIL。
 *
 * <p>在只有 combineunit 的数据目录里跑（见 verify/make-dataset.sh）。
 */
public class MegaTurretSyncTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_unit/data";
    static int pass = 0, fail = 0;
    static ClassLoader ml;
    static SpyNet spy;

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new Platform(){};
        spy = new SpyNet(Vars.platform.getNet());
        Vars.net = spy;
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MTS] " + t); };
        new HeadlessApplication(new MegaTurretSyncTest(), t -> t.printStackTrace());
    }

    /** 自称"服务端"、把发出的包记下来的 Net 探针（其余照旧交给真 provider，不真的建连接）。 */
    static class SpyNet extends Net{
        final Seq<Object> sent = new Seq<>();
        SpyNet(NetProvider provider){ super(provider); }
        @Override public boolean server(){ return true; }
        @Override public boolean client(){ return false; }
        @Override public boolean active(){ return true; }
        @Override public void send(Object object, boolean reliable){ sent.add(object); }
        @Override public void send(Object object, Iterable<mindustry.net.NetConnection> connections, boolean reliable){ sent.add(object); }
    }

    static void check(String n, boolean ok){ System.out.println("[MTS] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }

    /** 发出去的包里第一个指定类型的（没有就 null）。 */
    static <T> T sentOf(Class<T> type){
        for(Object o : spy.sent) if(type.isInstance(o)) return type.cast(o);
        return null;
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

    static Class<?> megaCls() throws Throwable{ return Class.forName("combineunit.units.mega.MegaUnitEntity", true, ml); }

    static int memberCount(Unit u){ try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; } }
    static int baySize(Unit u){
        try{ Object bay = u.getClass().getMethod("bay").invoke(u); return (Integer)bay.getClass().getMethod("size").invoke(bay); }
        catch(Throwable t){ return -1; }
    }

    /** 点某座炮台的"添加"：手动吸收**指定格**上的那一座（选取炮台那条路）。 */
    static boolean absorbTurretAt(Class<?> mergeCls, Unit mega, int tx, int ty){
        try{
            return (Boolean)mergeCls.getMethod("absorbTurretAt", megaCls(), int.class, int.class).invoke(null, mega, tx, ty);
        }catch(Throwable t){ System.out.println("[MTS] absorbTurretAt 失败: " + t); return false; }
    }

    static Unit merge(Class<?> mergeCls, float x, float y, UnitType... types){
        try{
            Seq<Unit> units = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = types[i].create(Team.sharded);
                u.set(x, y + i * 16f);
                u.add();
                units.add(u);
            }
            run(10);
            return (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
        }catch(Throwable t){ System.out.println("[MTS] 融合失败: " + t); return null; }
    }

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
            System.out.println("[MTS] 测试场地（陆地）: " + lx + "," + ly + "  net.server=" + Vars.net.server() + " client=" + Vars.net.client());

            Building duo = place(Blocks.duo, lx, ly, Team.sharded);
            check("炮台放好（duo）", duo != null && Vars.world.build(lx, ly) != null);

            Unit mega = merge(mergeCls, (lx - 3) * 8f + 4, ly * 8f + 4,
                UnitTypes.dagger, UnitTypes.dagger, UnitTypes.dagger, UnitTypes.dagger);
            check("融合成巨兽（4 名成员）", mega != null && memberCount(mega) == 4);
            run(5);

            // ---------- 吸收：必须发 RemoveTileCallPacket ----------
            spy.sent.clear();
            boolean absorbed = absorbTurretAt(mergeCls, mega, lx, ly);
            check("点\"添加\"：那一座被吸进炮台舱", absorbed && baySize(mega) == 1);
            check("服务端世界里那座炮台没了", Vars.world.build(lx, ly) == null);
            RemoveTileCallPacket rm = sentOf(RemoveTileCallPacket.class);
            check("吸收走了跨网络同步接口 → 发出 RemoveTileCallPacket（客户端才会跟着删）",
                rm != null && rm.tile != null && rm.tile.x == lx && rm.tile.y == ly);

            // 记下被吸收的那座（放回要在服务端保留它的状态）
            Building inside = null;
            try{
                Object bay = mega.getClass().getMethod("bay").invoke(mega);
                Seq<Building> all = (Seq<Building>)bay.getClass().getMethod("all").invoke(bay);
                inside = all.isEmpty() ? null : all.first();
            }catch(Throwable t){ System.out.println("[MTS] 读炮台舱失败: " + t); }
            check("炮台舱里是原来那座实例（状态保留的前提）", inside == duo);

            // ---------- 放回：必须发 ConstructFinishCallPacket ----------
            spy.sent.clear();
            int released = (Integer)mergeCls.getMethod("releaseTurrets", megaCls()).invoke(null, mega);
            check("释放全部炮台：放出 1 座", released == 1);
            ConstructFinishCallPacket cf = sentOf(ConstructFinishCallPacket.class);
            check("放回走了跨网络同步接口 → 发出 ConstructFinishCallPacket（客户端才会看到炮台回来）",
                cf != null && cf.tile != null && cf.block == Blocks.duo);
            check("放回后世界里又有那座炮台", cf != null && cf.tile != null && Vars.world.build(cf.tile.x, cf.tile.y) != null);
            check("服务端放回的是原来那座实例（血量/弹药等状态没丢）",
                cf != null && cf.tile != null && Vars.world.build(cf.tile.x, cf.tile.y) == duo);

            System.out.println("[MTS] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + " fail=" + fail + ")");
            System.exit(fail == 0 ? 0 : 1);
        }catch(Throwable t){
            System.out.println("[MTS] 崩了: " + t);
            t.printStackTrace();
            System.exit(2);
        }
    }
}
