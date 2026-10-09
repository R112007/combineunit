package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.math.Mathf; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.ai.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.game.*; import mindustry.gen.*; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.Tile;

/**
 * 用户报（组合战役 combinec）第 10 波：**组合巨兽同样停在原地、不向玩家核心进攻**。
 *
 * <p>第 10 波是 `nova×3 + crawler×6` —— **nova 带 canBoost**，于是派生类型继承 canBoost
 * （`ct.canBoost = anyBoostMember && !ct.flying`，见 MegaUnitEntity.compTypeFor）。
 * 原版 `UnitComp.updateBoosting()` 在**实心格**上会让它自动升空（`shouldBoost = boost ||
 * onSolid() || ...`）→ `isFlying()=true`、`solidity()=null` → 它就能一直待在那样的格子上；
 * 而它的流场是 costGround（没有飞行成员、没有腿成员），**天然岩壁在 costGround 里是 impassable**：
 * 流场在自己脚下返回 -1，`AIController.pathfind` 见 `tile == targetTile` 直接 return ——
 * 一步都不走。tarFields 的波次出生点就落在"岩壁（block=solid）+ 深水"那种格子上。
 *
 * <p>同一类现场还有第 33/34 波（spiroct 给腿 + horizon 给飞机，见
 * {@link MegaFlyingPathTest}）：能飞的巨兽永远悬空，腿类流场同样在自己脚下返回 -1。
 *
 * <p>修法：{@link combineunit.units.mega.MegaGroundAI} —— 流场给出死路（下一格 == 当前格）时
 * 直接朝最近的敌方核心推进，脱困后流场照常接管；正常路径完全走原版逻辑。
 *
 * <p>本测试直接量"同一只巨兽、同一格岩壁"下的 **A/B**：控制器换成原版 `GroundAI`（=修复前）
 * → 600 tick 几乎不动；换回巨兽自己的 AI（`type.createController`，= MegaGroundAI）→ 明显推进。
 */
public class MegaHoverStuckTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;

    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[MHS] "+t); };
        new HeadlessApplication(new MegaHoverStuckTest(), t->t.printStackTrace()); }

    static void check(String n, boolean ok){ System.out.println("[MHS] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static void runReal(int f, long sleepMs){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); if(sleepMs>0) try{ Thread.sleep(sleepMs); }catch(InterruptedException e){} } }
    static boolean isMega(Unit u){ return u != null && u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity"); }
    static UnitType unit(String name){ for(UnitType t : Vars.content.units()) if(t.name.equals(name)) return t; return null; }
    static Unit spawn(UnitType t, Team team, float x, float y){ return t.spawn(team, x, y, 0f); }
    static Unit merge(Seq<Unit> units){
        try{ return (Unit)Class.forName("combineunit.units.UnitComboMerge", true, ml)
            .getMethod("mergeSelected", Seq.class).invoke(null, units); }
        catch(Throwable t){ System.out.println("[MHS] 融合失败: " + t); return null; }
    }
    /** 找一格"天然岩壁"（costGround/costLegs 都判不可达那种）。 */
    static Tile findRock(float minDst, Building core){
        for(int y = 8; y < Vars.world.height() - 8; y++) for(int x = 8; x < Vars.world.width() - 8; x++){
            Tile t = Vars.world.tile(x, y);
            if(t == null || !t.legSolid()) continue;
            if(Vars.world.tile(x+1, y) == null || !Vars.world.tile(x+1, y).legSolid()) continue;
            if(core != null && Mathf.dst(t.worldx(), t.worldy(), core.x, core.y) < minDst) continue;
            return t;
        }
        return null;
    }
    static Object flowFieldField(Object f, String name){
        try{
            java.lang.reflect.Field fld = Class.forName("mindustry.ai.Pathfinder$Flowfield").getDeclaredField(name);
            fld.setAccessible(true);
            return fld.get(f);
        }catch(Throwable t){ return null; }
    }
    /** 流场在某一格的权重（-1 = 不可达）。 */
    static int flowValue(Object f, Tile t){
        try{
            int[] w = (int[])flowFieldField(f, "weights");
            int width = (Integer)flowFieldField(f, "width");
            int apos = t.x + t.y * width;
            return w == null || apos >= w.length ? -999 : w[apos];
        }catch(Throwable e){ return -999; }
    }
    /** 让巨兽在 600 tick 里朝核心推进多少像素。 */
    static float advance(Unit mega, Building core){
        float d0 = Mathf.dst(mega.x, mega.y, core.x, core.y);
        float best = d0;
        for(int i = 0; i < 600; i++){
            runReal(1, 2);
            if(!mega.isAdded() || mega.dead()) break;
            best = Math.min(best, Mathf.dst(mega.x, mega.y, core.x, core.y));
        }
        System.out.println("[MHS]      600 tick: 距核心 " + (int)d0 + " → " + (int)best
            + " 速度=" + String.format("%.2f", mega.vel().len()) + " 活着=" + (mega.isAdded() && !mega.dead()));
        return d0 - best;
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

        UnitType nova = unit("nova"), crawler = unit("crawler"), mace = unit("mace"), dagger = UnitTypes.dagger;
        System.out.println("[MHS] nova.canBoost=" + nova.canBoost + " crawler.canBoost=" + crawler.canBoost
            + " mace.canBoost=" + mace.canBoost + " dagger.canBoost=" + dagger.canBoost);
        check("前置：第 10 波的 nova 带 canBoost（派生类型会继承它 → 在实心格上自动悬空）", nova.canBoost);

        Vars.world.loadSector(SectorPresets.tarFields.sector);
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.disableUnitCap = true;
        Vars.logic.play();
        run(20);
        Building core = Vars.state.rules.defaultTeam.core();
        Tile rock = findRock(700f, core);
        check("地图上找得到天然岩壁（前置）", rock != null);
        if(rock == null){ System.out.println("[MHS] RESULT FAILED"); System.exit(1); }

        // ---------- ① nova+crawler（=第 10 波）的巨兽摆在岩壁上：A/B ----------
        {
            Seq<Unit> us = new Seq<>();
            us.add(spawn(nova, Team.crux, rock.worldx(), rock.worldy()));
            us.add(spawn(crawler, Team.crux, rock.worldx(), rock.worldy()));
            Unit mega = merge(us);
            check("第 10 波编组能融合（前置）", mega != null && isMega(mega));
            if(mega == null){ System.out.println("[MHS] RESULT FAILED"); System.exit(1); }
            mega.set(rock.worldx(), rock.worldy());
            runReal(20, 2);

            Object field = Vars.pathfinder.getField(Team.crux, mega.type.flowfieldPathType, Pathfinder.fieldCore);
            Tile on = mega.tileOn();
            System.out.println("[MHS] 巨兽: canBoost=" + mega.type.canBoost + " flying=" + mega.isFlying()
                + " elevation=" + String.format("%.2f", mega.elevation)
                + " flowfieldPathType=" + mega.type.flowfieldPathType
                + " 脚下=" + (on == null ? "null" : on.block().name + "(solid=" + on.solid() + ")")
                + " 自己格流场权重=" + flowValue(field, on) + " 自己格可通行=" + mega.canPass(mega.tileX(), mega.tileY()));
            check("巨兽悬在岩壁上（前置：canBoost 让它在实心格上升空，不会被原版清掉）",
                mega.isFlying() && on != null && on.solid());
            check("口径钉子：它用的流场代价表把「自己脚下这格」判成不可达（死路）",
                flowValue(field, on) == -1);

            // A：原版 GroundAI（= 修复前的行为）
            mega.controller(new mindustry.ai.types.GroundAI());
            System.out.println("[MHS] A 原版 GroundAI（修复前）:");
            float oldMoved = advance(mega, core);
            check("复现用户报的现象：原版 GroundAI 在岩壁上一动不动（推进 " + (int)oldMoved + "px）", oldMoved < 32f);

            // B：巨兽自己的 AI（MegaGroundAI）
            mega.set(rock.worldx(), rock.worldy());
            mega.vel().setZero();
            mega.controller(mega.type.createController(mega));
            run(2);
            System.out.println("[MHS] B 巨兽自己的 AI（= " + mega.controller().getClass().getSimpleName() + "，修复后）:");
            float newMoved = advance(mega, core);
            check("修复后：同一只巨兽在岩壁上明显朝核心推进（推进 " + (int)newMoved + "px）", newMoved > 200f);
            mega.kill();
            run(3);
        }

        // ---------- ② 对照：不能悬空的组合落在岩壁上，原版当场清掉（不是本 bug，别误判） ----------
        {
            Seq<Unit> us = new Seq<>();
            us.add(spawn(mace, Team.crux, rock.worldx(), rock.worldy()));
            us.add(spawn(dagger, Team.crux, rock.worldx(), rock.worldy()));
            Unit mega = merge(us);
            if(mega != null){
                mega.set(rock.worldx(), rock.worldy());
                runReal(30, 2);
                System.out.println("[MHS] 对照（mace+dagger，不能悬空）: canBoost=" + mega.type.canBoost
                    + " flying=" + mega.isFlying() + " 活着=" + (mega.isAdded() && !mega.dead())
                    + " 自己格可通行=" + mega.canPass(mega.tileX(), mega.tileY()));
                check("对照：不能悬空的巨兽落在实心格上被原版当帧清掉（原版行为，不会停在原地）",
                    !mega.isAdded() || mega.dead());
            }
        }

        System.out.println("[MHS] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
