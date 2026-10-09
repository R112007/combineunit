package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.gen.*; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * 扫描"所有炮台方块"的绘制/弹药/发射条件 —— 用来给"有的炮台不画 drawer/part""有的炮台不发射"
 * 提供现场（尤其装上饱和火力之类的模组炮台时）。
 *
 * <p>只打印，不做断言、永远 exit 0：它是排查工具 + 覆盖清单。
 */
public class MegaTurretScanTest implements ApplicationListener{
    static String dataDir = "/tmp/mp_unit/data";

    public static void main(String[] a){
        if(a.length > 0) dataDir = a[0];
        Vars.platform = new mindustry.core.Platform(){};
        Vars.net = new Net(Vars.platform.getNet());
        Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MTS] " + t); };
        new HeadlessApplication(new MegaTurretScanTest(), t -> t.printStackTrace());
    }

    static Object fval(Object o, String name){
        Class<?> c = o.getClass();
        while(c != null){
            try{
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            }catch(Throwable ignored){ c = c.getSuperclass(); }
        }
        return null;
    }

    @Override public void init(){
        try{
            Core.settings.setDataDirectory(Core.files.local(dataDir));
            Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
            mindustry.core.UI.loadColors(); Fonts.loadContentIconsHeadless();
            Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
            Vars.mods.eachClass(Mod::init);

            int n = 0;
            for(Block b : Vars.content.blocks()){
                if(!(b instanceof Turret t)) continue;
                n++;
                Object drawer = fval(t, "drawer");
                int parts = -1;
                if(drawer != null){
                    Object pp = fval(drawer, "parts");
                    if(pp instanceof Seq<?> s) parts = s.size;
                }
                String ammo = "-";
                if(t instanceof ItemTurret it){
                    StringBuilder sb = new StringBuilder();
                    for(Item i : it.ammoTypes.keys()) sb.append(i.localizedName).append(' ');
                    ammo = "物品[" + sb + "] 弹仓=" + it.maxAmmo + " 每发=" + it.ammoPerShot;
                }else if(t instanceof LiquidTurret lt && lt.ammoTypes.size > 0){
                    StringBuilder sb = new StringBuilder();
                    for(Liquid i : lt.ammoTypes.keys()) sb.append(i.localizedName).append(' ');
                    ammo = "液体[" + sb + "]";
                }else if(t instanceof ContinuousLiquidTurret clt && clt.ammoTypes.size > 0){
                    StringBuilder sb = new StringBuilder();
                    for(Liquid i : clt.ammoTypes.keys()) sb.append(i.localizedName).append(' ');
                    ammo = "连续液体[" + sb + "]";
                }
                System.out.println("[MTS] " + b.name + "  class=" + b.getClass().getSimpleName()
                    + " drawer=" + (drawer == null ? "null" : drawer.getClass().getSimpleName())
                    + (drawer instanceof mindustry.world.draw.DrawTurret ? "(DrawTurret)" : "(非 DrawTurret)")
                    + " parts=" + parts
                    + " heatReq=" + t.heatRequirement
                    + " 可操控=" + t.playerControllable
                    + " 目标 空/地/块=" + t.targetAir + "/" + t.targetGround + "/" + t.targetBlocks
                    + " 弹药=" + ammo);
            }
            System.out.println("[MTS] RESULT 炮台方块共 " + n + " 个（数据目录 " + dataDir + "）");
            System.exit(0);
        }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
