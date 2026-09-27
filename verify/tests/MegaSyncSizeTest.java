package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："客户端无法看见过多单位组合的组合体"。
 *
 * 上一轮已经知道：巨兽的网络快照**只发紧凑构成**（每个成员 id + 类型 id），
 * 因为早期发完整成员数据时"成员 &gt; 18 只"快照就有 3KB+，超过 arc 客户端的写缓冲 →
 * 整包被丢 → 非房主客户端完全看不见这只大单位。
 *
 * 这个测试量"当前实现的快照字节数随成员数怎么长"：2 / 8 / 20 / 60 / 120 / 240 只分别多少字节，
 * 并顺手验"客户端照着这份字节能不能把构成重建出来"（成员数 + 代表类型）。
 * 只要 240 只还在几百字节量级，就说明"看不见"不是快照大小的问题，得另找原因。
 */
public class MegaSyncSizeTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_unit/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MSS] " + t); };
    new HeadlessApplication(new MegaSyncSizeTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[MSS] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }
  static boolean isMega(Unit u) { return u != null && u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity"); }
  static int memberCount(Unit u) {
    try { return (Integer) u.getClass().getMethod("memberCount").invoke(u); } catch (Throwable t) { return -1; }
  }
  static Building place(Block b, int x, int y, Team team) {
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) 0, team, null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null) { try { bu.created(); } catch (Throwable ignored) {} try { bu.updateProximity(); } catch (Throwable ignored) {} }
    return bu;
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
      Vars.state.rules.unitCap = 600;
      Vars.logic.play();
      run(20);
      for (int y = 25; y < 175; y++) for (int x = 10; x < 250; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
      }
      run(5);
      place(Blocks.coreShard, 60, 60, Team.sharded);
      run(10);

      int[] sizes = {2, 8, 20, 60, 120, 240};
      for (int n : sizes) {
        // 单位排成一排（互相挨着，融合时按框选语义一次吃掉）
        Seq<Unit> units = new Seq<>();
        float bx = 60 * 8f, by = 90 * 8f;
        for (int i = 0; i < n; i++) {
          Unit u = UnitTypes.dagger.create(Team.sharded);
          u.set(bx + (i % 16) * 8f, by + (i / 16) * 8f);
          u.add();
          units.add(u);
        }
        run(2);
        Unit mega = (Unit) mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
        if (!isMega(mega)) { check("N=" + n + " 融合成功", false); continue; }
        run(2);

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        arc.util.io.Writes w = new arc.util.io.Writes(new java.io.DataOutputStream(bos));
        mega.writeSync(w);
        byte[] snap = bos.toByteArray();
        // 客户端侧：拿一份新实体照着这份字节读，看构成能不能重建
        Class<?> megaCls = Class.forName("combineunit.units.mega.MegaUnitEntity", true, ml);
        Unit client = (Unit) megaCls.getConstructor().newInstance();
        client.type(UnitTypes.dagger);
        arc.util.io.Reads r = new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(snap)));
        String err = "";
        int got = -1;
        try {
          client.readSync(r);
          got = memberCount(client);
        } catch (Throwable t) {
          err = String.valueOf(t);
        }
        System.out.println("[MSS] 成员=" + n + " 快照字节=" + snap.length
            + "（" + String.format("%.1f", snap.length / (double) n) + " B/成员） 挂载数=" + mega.mounts.length
            + " 客户端重建成员数=" + got
            + (err.isEmpty() ? "" : " 读失败=" + err));
        check("N=" + n + "：客户端照着快照能把构成读回来（" + got + "/" + n + "）", got == n);
        // 收尾：把这只巨兽拆掉，别影响下一轮
        try { mega.getClass().getMethod("kill").invoke(mega); } catch (Throwable ignored) {}
        run(2);
      }

      System.out.println("[MSS] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }
}
