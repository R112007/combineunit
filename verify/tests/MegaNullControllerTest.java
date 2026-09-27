package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户崩溃报告（crash-report-09_27_2026_09_40_20.txt）：
 *
 * <pre>
 * java.lang.NullPointerException: Cannot invoke "UnitController.removed(Unit)" because "this.controller" is null
 *   at mindustry.gen.UnitEntity.remove(UnitEntity.java:1687)
 *   at mindustry.gen.UnitEntity.destroy(...)
 *   at mindustry.entities.Units.unitDestroy(...)
 *   at mindustry.gen.Call.unitDestroy(...)
 *   at mindustry.gen.UnitEntity.update(...)
 *   at combineunit.units.mega.MegaUnitEntity.update(MegaUnitEntity.java:2075)
 * </pre>
 *
 * 巨兽是自定义实体：客户端从快照建实体、或换派生类型时都可能没有控制器，
 * 而原版 {@code remove()} 一定要调 {@code controller.removed(this)} —— 那一刻正好死亡就是 NPE 崩游戏。
 * 本测试把控制器清空再让巨兽死亡，要求：不抛异常、干净地移除。
 */
public class MegaNullControllerTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_unit/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MNC] " + t); };
    new HeadlessApplication(new MegaNullControllerTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[MNC] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }
  static boolean isMega(Unit u) {
    return u != null && u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity");
  }
  static void setController(Unit u, mindustry.entities.units.UnitController c) {
    // 直接写字段：原版的 controller(UnitController) setter 对 null 会自己 NPE（它后面还要调 value.unit(this)），
    // 这里只是要把实体弄成"没有控制器"的状态。
    try {
      Class<?> k = u.getClass();
      java.lang.reflect.Field f = null;
      while (k != null && f == null) {
        try { f = k.getDeclaredField("controller"); } catch (NoSuchFieldException e) { k = k.getSuperclass(); }
      }
      if (f == null) throw new NoSuchFieldException("controller");
      f.setAccessible(true);
      f.set(u, c);
    }
    catch (Throwable t) { System.out.println("[MNC] 设控制器失败: " + t); }
  }
  static mindustry.entities.units.UnitController getController(Unit u) {
    try { return (mindustry.entities.units.UnitController) u.getClass().getMethod("controller").invoke(u); }
    catch (Throwable t) { return null; }
  }
  static Unit mergeAt(Class<?> mergeCls, float x, float y, UnitType... types) {
    try {
      Seq<Unit> units = new Seq<>();
      for (int i = 0; i < types.length; i++) {
        Unit u = types[i].create(Team.sharded);
        u.set(x + i * 24f, y);
        u.add();
        units.add(u);
      }
      run(2);
      return (Unit) mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
    } catch (Throwable t) { System.out.println("[MNC] 融合失败: " + t); return null; }
  }

  @Override public void init() {
    try {
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false; Vars.loadSettings(); Vars.headless = true; Vars.init();
      UI.loadColors(); Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      if (Vars.logic == null) Vars.logic = new Logic();
      if (Vars.netServer == null) Vars.netServer = new NetServer();
      if (Vars.netClient == null) Vars.netClient = new NetClient();
      ml = Vars.mods.getMod("combineunit").main.getClass().getClassLoader();
      Class<?> mergeCls = Class.forName("combineunit.units.UnitComboMerge", true, ml);

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.state.rules.waves = false;
      Vars.state.rules.canGameOver = false;
      Vars.logic.play();
      run(20);
      for (int y = 25; y < 175; y++) for (int x = 10; x < 250; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
      }
      run(5);
      mindustry.world.Build.beginPlace(null, Blocks.coreShard, Team.sharded, 60, 60, 0, null);
      mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(60, 60), Blocks.coreShard, null, (byte) 0, Team.sharded, null);
      run(10);

      // A. controller 清空 → 直接 Call.unitDestroy（= 快照里血量<=0 时原版走的那条路）
      Unit mega = mergeAt(mergeCls, 60 * 8f + 200f, 60 * 8f + 200f, UnitTypes.dagger, UnitTypes.fortress);
      if (!isMega(mega)) { System.out.println("[MNC] 没融出巨兽"); System.exit(3); }
      setController(mega, null);
      int id = mega.id;
      boolean crashed = false;
      try {
        mindustry.gen.Call.unitDestroy(id);
      } catch (Throwable t) {
        crashed = true;
        System.out.println("[MNC] Call.unitDestroy 抛了: " + t);
      }
      check("A：控制器为空的巨兽被 unitDestroy 时不抛异常", !crashed);
      run(3);
      check("A：巨兽确实被移除了", !Groups.unit.contains(u -> u.id == id));

      // B. controller 清空 → 血量打负，让它自己在 update 里死（崩溃报告里就是这条 update 路径）
      Unit mega2 = mergeAt(mergeCls, 60 * 8f + 260f, 60 * 8f + 200f, UnitTypes.dagger, UnitTypes.fortress);
      if (!isMega(mega2)) { System.out.println("[MNC] 第二次没融出巨兽"); System.exit(3); }
      setController(mega2, null);
      check("B（前置）：控制器确实是空的（" + getController(mega2) + "）", getController(mega2) == null);
      int id2 = mega2.id;
      boolean crashed2 = false;
      try {
        mega2.health = -1f;
        run(120);
      } catch (Throwable t) {
        crashed2 = true;
        System.out.println("[MNC] 死亡路径抛了: " + t);
      }
      check("B：控制器为空的巨兽自己死亡时不抛异常", !crashed2);
      check("B：巨兽被移除了（" + Groups.unit.contains(u -> u.id == id2) + "）", !Groups.unit.contains(u -> u.id == id2));

      // C. 更新里会不会自动补上控制器（不然指挥模式里它是"点不动的幽灵"）
      Unit mega3 = mergeAt(mergeCls, 60 * 8f + 320f, 60 * 8f + 200f, UnitTypes.dagger, UnitTypes.fortress);
      setController(mega3, null);
      run(2);
      check("C：update 会自动补上控制器（" + getController(mega3) + "）", getController(mega3) != null);

      System.out.println("[MNC] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }
}
