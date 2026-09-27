package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户安卓崩溃报告（多人游戏里有人合体时爆）：
 * <pre>
 * java.lang.NullPointerException: Attempt to read from field 'boolean mindustry.type.UnitType.allowLegStep'
 *   at mindustry.gen.UnitEntity.collisionLayer(UnitEntity.java:3)
 *   at mindustry.async.PhysicsProcess.begin(PhysicsProcess.java:176)
 * </pre>
 *
 * 根因：巨兽是自定义实体，客户端从快照建实体、或成员构成整块读不出来时
 * {@code type} 可能一直是 null；而原版物理线程每帧都会读 {@code unit.type.allowLegStep}。
 *
 * 判定：① type 被清空的巨兽调 collisionLayer() 不许抛；② 拿"type=null 的服务端"写的快照
 * （类型 id 写成 -1）在客户端侧读回来，type 必须被兜成非 null；③ 空成员表的巨兽
 * refreshDerived 之后 type 也不许是 null。
 */
public class MegaNullTypeTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_unit/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[MNT] " + t); };
    new HeadlessApplication(new MegaNullTypeTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[MNT] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }
  static Class<?> megaCls() throws Exception { return Class.forName("combineunit.units.mega.MegaUnitEntity", true, ml); }
  static Unit newMega(Team team) throws Exception {
    Class<?> mc = megaCls();
    Object inst = mc.getConstructor().newInstance();
    Unit u = (Unit) inst;
    u.team(team);
    return u;
  }
  static void setTypeNull(Unit u) throws Exception {
    for (Class<?> k = u.getClass(); k != null; k = k.getSuperclass()) {
      try { java.lang.reflect.Field f = k.getDeclaredField("type"); f.setAccessible(true); f.set(u, null); return; }
      catch (NoSuchFieldException ignored) { }
    }
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

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.logic.play();
      run(20);

      // ① type=null 的巨兽：collisionLayer() 不许抛（物理线程就是这么调的）
      Unit anon = newMega(Team.sharded);
      setTypeNull(anon);
      int layer = -1;
      String err = "";
      try { layer = anon.collisionLayer(); } catch (Throwable t) { err = String.valueOf(t); }
      System.out.println("[MNT] type=null 的巨兽: collisionLayer()=" + layer + (err.isEmpty() ? "" : " 抛了=" + err));
      check("① type=null 时 collisionLayer() 不抛异常", err.isEmpty());
      check("① 兜底后 type 不再为 null（" + (anon.type == null ? "null" : anon.type.name) + "）", anon.type != null);

      // ② 服务端把 type 写成 null 的快照 → 客户端读回来不许留 null type
      Unit server = newMega(Team.sharded);
      server.set(60 * 8f, 60 * 8f);
      server.add();
      run(2);
      setTypeNull(server);
      java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
      arc.util.io.Writes w = new arc.util.io.Writes(new java.io.DataOutputStream(bos));
      server.writeSync(w);
      byte[] snap = bos.toByteArray();
      Unit client = newMega(Team.sharded);
      String err2 = "";
      try {
        client.readSync(new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(snap))));
      } catch (Throwable t) { err2 = String.valueOf(t); }
      System.out.println("[MNT] 快照（类型 id=-1）字节=" + snap.length + " 客户端读回后 type="
          + (client.type == null ? "null" : client.type.name) + (err2.isEmpty() ? "" : " 读失败=" + err2));
      check("② 客户端读完这种快照后 type 不是 null", client.type != null);
      int layer2 = -1; String err3 = "";
      try { layer2 = client.collisionLayer(); } catch (Throwable t) { err3 = String.valueOf(t); }
      check("② 客户端 collisionLayer() 不抛（" + layer2 + "）", err3.isEmpty());
      try { server.remove(); } catch (Throwable ignored) { }

      // ③ 空成员表的巨兽 refreshDerived 之后 type 也不许是 null
      Unit empty = newMega(Team.sharded);
      empty.type(null);
      try { empty.getClass().getMethod("refreshDerived", boolean.class).invoke(empty, true); } catch (Throwable t) { }
      System.out.println("[MNT] 空成员 refreshDerived 后 type=" + (empty.type == null ? "null" : empty.type.name));
      check("③ 空成员表 refreshDerived 后 type 不是 null", empty.type != null);

      System.out.println("[MNT] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }
}
