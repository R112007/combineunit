package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.game.*; import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："组合单位和 ~/sd/questions 里俩模组（CT系统 ctcoresystem 1.75 + CT2起源 creators 5.30）有冲突"，
 * 崩溃日志（{@code 1 (1).txt}）：
 * <pre>
 *   java.lang.RuntimeException: Error loading mod combineunit
 *   Caused by: Failed to define class
 *     at mindustry.android.AndroidRhinoContext$BaseAndroidClassLoader.defineClass
 *     at rhino.JavaAdapter.loadAdapterClass / js_createAdapter
 *     at rhino.gen.creators_T6_js_32._c_anonymous_1(creators/T6.js:7)
 *     at combineunit.units.UnitComboDamage.replaceUnitConstructors
 *   Caused by: ClassNotFoundException: Didn't find class "adapter39"
 *   Suppressed: NoClassDefFoundError: Failed resolution of: Lcombineunit/units/entities/CUnitEntity;
 * </pre>
 *
 * <p>根因：creators 的脚本里到处是这种写法（27 处）：
 * <pre>
 *   T6rishi.constructor = prov(() => extend(UnitTypes.eclipse.constructor.get().class, {}));
 * </pre>
 * 也就是拿**原版单位构造器产出实例的 class** 当自己的超类。我们把原版单位的构造器换成了
 * combineunit 的镜像类（{@code CUnitEntity} 等）之后，这句 extend 拿到的是**模组类** ——
 * 安卓上 Rhino JavaAdapter 在"内存 dex"里定义适配器类、其类加载器的父级只有游戏类加载器，
 * 看不见别的模组的类 → 定义失败 → 异常从我们 register() 抛出去 → 整个 combineunit 加载失败、游戏崩。
 *
 * <p>修法（UnitComboDamage）：①两阶段替换（先全部取样、脚本适配器都在"原版构造器还都在"时建好，
 * 之后才统一替换）；②镜像构造器做成"脚本感知"的：执行别的模组的脚本构造器期间返回**原版实例**，
 * 脚本拿到的 class 就是游戏自己的类；游戏自己创建单位时才返回镜像。
 *
 * <p>那两个 CT 模组是 **dex-only（安卓包）**，桌面 JVM 根本加载不了（桌面读不了 classes.dex），
 * 所以这里用 verify 自带的**等价最小 JS 模组** {@code verify/mods/ctcompat/}（一模一样的写法：
 * {@code compatUnit.constructor = prov(() => extend(UnitTypes.eclipse.constructor.get().class, {}));}）
 * 来复现并验证。
 *
 * <p>要验的不变量（正是安卓上炸掉的那条）：
 * <ul>
 *     <li>脚本单位的实体类**继承的是游戏自己的类，而不是 combineunit 的镜像类**；</li>
 *     <li>这些脚本单位照样能创建/加入世界；</li>
 *     <li>我们自己的镜像替换没被搞坏（原版类型在游戏侧仍是镜像类、仍是 ComboUnit）。</li>
 * </ul>
 * 需要装了 ctcoresystem + creators 的数据集（verify/make-dataset.sh 造）。
 */
public class ScriptModCompatTest implements ApplicationListener{
    static String dataDir="/tmp/mp_js/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new ScriptModCompatTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[CTC] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void log(String s){ System.out.println("[CTC] " + s); }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
    }
    /** 应用类加载器（游戏自己的类都在这里）。 */
    static ClassLoader gameLoader = UnitType.class.getClassLoader();
    static boolean isGameClass(Class<?> c){
        return c != null && c.getClassLoader() == gameLoader;
    }
    /** 是不是"别的模组/脚本定义"的类（类加载器不是游戏那个）。 */
    static boolean isForeignClass(Class<?> c){
        return c != null && c.getClassLoader() != gameLoader;
    }
    /** 脚本/别的模组定义的实体类：类加载器不是游戏那个，而且继承链里没有 combineunit 的镜像类。 */
    static boolean isScriptClass(Class<?> c){
        return isForeignClass(c) && !hasCombineMirrorInHierarchy(c);
    }
    /** 类名里有没有 combineunit 的镜像实体（安卓上就是这一条让 Rhino 定义不了适配器）。 */
    static boolean hasCombineMirrorInHierarchy(Class<?> c){
        while(c != null){
            if(c.getName().startsWith("combineunit.units.entities.")) return true;
            c = c.getSuperclass();
        }
        return false;
    }
    static String hierarchy(Class<?> c){
        StringBuilder sb = new StringBuilder();
        while(c != null && !c.getName().equals("java.lang.Object")){
            if(sb.length() > 0) sb.append(" ← ");
            sb.append(c.getSimpleName());
            c = c.getSuperclass();
        }
        return sb.toString();
    }
    static Class<?> comboUnitClass;

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        // 【这一句就是崩溃点】别的模组的 init/register 全在这里跑：我们的 register() 抛异常 =
        // "Error loading mod combineunit" + 游戏崩。跑过去就说明没炸。
        Vars.mods.eachClass(Mod::init);
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();
        ml = Vars.mods.getMod("combineunit").main.getClass().getClassLoader();
        comboUnitClass = Class.forName("combineunit.units.entities.ComboUnit", true, ml);

        log("已装模组: " + Vars.mods.list().map(m -> m.name + ":" + m.meta.version).toString(", "));
        check("combineunit 加载完成（register 没把模组加载搞崩）", Vars.mods.getMod("combineunit") != null);
        if(Vars.mods.getMod("ctcompat") == null){
            log("（这个数据集里没有 verify/mods/ctcompat，跳过：跑这条要 "
                + "verify/make-dataset.sh /tmp/mp_js/data verify/mods/ctcompat）");
            System.out.println("[CTC] RESULT SKIP (no ctcompat)");
            System.exit(0);
        }
        check("数据集里有复现用的 JS 模组 ctcompat（前置）", Vars.mods.getMod("ctcompat") != null);

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.waves = false;
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.disableUnitCap = true;
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(10);
        int ox=-1, oy=-1;
        outer:
        for(int y=45;y<120;y++) for(int x=40;x<200;x++){
            boolean ok = true;
            for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-4;dx<=4;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ log("没找到陆地"); System.exit(4); }
        place(Blocks.coreShard, ox + 30, oy + 16, Team.sharded);
        run(10);

        // ---------- ① 找出"脚本模组自己的单位"（实体类不是游戏类） ----------
        Seq<UnitType> scriptTypes = new Seq<>();
        for(UnitType t : Vars.content.units()){
            try{
                Unit u = t.constructor.get();
                if(u != null && isScriptClass(u.getClass())) scriptTypes.add(t);
            }catch(Throwable ignored){
            }
        }
        log("实体类由模组/脚本自定义的单位类型 = " + scriptTypes.size + " 个");
        UnitType compat = null;
        for(UnitType t : Vars.content.units()) if(t.name.contains("ctcompat-unit")) compat = t;
        log("复现单位: " + (compat == null ? "没找到" : compat.name));
        check("JS 模组确实自定义了单位实体类（前置，" + scriptTypes.size + " 个）", scriptTypes.size > 0);
        check("找到了复现用的单位 ctcompat-unit（前置）", compat != null);

        // ---------- ①' 核心：脚本那句 extend(...constructor.get().class) 拿到的是**游戏类** ----------
        if(compat != null){
            Class<?> cls = null;
            try{
                Unit probe = compat.constructor.get();
                cls = probe == null ? null : probe.getClass();
            }catch(Throwable ex){
                log("复现单位构造失败: " + ex);
                ex.printStackTrace();
            }
            log("复现单位实体类: " + (cls == null ? "null" : cls.getName()) + "（继承链 "
                + (cls == null ? "-" : hierarchy(cls)) + "）");
            check("脚本单位继承的是**游戏自己的类**（" + (cls != null && isGameClass(cls.getSuperclass())) + "）",
                cls != null && isGameClass(cls.getSuperclass()));
            check("脚本单位的继承链里**没有** combineunit 的镜像类（安卓上就是这条炸的）",
                cls != null && !hasCombineMirrorInHierarchy(cls));
        }

        // ---------- ② 核心不变量：这些单位的类必须继承**游戏自己的类**，不能继承我们的镜像类 ----------
        int bad = 0, okTypes = 0, created = 0;
        StringBuilder samples = new StringBuilder();
        for(int i = 0; i < Math.min(scriptTypes.size, 12); i++){
            UnitType t = scriptTypes.get(i);
            Class<?> cls;
            try{
                Unit u = t.constructor.get();
                if(u == null) continue;
                cls = u.getClass();
            }catch(Throwable ex){
                log("  " + t.name + " 构造失败: " + ex);
                bad++;
                continue;
            }
            boolean mirror = hasCombineMirrorInHierarchy(cls);
            if(mirror) bad++; else okTypes++;
            // 真的创建一个、放进世界
            try{
                Unit live = t.create(Team.sharded);
                live.set(ox * 8f + 20f + i * 12f, oy * 8f + 120f);
                live.controller(new mindustry.ai.types.CommandAI());
                live.add();
                created++;
                live.kill();
            }catch(Throwable ex){
                log("  " + t.name + " 创建失败: " + ex);
            }
            samples.append("\n      ").append(t.name).append(": ").append(hierarchy(cls));
        }
        log("脚本单位实体类的继承链（前 12 个）:" + samples);
        check("脚本单位的实体类**不**继承 combineunit 的镜像类（实测 好=" + okTypes + " 坏=" + bad + "）", bad == 0);
        check("这些脚本单位能正常创建/加入世界（成功 " + created + " 个）", created > 0);

        // ---------- ③ 我们自己的镜像替换照旧 ----------
        Unit daggerSample = UnitTypes.dagger.constructor.get();
        log("原版 dagger 的构造器产出类 = " + daggerSample.getClass().getName()
            + "（是 ComboUnit? " + comboUnitClass.isInstance(daggerSample) + "）");
        check("原版类型（游戏侧创建）仍然是 combineunit 的镜像类（承伤共享照旧）",
            comboUnitClass.isInstance(daggerSample));
        check("镜像类本身确实继承游戏类（" + hierarchy(daggerSample.getClass()) + "）",
            hasCombineMirrorInHierarchy(daggerSample.getClass()) && !isForeignClass(daggerSample.getClass().getSuperclass()));

        // ---------- ④ 兜底：更晚（世界加载时）才设构造器的模组也要被补扫到 ----------
        {
            UnitType late = null;
            for(UnitType t : Vars.content.units()) if(t.name.contains("ctcompat-late-unit")) late = t;
            if(late == null){
                check("找到了「晚设构造器」的复现单位（前置）", false);
            }else{
                Class<?> cls = null;
                try{
                    Unit probe = late.constructor.get();
                    cls = probe == null ? null : probe.getClass();
                }catch(Throwable ex){
                    log("晚设构造器单位构造失败: " + ex);
                }
                log("晚设构造器单位实体类: " + (cls == null ? "null" : cls.getName())
                    + "（继承链 " + (cls == null ? "-" : hierarchy(cls)) + "）");
                check("世界加载后补扫到「晚设构造器」的模组单位，它继承的也是游戏类（"
                    + (cls != null && isGameClass(cls.getSuperclass())) + "）",
                    cls != null && isGameClass(cls.getSuperclass()) && !hasCombineMirrorInHierarchy(cls));
            }
        }

        System.out.println("[CTC] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
