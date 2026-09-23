package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import arc.util.io.Writes;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："组合成功后，这个大单位在**房间成员**的画面上直接消失（不可见、不可选中、不可驾驶），
 * 只有房主能看到；成员单位已消耗，无法撤销。临界条件：**成员数量 &gt; 18**。而且约 10 分钟后
 * 合体单位在**服务端**也消失。"
 *
 * <p>本测试要量清楚三件事（都按联机真实路径）：
 * <ol>
 *   <li><b>快照多大</b>：服务端每个实体发的是 `id(4) + classId(1) + writeSync(...)`
 *       （见 `NetServer.writeEntity`），实体总量超过 **800 字节**就会单独刷一包；
 *       arc 客户端的写缓冲是 **16384 字节**（`new Client(16384, 25000, …)`），
 *       超过就 BufferOverflow → `Net.showError` → **这一包被丢掉**（客户端看不到实体）。
 *       这里按同样的字节布局量不同成员数下的包大小。</li>
 *   <li><b>客户端能不能读回来</b>：按 NetClient 的路子（`EntityMapping` 新建实例 + `readSync`）
 *       在"另一个进程/另一套实体映射"的语境下把快照读一遍，成员数/代表类型必须一致
 *       （读崩了客户端会把整个快照包后面的实体一起丢）。</li>
 *   <li><b>长时间存活</b>：20 个成员的巨兽跑满 40000 tick（≈11 分钟游戏时间），
 *       巨兽必须还在（服务端视角），期间记下是否有消失/掉队。</li>
 * </ol>
 */
public class MegaBigTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaBigTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MB] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
    }
    /** 服务端发实体的字节布局：id(4) + classId(1) + writeSync 的字节数。 */
    static int snapshotBytes(Unit u){
        try{
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
            dos.writeInt(u.id());
            dos.writeByte(u.classId() & 0xFF);
            Writes w = new Writes(dos);
            u.beforeWrite();
            u.writeSync(w);
            dos.flush();
            return bos.size();
        }catch(Throwable t){ System.out.println("[MB] 序列化失败: " + t); return -1; }
    }
    /** 像客户端那样把快照读回来（新实例 + readSync），返回读回来的成员数（-1 = 读崩）。 */
    static int clientReadback(Unit u){
        try{
            byte[] data;
            {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
                dos.writeInt(u.id());
                dos.writeByte(u.classId() & 0xFF);
                Writes w = new Writes(dos);
                u.beforeWrite();
                u.writeSync(w);
                dos.flush();
                data = bos.toByteArray();
            }
            java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
            int id = dis.readInt();
            int classId = dis.readByte() & 0xFF;
            arc.func.Prov<?> prov = EntityMapping.map(classId);
            if(prov == null) return -1;
            Object o = prov.get();
            if(!(o instanceof Unit copy)) return -1;
            copy.id(id);
            copy.team(Team.sharded);   // 真实客户端读快照时队伍由快照上下文给出
            copy.readSync(new arc.util.io.Reads(dis));
            copy.afterSync();
            int mc = -1;
            try{ mc = (Integer)copy.getClass().getMethod("memberCount").invoke(copy); }catch(Throwable ignored){}
            return mc;
        }catch(Throwable t){ System.out.println("[MB] 客户端读快照崩了: " + t); return -1; }
    }
    /** 造 n 个成员并融合，返回巨兽。 */
    static Unit mergeN(float x, float y, int n){
        try{
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < n; i++){
                Unit u = UnitTypes.dagger.create(Team.sharded);
                // 摆紧凑一点：散开的话后排会掉进深水淹死（可编组数量对不上）
                u.set(x + (i % 5) * 8f, y + (i / 5) * 8f);
                u.add();
                us.add(u);
            }
            run(3);
            int alive = 0;
            try{
                Class<?> dmg = Class.forName("combineunit.units.UnitComboDamage", true, ml);
                java.lang.reflect.Method grp = dmg.getMethod("groupable", Unit.class);
                for(int i = 0; i < us.size; i++){
                    Unit u = us.get(i);
                    boolean g = (Boolean)grp.invoke(null, u);
                    if(g) alive++;
                    else System.out.println("[MB]   第 " + i + " 只不可编组: 已加=" + u.isAdded() + " 有效=" + u.isValid()
                        + " 死亡=" + u.dead() + " hittable=" + u.hittable() + " 血=" + (int)u.health()
                        + " 位置=" + (int)u.x + "," + (int)u.y
                        + " 地形=" + (Vars.world.tileWorld(u.x, u.y) == null ? "?" : Vars.world.tileWorld(u.x, u.y).floor().name)
                        + " 在unit组=" + Groups.unit.contains(o -> o == u));
                }
            }catch(Throwable t){ System.out.println("[MB]   诊断失败: " + t); }
            System.out.println("[MB]   （造出 " + n + " 只，融合前可编组 " + alive + " 只）");
            Object mega = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("mergeSelected", Seq.class).invoke(null, us);
            run(3);
            return mega instanceof Unit mu ? mu : null;
        }catch(Throwable t){ System.out.println("[MB] 融合 " + n + " 只失败: " + t); return null; }
    }
    /** 巨兽的成员武器总数（客户端要照着建挂载）。 */
    static int mountCount(Unit u){
        return u.mounts() == null ? -1 : u.mounts().length;
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

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.waves = false;
        // 【测试前提】关掉单位上限：Gamemode.survival 的 rules.unitCap 默认只有 8，
        // 造第 9 只就会被 Call.unitCapDeath 干掉（表现为"可编组 8 只"）——
        // 那是测试场景的锅，不是模组 bug。真实战场里玩家单位上限通常远高于此。
        Vars.state.rules.disableUnitCap = true;
        System.out.println("[MB] rules.unitCap=" + Vars.state.rules.unitCap
            + " disableUnitCap=" + Vars.state.rules.disableUnitCap);
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(10);
        int ox=-1, oy=-1;
        outer:
        for(int y=45;y<150;y++) for(int x=40;x<220;x++){
            boolean ok = true;
            for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-4;dx<=4;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ System.out.println("[MB] 没找到陆地"); System.exit(3); }
        place(Blocks.coreShard, ox + 30, oy + 16, Team.sharded);
        run(10);
        float cx = ox * 8f, cy = oy * 8f;

        // ---------- ① 不同成员数下的快照包大小 + 客户端读回 ----------
        System.out.println("[MB] 服务端单实体快照 = id(4)+classId(1)+writeSync；800 = 分批阈值，16384 = arc 客户端写缓冲上限");
        for(int n : new int[]{2, 8, 18, 20, 24}){
            Unit beast = mergeN(cx, cy, n);
            if(beast == null){ check("融合 " + n + " 只（前置）", false); continue; }
            int bytes = snapshotBytes(beast);
            int readback = clientReadback(beast);
            int actual = -1;
            try{ actual = (Integer)beast.getClass().getMethod("memberCount").invoke(beast); }catch(Throwable ignored){}
            System.out.println("[MB] 成员 " + n + ": 实际成员=" + actual + "、快照 " + bytes + " 字节、挂载 "
                + mountCount(beast) + " 个、客户端读回成员数=" + readback + "（应 = " + n + "）");
            check("成员 " + n + " 的快照能装进一包（< 16384 字节；实测 " + bytes + "）", bytes > 0 && bytes < 16384);
            // 派生的巨兽类型必须不吃单位上限：否则队伍人数一多，引擎的 Call.unitCapDeath
            // 会把巨兽清掉（"过一会儿大单位自己没了"的另一个可疑来源）
            check("成员 " + n + " 的巨兽类型不占单位上限（useUnitCap=false）", !beast.type.useUnitCap);
            check("成员 " + n + " 的快照客户端能原样读回（实测 " + readback + "）", readback == n);
            beast.remove();
            run(5);
        }

        // ---------- ② 20 个成员的巨兽跑 40000 tick（≈11 分钟）看会不会消失 ----------
        Unit big = mergeN(cx, cy, 20);
        if(big == null) check("20 只融合成巨兽（前置）", false);
        else{
            check("20 只融合成巨兽（前置）", true);
            int id = big.id();
            boolean lost = false;
            for(int i = 0; i < 40000; i++){
                run(1);
                if((i % 4000) == 0){
                    boolean in = Groups.unit.contains(u -> u.id == id);
                    System.out.println("[MB]   tick " + i + ": 在=" + in + " 死亡=" + big.dead
                        + " 已加=" + big.isAdded() + " 血=" + (int)big.health() + "/" + (int)big.maxHealth()
                        + " 位置=" + (int)big.x + "," + (int)big.y);
                    if(!in){ lost = true; break; }
                }
            }
            check("20 成员的巨兽跑 40000 tick 后仍在世界里（服务端视角）", !lost && !big.dead && big.isAdded());
        }

        System.out.println("[MB] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
