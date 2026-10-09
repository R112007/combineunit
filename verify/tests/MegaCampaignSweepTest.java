package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.math.Mathf; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.ai.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.game.*; import mindustry.gen.*; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.Tile;

/**
 * 诊断/回归：把 tarFields 的前若干波逐波刷出来（combinec 的 WaveMerger 合体），
 * 逐波报告"合出来的巨兽朝不朝玩家核心走"，抓"某几波停在原地"的现场。
 *
 * <p>用户报过两处：第 33 波（腿 + 飞机）和第 10 波。
 */
public class MegaCampaignSweepTest implements ApplicationListener{
    static String dataDir="/tmp/mp_campc/data";
    static int pass=0, fail=0;
    static int fromWave = 1, toWave = 20;

    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[SW] "+t); };
        for(String s : a){ if(s.startsWith("-Dfrom=")) fromWave = Integer.parseInt(s.substring(6)); if(s.startsWith("-Dto=")) toWave = Integer.parseInt(s.substring(4)); }
        new HeadlessApplication(new MegaCampaignSweepTest(), t->t.printStackTrace()); }

    static void check(String n, boolean ok){ System.out.println("[SW] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static void runReal(int f, long sleepMs){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); if(sleepMs>0) try{ Thread.sleep(sleepMs); }catch(InterruptedException e){} } }
    static boolean isMega(Unit u){ return u != null && u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity"); }

    static String tileInfo(Unit u){
        Tile t = u.tileOn();
        return t == null ? "null" : (t.x + "," + t.y + "/" + t.block().name + (t.legSolid() ? "(legSolid)" : "")
            + (t.solid() ? "(solid)" : "") + (t.floor().isLiquid ? "(" + t.floor().name + ")" : ""));
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
        if(Vars.mods.getMod("combinec") == null){ System.out.println("[SW] SKIP 没有 combinec.jar"); System.exit(0); }

        Vars.world.loadSector(SectorPresets.tarFields.sector);
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.disableUnitCap = true;
        Vars.logic.play();
        run(20);
        Building core = Vars.state.rules.defaultTeam.core();

        for(int wave = fromWave; wave <= toWave; wave++){
            for(Unit u : Groups.unit.copy()){
                if(u.team == Vars.state.rules.waveTeam) u.kill();
            }
            run(3);

            StringBuilder comp = new StringBuilder();
            for(SpawnGroup g : Vars.state.rules.spawns){
                int n = g.getSpawned(wave - 1);
                if(n > 0) comp.append(g.type.name).append("×").append(n).append(" ");
            }

            Vars.state.wave = wave;
            Vars.state.wavetime = 0f;
            Vars.logic.runWave();
            run(2);

            Seq<Unit> megas = new Seq<>();
            int left = 0;
            for(Unit u : Groups.unit){
                if(u.team != Vars.state.rules.waveTeam) continue;
                if(isMega(u)) megas.add(u); else left++;
            }
            System.out.println("[SW] === 第" + wave + "波: " + comp + "→ 巨兽 " + megas.size + " 个，残留独立 " + left);
            for(Unit mega : megas){
                float d0 = Mathf.dst(mega.x, mega.y, core.x, core.y);
                float best = d0;
                for(int i = 0; i < 400; i++){
                    runReal(1, 2);
                    if(!mega.isAdded() || mega.dead()) break;
                    best = Math.min(best, Mathf.dst(mega.x, mega.y, core.x, core.y));
                }
                System.out.println("[SW]      巨兽: " + composition(mega)
                    + " | flying=" + mega.isFlying() + " allowLegStep=" + mega.type.allowLegStep
                    + " flowfieldPathType=" + mega.type.flowfieldPathType
                    + " | 脚下=" + tileInfo(mega)
                    + " | 距核心 " + (int)d0 + " → " + (int)best + " 速度=" + String.format("%.2f", mega.vel().len())
                    + " 活着=" + (mega.isAdded() && !mega.dead()));
                check("第" + wave + "波巨兽朝核心推进（" + (int)d0 + " → " + (int)best + "）", d0 - best > 64f);
            }
        }

        System.out.println("[SW] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
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
