package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.ai.UnitCommand; import mindustry.ai.types.CommandAI;

/**
 * 用户安卓崩溃报告：
 *
 * <pre>
 * java.lang.NullPointerException: Attempt to read from field 'mindustry.game.Team mindustry.gen.Unit.team'
 *   on a null object reference
 *   at mindustry.ai.types.BuilderAI.useFallback(BuilderAI.java:3)
 *   at mindustry.entities.units.AIController.updateUnit(AIController.java:1)
 *   at mindustry.ai.types.CommandAI.updateUnit(CommandAI.java:199)
 *   at mindustry.gen.UnitEntity.update(UnitEntity.java:1852)
 *   at combineunit.units.mega.MegaUnitEntity.update(MegaUnitEntity.java:2075)
 * </pre>
 *
 * 根因：CommandAI.updateUnit 的命令控制器（rebuild/assist → BuilderAI）用的 `unit` 是
 * CommandAI 自己的 `unit` 字段：
 * <pre>
 * if(commandController != null){
 *     if(commandController.unit() != unit) commandController.unit(unit);   // unit 为 null → 两个都 null，跳过
 *     commandController.updateUnit();                                       // BuilderAI.useFallback() 读 unit.team → NPE
 * }
 * </pre>
 * 只要巨兽的控制器（CommandAI）`unit` 为 null，且它挂着建造类指令（成员里有 poly 这类工程单位），
 * 这一帧就必崩。`ensureController()` 原来只判 `controller() != null`，查不出"控制器在、但没挂 unit"
 * 这种半成品状态（网络读回来的 CommandAI、或构造中途异常留下的控制器都会这样）。
 *
 * 本测试把巨兽弄成这种状态再 tick，要求：不崩、且这一帧内把控制器挂回巨兽本身。
 */
public class MegaBuilderAiNpeTest implements ApplicationListener{
  static String dataDir = "/tmp/mp_unit/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  public static void main(String[] a){
    if(a.length > 0) dataDir = a[0];
    Vars.platform = new Platform(){};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if(l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MBA] " + t); };
    new HeadlessApplication(new MegaBuilderAiNpeTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok){ System.out.println("[MBA] " + (ok ? "PASS " : "FAIL ") + n); if(ok) pass++; else fail++; }
  static void run(int f){ for(int i = 0; i < f; i++){ Time.delta = 1f; Vars.logic.update(); } }
  static boolean isMega(Unit u){ return u != null && u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity"); }

  static void setController(Unit u, mindustry.entities.units.UnitController c){
    try{
      Class<?> k = u.getClass(); java.lang.reflect.Field f = null;
      while(k != null && f == null){ try{ f = k.getDeclaredField("controller"); }catch(NoSuchFieldException e){ k = k.getSuperclass(); } }
      f.setAccessible(true); f.set(u, c);
    }catch(Throwable t){ System.out.println("[MBA] 设控制器失败: " + t); }
  }

  static Unit mergeAt(Class<?> mergeCls, float x, float y, UnitType... types){
    try{
      Seq<Unit> units = new Seq<>();
      for(int i = 0; i < types.length; i++){
        Unit u = types[i].create(Team.sharded);
        u.set(x + i * 24f, y);
        u.add();
        units.add(u);
      }
      run(2);
      return (Unit)mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
    }catch(Throwable t){ System.out.println("[MBA] 融合失败: " + t); return null; }
  }

  @Override public void init(){
    try{
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
      UI.loadColors(); Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      if(Vars.logic == null) Vars.logic = new Logic();
      if(Vars.netServer == null) Vars.netServer = new NetServer();
      if(Vars.netClient == null) Vars.netClient = new NetClient();
      ml = Vars.mods.getMod("combineunit").main.getClass().getClassLoader();
      Class<?> mergeCls = Class.forName("combineunit.units.UnitComboMerge", true, ml);

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.state.rules.waves = false;
      Vars.state.rules.canGameOver = false;
      Vars.logic.play();
      run(20);
      for(int y = 25; y < 175; y++) for(int x = 10; x < 250; x++){
        Tile t = Vars.world.tile(x, y);
        if(t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
      }
      run(5);
      mindustry.world.Build.beginPlace(null, Blocks.coreShard, Team.sharded, 60, 60, 0, null);
      mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(60, 60), Blocks.coreShard, null, (byte)0, Team.sharded, null);
      run(10);

      // dagger + poly：poly 是工程单位（buildSpeed>0）→ 派生类型会并进 rebuild/assist 指令
      Unit mega = mergeAt(mergeCls, 60 * 8f + 200f, 60 * 8f + 200f, UnitTypes.dagger, UnitTypes.poly);
      if(!isMega(mega)){ System.out.println("[MBA] 没融出巨兽"); System.exit(3); }
      boolean hasRebuild = mega.type.commands.contains(UnitCommand.rebuildCommand);
      check("前置：巨兽指令表含「自动重建」(rebuildCommand) → 命令控制器是 BuilderAI", hasRebuild);

      // A. 把它弄成"控制器在、但 unit 为 null"的半成品状态（网络读回来的 CommandAI / 读快照中途异常的残留）
      setController(mega, new CommandAI());
      CommandAI cai = (CommandAI)mega.controller();
      check("A：前置 —— 控制器是 CommandAI 且 unit() 为空（" + cai.unit() + "）", cai.unit() == null);
      cai.command = UnitCommand.rebuildCommand;
      boolean crashed = false;
      try{
        run(5);
      }catch(Throwable t){
        crashed = true;
        System.out.println("[MBA] A：tick 抛异常了: " + t);
      }
      check("A：unit 为空的 CommandAI 挂着建造指令 tick 不崩（旧版 BuilderAI.useFallback 读 unit.team NPE）", !crashed);
      check("A：tick 后控制器已挂回巨兽（" + mega.controller().unit() + "）", mega.controller() != null && mega.controller().unit() == mega);

      // B. 控制器彻底为空的老路径照旧兜底（不能因为这次改动回退）
      Unit mega2 = mergeAt(mergeCls, 60 * 8f + 300f, 60 * 8f + 200f, UnitTypes.dagger, UnitTypes.poly);
      setController(mega2, null);
      run(2);
      check("B：控制器为空时 update 会自动补上控制器（" + mega2.controller() + "）", mega2.controller() != null && mega2.controller().unit() == mega2);

      System.out.println("[MBA] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
  }
}
