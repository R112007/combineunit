package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import arc.func.Prov; import arc.util.io.*; import arc.util.io.Reads; import arc.util.io.Writes;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.entities.Units; import mindustry.gen.*; import mindustry.io.TypeIO;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.type.*;
import java.io.ByteArrayInputStream; import java.io.ByteArrayOutputStream; import java.io.DataInputStream; import java.io.DataOutputStream;

/**
 * 用户报：**联机时客户端生成不出核心机，一直处于无法建造的状态**。
 *
 * 根因：combineunit 把每个原版单位实体都换成了自己的镜像类（镜像类的 writeSync/readSync
 * 末尾多写一个 8 字节的 comboId），但"核心机不换构造器"那次修复只改了**构造器**、
 * 没改 **EntityMapping**（网络重建/存档读回按 classId 走的映射表）。于是：
 *   · 服务端造出来的核心机是原版 `UnitEntityLegacyBeta` → 按**原版格式**写快照；
 *   · 客户端按 classId 查 EntityMapping，建出来的是镜像 `CUnitEntityLegacyBeta`
 *     → 按**镜像格式**读，多读 8 字节 → 整包实体快照 EOF 被丢掉
 *     （真联机日志：`Error reading entity snapshot: ... EOFException at
 *      combineunit.units.entities.CUnitEntityLegacyGamma.readSync`，一次跑刷 309 条）。
 * 核心机所在的快照全被丢 → 客户端迟迟拿不到自己的核心机 → 不能建造；
 * 别的单位也跟着少同步（服务端 6 个单位、客户端只看到 4 个）。
 *
 * 判定（本测试）：**每一个单位类型**，"服务端按 type.constructor 造出来的类" 必须和
 * "客户端按 classId 从 EntityMapping 建出来的类"**是同一个类**（同一个 classId 只能有一种
 * 字节格式）；核心机必须是原版类（这是与作弊模组兼容那条修复的钉子），普通单位照旧是镜像类；
 * 并且真做一次 服务端 writeUnitContainer → 客户端 readUnitContainer 的字节往返。
 */
public class CoreUnitSyncTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new CoreUnitSyncTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CUS] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }

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

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();

        // ——① 逐个类型核对"服务端造的类" == "客户端按 classId 建的类" ——
        int checked = 0, skipped = 0, mismatch = 0;
        arc.struct.ObjectMap<Class<?>, arc.struct.Seq<String>> byClass = new arc.struct.ObjectMap<>();
        for(UnitType type : Vars.content.units()){
            if(type.constructor == null){ skipped++; continue; }
            Unit serverSide;
            try{ serverSide = type.constructor.get(); }catch(Throwable t){ skipped++; continue; }
            if(serverSide == null){ skipped++; continue; }
            Class<?> serverCls = serverSide.getClass();
            byClass.get(serverCls, arc.struct.Seq::new).add(type.name);
            int cid = serverSide.classId() & 0xFF;
            Prov<?> mapped = EntityMapping.idMap[cid];
            if(mapped == null){
                System.out.println("[CUS] 槽 " + cid + "（" + type.name + "）在映射表里是空的");
                mismatch++; continue;
            }
            Class<?> clientCls = mapped.get().getClass();
            checked++;
            if(clientCls != serverCls){
                mismatch++;
                System.out.println("[CUS] 类不一致 " + type.name + "：服务端 " + serverCls.getName()
                    + " / 客户端按槽 " + cid + " 建出 " + clientCls.getName());
            }
        }
        check("全部单位类型：服务端实体类 == 客户端按 classId 建出来的类（" + checked + " 个类型，跳过 "
            + skipped + "）", mismatch == 0);

        // 一个实体类被多个类型共用时，谁能装镜像就必须整个类一起决定 —— 这里把共用的类打出来
        for(var e : byClass){
            if(e.value.size > 1){
                boolean anyCore = false, anyNormal = false;
                for(String n : e.value){
                    UnitType t = Vars.content.unit(n);
                    boolean core = t != null && (t.coreUnitDock || t == UnitTypes.alpha || t == UnitTypes.beta
                        || t == UnitTypes.gamma || t == UnitTypes.evoke || t == UnitTypes.incite || t == UnitTypes.emanate);
                    anyCore |= core; anyNormal |= !core;
                }
                System.out.println("[CUS] 共用实体类 " + e.key.getName() + (anyCore && anyNormal ? "（核心机+普通单位！）" : "")
                    + " ← " + e.value.toString(", "));
            }
        }

        // ——② 塞普罗核心机（只被核心机用的类）必须是原版类（与作弊模组兼容那条修复的钉子）；
        //      埃里克尔核心机与普通单位共用一个实体类，只能跟着普通单位一起装镜像（两端一致即可）；
        //      普通单位照旧是镜像类 ——
        for(UnitType t : new UnitType[]{UnitTypes.alpha, UnitTypes.beta, UnitTypes.gamma}){
            if(t == null) continue;
            String cls = t.constructor.get().getClass().getName();
            String mapped = EntityMapping.idMap[t.constructor.get().classId() & 0xFF].get().getClass().getName();
            check("核心机 " + t.name + " 两端都是原版类（" + cls + "）",
                cls.startsWith("mindustry.gen.") && mapped.equals(cls));
        }
        for(UnitType t : new UnitType[]{UnitTypes.evoke, UnitTypes.incite, UnitTypes.emanate}){
            if(t == null) continue;
            UnitType shared = t == UnitTypes.evoke ? UnitTypes.mega : UnitTypes.mega;
            String cls = t.constructor.get().getClass().getName();
            String mapped = EntityMapping.idMap[t.constructor.get().classId() & 0xFF].get().getClass().getName();
            String megaCls = shared == null ? "-" : shared.constructor.get().getClass().getName();
            check("埃里克尔核心机 " + t.name + " 与共用该类的普通单位（" + (shared == null ? "?" : shared.name)
                + "）同类且两端一致（" + cls + "）", mapped.equals(cls) && cls.equals(megaCls));
        }
        String dagger = UnitTypes.dagger.create(Team.sharded).getClass().getName();
        String daggerMapped = EntityMapping.idMap[UnitTypes.dagger.constructor.get().classId() & 0xFF].get().getClass().getName();
        check("普通单位 dagger 两端都是镜像类（" + dagger + "）",
            dagger.startsWith("combineunit.units.entities.") && daggerMapped.equals(dagger));

        // ——③ 真做一次字节往返：服务端 writeUnitContainer → 客户端 readUnitContainer ——
        for(UnitType core : new UnitType[]{UnitTypes.alpha, UnitTypes.beta, UnitTypes.gamma}){
            if(core == null) continue;
            roundTrip(core);
        }

        System.out.println("[CUS] ===== 结果: PASS=" + pass + " FAIL=" + fail + " =====");
        System.exit(fail == 0 ? 0 : 1);
      }catch(Throwable t){ System.out.println("[CUS] 崩了: " + t); t.printStackTrace(); System.exit(2); }
    }

    /** 服务端把一只核心机写进快照字节，客户端从同一份字节里读回来（联机快照/生成包走的就是这两句）。 */
    static void roundTrip(UnitType core){
        String tag = "核心机字节往返 " + core.name;
        try{
            Unit u = core.create(Team.sharded);
            u.set(123 * 8f, 45 * 8f);
            u.rotation(90f);
            u.health = u.maxHealth * 0.5f;
            u.add();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            TypeIO.writeUnitContainer(new Writes(new DataOutputStream(bos)), new Units.UnitSyncContainer(u));
            byte[] bytes = bos.toByteArray();

            int id = u.id, hp = (int)u.health;
            u.remove();                      // 客户端此刻还没有这只单位（快照就是"第一次见到它"）
            Unit back = TypeIO.readUnitContainer(
                new Reads(new DataInputStream(new ByteArrayInputStream(bytes)))).unit;

            // 位置/方向不在这段同步流里（快照另发），这里只判"字节没读串"：类/类型/id/血量必须一致
            boolean ok = back != null && back.id == id && back.type == core && (int)back.health == hp;
            System.out.println("[CUS] " + tag + "：写 " + bytes.length + " 字节 id=" + id + " 血量=" + hp
                + " → 读回 " + (back == null ? "null" : ("id=" + back.id + " 类=" + back.getClass().getName()
                + " 类型=" + back.type.name + " 位置=" + (int)back.x + "," + (int)back.y
                + " 方向=" + (int)back.rotation() + " 血量=" + (int)back.health)));
            check(tag, ok);
            if(back != null) back.remove();
        }catch(Throwable t){
            check(tag + "（抛异常：" + t + "）", false);
        }
    }
}
