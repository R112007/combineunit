package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.ui.Fonts; import mindustry.type.Weapon; import mindustry.world.*;
import mindustry.entities.units.WeaponMount;

/**
 * 用户崩溃报告（crash-report-09_27_2026_15_52_05.txt）：
 * <pre>
 * java.lang.NullPointerException: Cannot store to float array because "mount.recoils" is null
 *   at combineunit.units.UnitComboFire.spawnBullet(UnitComboFire.java:366)
 *   at combineunit.units.UnitComboFire.fire(...)
 * </pre>
 *
 * 根因：原版是在 {@code Weapon.update()} 里现建 {@code mount.recoils}
 * （{@code if(mount.recoils == null) mount.recoils = new float[recoils];}），
 * 而借火用的是**同组成员的挂座** —— 成员已经被合体收进巨兽体内、不再走自己的 Weapon.update，
 * 数组就一直是 null；武器带多管（{@code recoils > 0}）时直接写就 NPE 崩游戏。
 *
 * 判定：挂座 recoils 为 null 时借火不许崩，并且要顺手把数组建出来。
 */
public class ComboFireRecoilTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_cj/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.err.ordinal()) System.out.println("[CFR] " + t); };
    new HeadlessApplication(new ComboFireRecoilTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[CFR] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }

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
      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      Vars.state.rules.waves = false;
      Vars.state.rules.canGameOver = false;
      Vars.state.rules.unitCap = 100;
      run(20);
      for (int y = 25; y < 175; y++) for (int x = 10; x < 250; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
      }
      run(5);
      mindustry.world.Build.beginPlace(null, Blocks.coreShard, Team.sharded, 60, 60, 0, null);
      mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(60, 60), Blocks.coreShard, null, (byte) 0, Team.sharded, null);
      run(10);

      // 造一把"多管武器"（recoils > 0）——原版只有炮塔带这个，模组单位武器也会有
      mindustry.type.Weapon w = new mindustry.type.Weapon("combo-fire-recoil-test");
      w.recoils = 2;
      w.reload = 10f;
      w.x = 0f;
      w.y = 0f;
      // 160.1 里 Bullets 只是个占位壳：借 duo 的铜弹当子弹模板
      mindustry.world.blocks.defense.turrets.ItemTurret duo = (mindustry.world.blocks.defense.turrets.ItemTurret) Blocks.duo;
      w.bullet = duo.ammoTypes.get(Items.copper);

      Unit u = UnitTypes.dagger.create(Team.sharded);
      u.set(60 * 8f + 100f, 60 * 8f + 100f);
      u.add();
      run(2);
      WeaponMount mount = new WeaponMount(w);
      mount.aimX = u.x + 40f;
      mount.aimY = u.y;
      mount.recoils = null;   // = 成员被收进巨兽体内、自己不再 update 时的样子

      Class<?> fireCls = Class.forName("combineunit.units.UnitComboFire", true, ml);
      java.lang.reflect.Method fireM = fireCls.getDeclaredMethod("fire", Unit.class, Weapon.class, WeaponMount.class);
      fireM.setAccessible(true);
      boolean crashed = false;
      String err = "";
      try {
        fireM.invoke(null, u, w, mount);
      } catch (Throwable t) {
        crashed = true;
        err = String.valueOf(t.getCause() == null ? t : t.getCause());
      }
      System.out.println("[CFR] 借火（多管武器、挂座 recoils=null）: 崩了=" + crashed
          + " 挂座 recoils=" + (mount.recoils == null ? "null" : "长度 " + mount.recoils.length)
          + (crashed ? " err=" + err : ""));
      check("多管武器 + 挂座 recoils 为 null 时借火不崩（用户崩溃报告的 NPE）", !crashed);
      check("顺手把 mount.recoils 建出来了（长度 " + (mount.recoils == null ? -1 : mount.recoils.length) + "）",
          mount.recoils != null && mount.recoils.length == 2);

      System.out.println("[CFR] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }
}
