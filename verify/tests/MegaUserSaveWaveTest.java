package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.math.Mathf; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.ai.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.game.*; import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.Tile;

/**
 * **用户自己那张 tarFields 图**上的复现/回归（读 `saves/sector-serpulo-99.msav`；没有这个存档就 SKIP）。
 *
 * <p>用户报的三处："第 34 波"、"第 10 波"、"**第 9 波也是这样**"。这个存档是他在 tarFields
 * （内部名 `焦油田`，260×260，液面比 0.126 → 不是海军图）里打到第 40 波的那份，波次表就是他的：
 * <pre>
 *   第 8 波 crawler×5 / 第 9 波 mace×2 spiroct×1(腿) horizon×2(飞) / 第 10 波 nova×3(助) crawler×6
 *   第 33 波 mace×8 spiroct×5(腿) horizon×14(飞) atrax×3(腿) / 第 34 波 nova×9(助) crawler×21
 * </pre>
 *
 * <p>本测试在**他的图**上把每一波刷出来（combinec 的 WaveMerger 合体），先用**修复前**的口径
 * （原版 `GroundAI` + 原版 `initPathType` 优先级：腿类排在飞行前面）跑一遍，再用修复后的口径跑一遍：
 * <ul>
 *   <li>第 9 波 / 第 33 波：修复前巨兽出生点就在天然岩壁（`sand-wall` + 深水）上，2400 tick
 *       **速度恒为 0.00、距核心几乎不变**（用户报的"停在原地"）；修复后 2400 tick 直接推进到核心
 *       （1747→40 / 1766→2）。</li>
 *   <li>第 10 波 / 第 34 波（nova 带 canBoost + crawler，纯地面）：这两波在**他的图上、这个出生点**
 *       两条口径都能走（走到基地火力圈里被打掉），所以这里只打印现场、不做断言 ——
 *       canBoost 巨兽"悬在岩壁上被自己的 costGround 流场判死路"的那条链由
 *       {@link MegaHoverStuckTest} 钉（同一只巨兽换控制器 A/B：原版 GroundAI 5px vs MegaGroundAI 466px）。</li>
 * </ul>
 *
 * <p>数据目录：把 `/tmp/mp_save/data/saves/sector-serpulo-99.msav` 拷进 `<数据目录>/saves/`
 * （并装 combineunit + combinec）。
 */
public class MegaUserSaveWaveTest implements ApplicationListener{
    static String dataDir="/tmp/mp_campc/data";
    static int pass=0, fail=0;
    static final int[] waves = {9, 10, 33, 34};
    /** 断言要的两波（在用户的图上、这个出生点上决定性复现的那两波）。 */
    static final int[] assertWaves = {9, 33};
    static int ticks = 2400;
    /** 逐波扫的范围/每波观测 tick。 */
    static int sweepFrom = 1, sweepTo = 45, sweepTicks = 300;

    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[U] "+t); };
        new HeadlessApplication(new MegaUserSaveWaveTest(), t->t.printStackTrace()); }

    static void check(String n, boolean ok){ System.out.println("[U] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static void runReal(int f, long sleepMs){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); if(sleepMs>0) try{ Thread.sleep(sleepMs); }catch(InterruptedException e){} } }
    static boolean isMega(Unit u){ return u != null && u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity"); }
    static boolean contains(int[] arr, int v){ for(int x : arr) if(x == v) return true; return false; }
    static String tileInfo(Unit u){
        Tile t = u.tileOn();
        return t == null ? "null" : (t.x + "," + t.y + "/" + t.block().name
            + (t.solid() ? "(solid)" : "") + (t.floor().isLiquid ? "(" + t.floor().name + ")" : ""));
    }
    static String waveLine(Seq<SpawnGroup> groups, int w){
        StringBuilder sb = new StringBuilder();
        for(SpawnGroup g : groups){
            int n = g.getSpawned(w - 1);
            if(n > 0) sb.append(g.type.name).append("×").append(n)
                .append(g.type.flying ? "(飞)" : g.type.allowLegStep ? "(腿)" : g.type.canBoost ? "(助)" : "").append(" ");
        }
        return sb.toString();
    }
    /** 修复前的流场代价类型（原版 initPathType 的优先级：腿类排在飞行前面）。 */
    static int oldFlowType(Unit mega){
        return mega.type.naval ? Pathfinder.costNaval
            : mega.type.allowLegStep ? Pathfinder.costLegs
                : mega.type.flying ? Pathfinder.costNone
                    : !mega.type.canDrown ? Pathfinder.costHover
                        : Pathfinder.costGround;
    }

    /** 量一段：返回 {推进了多少, 结束时活着?1:0}。 */
    static float[] measure(Unit mega, Building core, int tickCount){
        float d0 = Mathf.dst(mega.x, mega.y, core.x, core.y);
        float best = d0;
        for(int i = 0; i < tickCount; i++){
            runReal(1, 2);
            if(!mega.isAdded() || mega.dead()) return new float[]{d0 - best, 0f};
            best = Math.min(best, Mathf.dst(mega.x, mega.y, core.x, core.y));
        }
        return new float[]{d0 - best, 1f};
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

        arc.files.Fi save = Core.settings.getDataDirectory().child("saves/sector-serpulo-99.msav");
        if(!save.exists()){
            System.out.println("[U] SKIP 没有用户存档 " + save.path() + "（需要 saves/sector-serpulo-99.msav）");
            System.exit(0);
        }

        for(int w : waves){
            for(boolean oldBehavior : new boolean[]{true, false}){
                SaveIO.load(save);
                Vars.logic.play();
                run(20);
                Building core = Vars.state.rules.defaultTeam.core();
                Vars.state.wave = w;
                Vars.state.wavetime = 0f;
                Vars.logic.runWave();
                run(2);

                Unit mega = null;
                for(Unit u : Groups.unit) if(u.team == Vars.state.rules.waveTeam && isMega(u)){ mega = u; break; }
                if(mega == null){ System.out.println("[U] 第" + w + "波没合出巨兽"); continue; }
                int properCost = mega.type.flowfieldPathType;
                if(oldBehavior){
                    mega.type.flowfieldPathType = oldFlowType(mega);
                    mega.controller(new mindustry.ai.types.GroundAI());
                }

                String tag = "第" + w + "波 " + (oldBehavior ? "修复前" : "修复后");
                float d0 = Mathf.dst(mega.x, mega.y, core.x, core.y);
                System.out.println("[U] === " + tag + ": " + composition(mega)
                    + " | flying=" + mega.isFlying() + " legs=" + mega.type.allowLegStep
                    + " boost=" + mega.type.canBoost + " cost=" + mega.type.flowfieldPathType
                    + " ai=" + mega.controller().getClass().getSimpleName()
                    + " | 起点距核心 " + (int)d0 + " 脚下=" + tileInfo(mega));
                float[] res = measure(mega, core, ticks);
                System.out.println("[U]      " + ticks + " tick: 推进 " + (int)res[0] + "px 距核心 "
                    + (int)Mathf.dst(mega.x, mega.y, core.x, core.y) + " 活着=" + (res[1] > 0.5f)
                    + ((res[1] > 0.5f && res[0] <= 64f) ? "   <<<< 停在原地" : ""));

                if(contains(assertWaves, w)){
                    if(oldBehavior){
                        check("复现用户报的现象：第" + w + "波修复前停在原地/被清掉（推进 " + (int)res[0] + "px）",
                            res[1] < 0.5f || res[0] <= 64f);
                    }else{
                        check("第" + w + "波修复后朝玩家核心推进（推进 " + (int)res[0] + "px）", res[0] > 500f);
                    }
                }else{
                    System.out.println("[U]      （第" + w + "波在用户这张图这个出生点上两条口径都能走："
                        + "canBoost 悬空那条链由 MegaHoverStuckTest 钉，这里只记录现场）");
                }

                if(oldBehavior) mega.type.flowfieldPathType = properCost; // 还原派生类型缓存，别污染后面的场景
            }
        }

        // ---------- 第二阶段：在用户的图上**逐波扫一遍**，确保没有哪一波又停在原地 ----------
        SaveIO.load(save);
        Vars.state.rules.disableUnitCap = true;
        Vars.state.rules.canGameOver = false;
        Vars.logic.play();
        run(20);
        Building core2 = Vars.state.rules.defaultTeam.core();
        System.out.println("[U] ===== 逐波扫（第" + sweepFrom + "~" + sweepTo + "波，每波 " + sweepTicks + " tick）");
        int swept = 0, stalled = 0;
        for(int w = sweepFrom; w <= sweepTo; w++){
            for(Unit u : Groups.unit.copy()) if(u.team == Vars.state.rules.waveTeam) u.kill();
            run(3);
            Vars.state.rules.disableUnitCap = true;
            Vars.state.wave = w;
            Vars.state.wavetime = 0f;
            Vars.logic.runWave();
            run(2);
            Seq<Unit> ms = new Seq<>();
            for(Unit u : Groups.unit) if(u.team == Vars.state.rules.waveTeam && isMega(u)) ms.add(u);
            if(ms.isEmpty()) continue;
            for(Unit mega : ms){
                swept++;
                float d0 = Mathf.dst(mega.x, mega.y, core2.x, core2.y);
                float best = d0;
                boolean alive = true;
                for(int i = 0; i < sweepTicks; i++){
                    runReal(1, 2);
                    if(!mega.isAdded() || mega.dead()){ alive = false; break; }
                    best = Math.min(best, Mathf.dst(mega.x, mega.y, core2.x, core2.y));
                }
                float adv = d0 - best;
                boolean stall = alive && adv <= 24f;
                if(stall) stalled++;
                System.out.println("[U] 第" + w + "波 " + composition(mega)
                    + " | flying=" + mega.isFlying() + " legs=" + mega.type.allowLegStep
                    + " boost=" + mega.type.canBoost + " cost=" + mega.type.flowfieldPathType
                    + " 脚下=" + tileInfo(mega)
                    + " | 推进 " + (int)adv + "px" + (alive ? "" : " [死亡]")
                    + (stall ? "   <<<< 停在原地" : ""));
            }
        }
        System.out.println("[U] 逐波扫合计巨兽 " + swept + "，停在原地 " + stalled);
        check("玩家图上第" + sweepFrom + "~" + sweepTo + "波没有任何一波停在原地", stalled == 0);

        System.out.println("[U] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    static String composition(Unit u){
        try{
            ClassLoader ml = Vars.mods.getMod("combineunit").main.getClass().getClassLoader();
            return (String)Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("composition", Unit.class).invoke(null, u);
        }catch(Throwable t){ return "<" + t + ">"; }
    }
}
