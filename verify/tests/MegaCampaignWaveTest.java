package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.math.Mathf; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.ai.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.game.*; import mindustry.gen.*; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts;

/**
 * 用户报（组合战役 combinec）的**端到端**复现：**组合敌方波次之后，如果那一波里有一些别的
 * 类型的单位，合出来的组合巨兽不会向玩家核心进攻，而是停在原地**（举例：tarFields 第 34 波）。
 *
 * <p>根因与修法见 {@link MegaFlyingPathTest}（派生类型的流场代价类型把"腿类"排在"飞行"前面，
 * 能飞的巨兽悬在天然岩壁上时流场在它自己脚下返回 -1 → `AIController.pathfind` 每帧直接 return）。
 *
 * <p>本测试走**真·战役扇区 + combinec 的波次合体**：读 `SectorPresets.tarFields`，用原版
 * `Logic.runWave()` 刷"腿类 + 飞行"那一波（combinec 的 WaveMerger 在 WaveEvent 里合体），
 * 然后看合出来的巨兽有没有朝玩家核心推进。
 *
 * <p>数据目录里没有 combinec.jar 时打印 SKIP 并 exit 0（这个测试是"用户现场"的钉子，
 * 根因本身由 {@link MegaFlyingPathTest} 在只有 combineunit 的数据目录里守）。
 */
public class MegaCampaignWaveTest implements ApplicationListener{
    static String dataDir="/tmp/mp_campc/data";
    static int pass=0, fail=0;

    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[MCW] "+t); };
        new HeadlessApplication(new MegaCampaignWaveTest(), t->t.printStackTrace()); }

    static void check(String n, boolean ok){ System.out.println("[MCW] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static void runReal(int f, long sleepMs){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); if(sleepMs>0) try{ Thread.sleep(sleepMs); }catch(InterruptedException e){} } }
    static boolean isMega(Unit u){ return u != null && u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity"); }

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

        if(Vars.mods.getMod("combinec") == null){
            System.out.println("[MCW] SKIP 数据目录里没有 combinec.jar（本测试要同时装 combinec + combineunit）");
            System.exit(0);
        }

        Sector sector = SectorPresets.tarFields.sector;
        Vars.world.loadSector(sector);
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.disableUnitCap = true;
        Vars.logic.play();
        run(20);

        Building core = Vars.state.rules.defaultTeam.core();
        if(core == null){ System.out.println("[MCW] 没有玩家核心"); System.exit(3); }

        // 找出"腿类 + 飞行"同时出现的那一波（原版口径：HUD 第 N 波 = getSpawned(N-1)）
        int target = -1;
        System.out.println("[MCW] tarFields 原版阵容（扇区 id=" + sector.id + "）:");
        for(int w = 30; w <= 45; w++){
            StringBuilder sb = new StringBuilder();
            boolean legs = false, flyer = false;
            for(SpawnGroup g : Vars.state.rules.spawns){
                int n = g.getSpawned(w - 1);
                if(n <= 0) continue;
                sb.append(g.type.name).append("×").append(n).append(" ");
                legs |= g.type.allowLegStep;
                flyer |= g.type.flying;
            }
            boolean mix = legs && flyer;
            System.out.println("[MCW]    第" + w + "波: " + sb + (mix ? " ← 腿类 + 飞行（用户现场）" : ""));
            if(mix && target < 0) target = w;
        }
        check("tarFields 找得到「腿类 + 飞行」的波次（前置）", target > 0);
        if(target < 0){ System.out.println("[MCW] RESULT FAILED"); System.exit(1); }

        Vars.state.wave = target;
        Vars.state.wavetime = 0f;
        Vars.logic.runWave();       // 刷这一波 + WaveEvent（combinec 在这一刻合体）
        run(2);

        Seq<Unit> megas = new Seq<>();
        Seq<Unit> left = new Seq<>();
        for(Unit u : Groups.unit){
            if(u.team != Vars.state.rules.waveTeam) continue;
            if(isMega(u)) megas.add(u); else left.add(u);
        }
        System.out.println("[MCW] 第" + target + "波刷完: 巨兽=" + megas.size + " 残留独立单位=" + left.size);
        check("combinec 在战役波次里合出了组合巨兽（前置）", megas.size > 0);
        if(megas.isEmpty()){ System.out.println("[MCW] RESULT FAILED"); System.exit(1); }

        for(Unit mega : megas){
            System.out.println("[MCW] 巨兽: 构成=" + composition(mega) + " flying=" + mega.isFlying()
                + " flowfieldPathType=" + mega.type.flowfieldPathType
                + " 控制器=" + (mega.controller() == null ? "null" : mega.controller().getClass().getSimpleName())
                + " 位置=(" + (int)mega.x + "," + (int)mega.y + ") 核心=(" + (int)core.x + "," + (int)core.y + ")");
            if(mega.isFlying()){
                check("会飞的巨兽用飞行流场 costNone（修前是 costLegs → 悬在岩壁上一步不走）",
                    mega.type.flowfieldPathType == Pathfinder.costNone);
            }

            float d0 = Mathf.dst(mega.x, mega.y, core.x, core.y);
            float best = d0;
            for(int i = 0; i < 1500; i++){
                runReal(1, 2);
                if(!mega.isAdded() || mega.dead()) break;
                best = Math.min(best, Mathf.dst(mega.x, mega.y, core.x, core.y));
            }
            System.out.println("[MCW] 巨兽距核心 " + (int)d0 + " → " + (int)best
                + "（终点 " + (int)Mathf.dst(mega.x, mega.y, core.x, core.y) + "）活着=" + (mega.isAdded() && !mega.dead()));
            check("组合巨兽朝玩家核心进攻（" + (int)d0 + " → " + (int)best + "）", d0 - best > 100f);
        }

        System.out.println("[MCW] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
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
