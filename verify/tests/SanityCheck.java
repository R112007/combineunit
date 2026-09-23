package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts;

/**
 * 验证器的"防呆"检查：**combineunit 模组真的加载、单位侧机制真的挂上了吗**。
 *
 * 【为什么需要】客户端跑挂了（被沙箱杀在半路、或真崩了）时，Mindustry 会在数据目录的
 * settings 里写 `mod-combineunit-failed`，之后所有 headless 测试里 combineunit 都会被跳过 ——
 * 测试看着还在 PASS，其实一条组合单位逻辑都没跑（combine 仓库真踩过）。
 * run-headless.sh 每次跑测试前先跑这个，不通过就自动清掉那份 settings 重来。
 *
 * 另外这里顺手验三件"注册了才算装上"的事：
 *   · UnitComboMerge.register() 建出了三种巨兽类型、并把巨兽实体类登记到固定槽 250；
 *   · UnitComboDamage.register() 真的把原版单位实体换成了镜像类（造一只 dagger 看类型）。
 */
public class SanityCheck implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new SanityCheck(), t->t.printStackTrace()); }
    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        var cm = Vars.mods.getMod("combineunit");
        boolean loaded = cm != null && cm.main != null;

        Class<?> mergeCls = null;
        if(loaded){
            try{ mergeCls = Class.forName("combineunit.units.UnitComboMerge", true, cm.main.getClass().getClassLoader()); }
            catch(Throwable ignored){}
        }

        boolean megaTypes = false;
        int slot = -1;
        if(mergeCls != null){
            try{
                megaTypes = mergeCls.getField("megaGround").get(null) != null
                    && mergeCls.getField("megaAir").get(null) != null
                    && mergeCls.getField("megaNaval").get(null) != null;
                slot = mergeCls.getField("MEGA_ENTITY_SLOT").getInt(null);
            }catch(Throwable ignored){}
        }
        boolean slotRegistered = slot >= 0 && slot < mindustry.gen.EntityMapping.idMap.length
            && mindustry.gen.EntityMapping.idMap[slot] != null;

        // 镜像实体替换的实证：造一只 dagger，看它是不是 combineunit 的镜像类
        String daggerClass = "-";
        try{ daggerClass = UnitTypes.dagger.create(Team.sharded).getClass().getName(); }catch(Throwable ignored){}
        boolean mirrored = daggerClass.startsWith("combineunit.units.entities.");

        System.out.println("[SANITY] combineunit 加载=" + loaded
            + " 巨兽类型=" + megaTypes + " 实体槽" + slot + "已登记=" + slotRegistered
            + " dagger 实体类=" + daggerClass + " 已镜像=" + mirrored);
        System.exit(loaded && megaTypes && slotRegistered && mirrored ? 0 : 1);
      }catch(Throwable t){ System.out.println("[SANITY] 崩了: " + t); System.exit(2); }
    }
}
