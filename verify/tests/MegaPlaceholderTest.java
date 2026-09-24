package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.files.Fi; import arc.struct.Seq;
import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："3 个 toxopid 合体后的单位，在指挥模式下的图标变成了 corvus"（附了存档）。
 *
 * <p>根因：原版命令面板是按 `unit.type.id` 聚合、再用 `content.unit(id)` 取**类型**的
 * （图标 `StatValues.stack(type, n)` 读 `type.uiIcon`、指令遍历 `type.commands`）；而巨兽的
 * 所有派生类型**共用一个占位 id**（`megaGround.id`），面板实际拿到的是占位类型本身。
 * 占位类型的图标/指令是"每推导一只巨兽就覆盖一次"的 —— 存档里先有 toxopid 巨兽、后面又推导过
 * 一只 corvus 巨兽，占位类型就停在 corvus 上，于是 toxopid 巨兽在面板里显示 corvus 的图标/指令。
 *
 * <p>修法：把占位类型同步抽成 `MegaUnitEntity.syncPlaceholder(...)`，并让**客户端每帧**
 * 把它同步成"当前焦点的那只巨兽"（指挥模式选中的优先，其次玩家操控的，见
 * `UnitComboBind.tick()` → `syncPlaceholderToFocus()`）。本测试验这套同步的行为：
 * 两只不同构成的巨兽，跟着"同步谁"走；另外如果能读到用户存档，就把现场也打出来。
 */
public class MegaPlaceholderTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaPlaceholderTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MPH] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
    }
    static Unit merge(float x, float y, int count, UnitType type){
        try{
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < count; i++){
                Unit u = type.create(Team.sharded);
                u.set(x + i * 10f, y);
                u.add();
                us.add(u);
            }
            run(3);
            Object mega = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("mergeSelected", Seq.class).invoke(null, us);
            run(3);
            return mega instanceof Unit mu ? mu : null;
        }catch(Throwable t){ System.out.println("[MPH] 融合失败: " + t); return null; }
    }
    /** 面板取到的东西：content.unit(unit.type.id)（= 占位类型）的指令列表。 */
    static String panelCommands(Unit beast){
        UnitType t = Vars.content.unit(beast.type.id);
        if(t == null) return "null";
        StringBuilder sb = new StringBuilder();
        for(var c : t.commands) sb.append(c.name).append(' ');
        return sb.toString().trim();
    }
    static String ownCommands(Unit beast){
        StringBuilder sb = new StringBuilder();
        for(var c : beast.type.commands) sb.append(c.name).append(' ');
        return sb.toString().trim();
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

        UnitType tox = null, corvus = null;
        for(UnitType t : Vars.content.units()){
            if(t.name.equals("toxopid")) tox = t;
            if(t.name.equals("corvus")) corvus = t;
        }
        if(tox == null || corvus == null){ System.out.println("[MPH] 缺 toxopid/corvus（跳过）"); System.exit(0); }

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
        for(int y=45;y<150;y++) for(int x=40;x<220;x++){
            boolean ok = true;
            for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-4;dx<=4;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ System.out.println("[MPH] 没找到陆地"); System.exit(3); }
        place(Blocks.coreShard, ox + 30, oy + 16, Team.sharded);
        run(10);

        // 先合 toxopid 巨兽，再合 corvus 巨兽（后者的推导会覆盖共用的占位类型 = 用户遇到的现象）
        Unit beastTox = merge(ox * 8f, oy * 8f, 3, tox);
        Unit beastCorvus = merge(ox * 8f + 140f, oy * 8f, 3, corvus);
        if(beastTox == null || beastCorvus == null){ check("两只巨兽都合出来（前置）", false); System.exit(3); }
        check("两只巨兽都合出来（前置）", true);
        System.out.println("[MPH] toxopid 巨兽: 自己的指令=[" + ownCommands(beastTox) + "] 面板看到=[" + panelCommands(beastTox) + "]");
        System.out.println("[MPH] corvus  巨兽: 自己的指令=[" + ownCommands(beastCorvus) + "] 面板看到=[" + panelCommands(beastCorvus) + "]");
        check("共用一个占位 id（content.unit(type.id) 就是占位类型）",
            Vars.content.unit(beastTox.type.id) == Vars.content.unit(beastCorvus.type.id));

        // 修法：把占位类型同步成"当前焦点的那只"
        Class<?> muCls = Class.forName("combineunit.units.mega.MegaUnitEntity", true, ml);
        muCls.getMethod("syncPlaceholderTo", Unit.class).invoke(null, beastTox);
        String afterTox = panelCommands(beastTox);
        System.out.println("[MPH] 同步到 toxopid 巨兽后：面板看到=[" + afterTox + "]（toxopid 自己的=[" + ownCommands(beastTox) + "]）");
        check("同步到 toxopid 巨兽后，面板指令 = 它自己的指令", afterTox.equals(ownCommands(beastTox)));
        // 注意：toxopid / corvus 的指令表**恰好一样**（move/combo/enterPayload），
        // 所以"图标对不对"只能在真客户端比（headless 没图集）：见 verify/client Driver 的 icon 模式，
        // 以及 verify/README.md 里那次用户存档的实测记录（修前 4 只巨兽面板图标全是 unit-corvus-ui，
        // 选中 toxopid 巨兽后变成 unit-toxopid-ui 且"是否一致=true"）。

        muCls.getMethod("syncPlaceholderTo", Unit.class).invoke(null, beastCorvus);
        String afterCorvus = panelCommands(beastCorvus);
        System.out.println("[MPH] 同步到 corvus 巨兽后：面板看到=[" + afterCorvus + "]（corvus 自己的=[" + ownCommands(beastCorvus) + "]）");
        check("同步到 corvus 巨兽后，面板指令 = corvus 自己的指令", afterCorvus.equals(ownCommands(beastCorvus)));

        // 用户存档现场（有就打印；没有就跳过）
        try{
            Fi dir = Core.files.local(dataDir).child("saves");
            Fi pick = null;
            if(dir.exists()){
                for(Fi f : dir.list()) if(f.name().endsWith(".msav") && !f.name().contains("backup")){ pick = f; break; }
            }
            if(pick == null) System.out.println("[MPH] （数据目录里没有用户存档，跳过现场复现）");
            else{
                System.out.println("[MPH] 读用户存档: " + pick.name());
                SaveIO.load(pick);
                run(30);
                int beasts = 0;
                for(Unit u : Groups.unit){
                    if(!u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")) continue;
                    beasts++;
                    System.out.println("[MPH]   存档里的巨兽 id=" + u.id() + " 代表类型="
                        + dominantName(u) + " 自己的指令=[" + ownCommands(u) + "] 面板看到=[" + panelCommands(u) + "]");
                }
                System.out.println("[MPH]   存档里巨兽数量=" + beasts);
                check("用户存档里能找到巨兽（用来复现现场）", beasts > 0);
            }
        }catch(Throwable t){ System.out.println("[MPH] 读用户存档失败: " + t); }

        System.out.println("[MPH] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static String dominantName(Unit u){
        try{
            var f = u.getClass().getDeclaredField("dominant");
            f.setAccessible(true);
            Object o = f.get(u);
            return o instanceof UnitType t ? t.name : "null";
        }catch(Throwable t){ return "?"; }
    }
}
