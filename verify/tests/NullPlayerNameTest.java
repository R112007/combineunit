package combineunit.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.Logic;
import mindustry.core.NetClient;
import mindustry.core.NetServer;
import mindustry.core.Platform;
import mindustry.core.UI;
import mindustry.game.Gamemode;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.net.Net;
import mindustry.ui.Fonts;

/**
 * crash_1790334593570.txt（安卓客户端）：
 * {@code MinimapRenderer.drawEntities → drawLabel(player.name) → GlyphLayout.setText(null)} 直接闪退。
 * 原版那条路只会拿 {@code player.name} 去排版，null 就 NPE —— 游戏代码改不了，
 * 我们兜住数据侧：{@link combineunit.UnitComboGuard#fixPlayerNames()} 保证 Groups.player 里没有空名字。
 *
 * 本测试：造一个 name=null 的玩家（模拟"玩家对象刚建好还没填名字"）+ 一个正常玩家 →
 * 调兜底 → 断言①空名字被补上；②正常名字原样不动；③整个 Groups.player 里再没有 null/空名字
 * （= 小地图那条 drawLabel 路径不可能再拿到 null）。
 */
public class NullPlayerNameTest implements arc.ApplicationListener {
  static String dataDir = "/tmp/mp_unit/data";
  static int pass = 0, fail = 0;

  public static void main(String[] a) {
    if (a.length > 0)
      dataDir = a[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> {
      if (l.ordinal() >= arc.util.Log.LogLevel.err.ordinal())
        System.out.println("[NPN] " + t);
    };
    new HeadlessApplication(new NullPlayerNameTest(), t -> t.printStackTrace());
  }

  static void check(String n, boolean ok) {
    System.out.println("[NPN] " + (ok ? "PASS " : "FAIL ") + n);
    if (ok)
      pass++;
    else
      fail++;
  }

  static ClassLoader ml() {
    return Vars.mods.getMod("combineunit").main.getClass().getClassLoader();
  }

  @Override
  public void init() {
    try {
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false;
      Vars.loadSettings();
      Vars.headless = true;
      Vars.init();
      UI.loadColors();
      Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent();
      Vars.mods.loadScripts();
      Vars.content.createModContent();
      Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      if (Vars.logic == null)
        Vars.logic = new Logic();
      if (Vars.netServer == null)
        Vars.netServer = new NetServer();
      if (Vars.netClient == null)
        Vars.netClient = new NetClient();
      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();

      Class<?> guard = Class.forName("combineunit.UnitComboGuard", true, ml());
      java.lang.reflect.Method fix = guard.getMethod("fixPlayerNames");

      // 一个"名字还没填"的玩家 + 一个正常玩家
      Player nameless = Player.create();
      nameless.name = null;
      nameless.add();
      Player named = Player.create();
      named.name = "正常玩家";
      named.add();
      check("前置：Groups.player 里确实有一个 null 名字（这就是会闪退的状态）",
          nameless.id >= 0 && Groups.player.contains(p -> p == nameless) && nameless.name == null);

      fix.invoke(null);

      System.out.println("[NPN] 兜底后: 空名字玩家 → 「" + nameless.name + "」；正常玩家仍是「" + named.name + "」");
      check("空名字被补上（不再为 null / 空）", nameless.name != null && !nameless.name.isEmpty());
      check("本来有名字的不动", "正常玩家".equals(named.name));

      boolean anyNull = false;
      for (Player p : Groups.player)
        if (p != null && (p.name == null || p.name.isEmpty()))
          anyNull = true;
      check("整个 Groups.player 里再没有 null/空名字（小地图 drawLabel 拿不到 null）", !anyNull);

      // 顺带钉子：这条崩溃链的入口是 GlyphLayout.setText(null)，这里直接钉住"数据侧不为空"
      boolean glyphSafe = true;
      for (Player p : Groups.player)
        if (p != null && p.name == null)
          glyphSafe = false;
      check("GlyphLayout.setText(player.name) 的前置数据安全", glyphSafe);

      System.out.println("[NPN] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
