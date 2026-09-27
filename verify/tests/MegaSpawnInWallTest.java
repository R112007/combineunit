package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log; import arc.util.Time;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.UnitType; import mindustry.ui.Fonts; import mindustry.world.*; import mindustry.world.blocks.defense.Wall;

/**
 * 用户报："融合单位的融合后的出生点在墙里就被闷死"。
 *
 * 根因：巨兽原来直接生成在成员坐标的**平均点**（UnitComboMerge.mergeMembers），
 * 而原版 UnitComp.update 有一条硬规则 ——
 * {@code if(tile != null && !canPassOn()){ if(type.canBoost) elevation = 1f; else kill(); }}
 * 也就是"脚下那格对该单位不可通行就直接 kill()"。两个地面单位隔着一堵墙融合时，
 * 平均点正好落在墙格里 → 巨兽当帧被清掉。
 *
 * 判定：①隔墙融合后巨兽必须活着、脚下那格对它可通行；②挪出来的落点必须在墙外（不在墙上）；
 * ③跑 120 帧还活着（不是"当帧活着、下一帧被闷死"）。
 */
public class MegaSpawnInWallTest implements ApplicationListener {
  static String dataDir = "/tmp/mp_unit/data";
  static int pass = 0, fail = 0;
  static ClassLoader ml;
  public static void main(String[] a) {
    if (a.length > 0) dataDir = a[0];
    Vars.platform = new Platform() {};
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (l, t) -> { if (l.ordinal() >= arc.util.Log.LogLevel.info.ordinal()) System.out.println("[MSW] " + t); };
    new HeadlessApplication(new MegaSpawnInWallTest(), t -> t.printStackTrace());
  }
  static void check(String n, boolean ok) { System.out.println("[MSW] " + (ok ? "PASS " : "FAIL ") + n); if (ok) pass++; else fail++; }
  static void run(int f) { for (int i = 0; i < f; i++) { Time.delta = 1f; Vars.logic.update(); } }
  static boolean isMega(Unit u) { return u != null && u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity"); }
  static Building place(Block b, int x, int y, Team team) {
    int size = Math.max(b.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte) 0, team, null);
    Building bu = Vars.world.build(ax, ay);
    if (bu != null) { try { bu.created(); } catch (Throwable ignored) {} try { bu.updateProximity(); } catch (Throwable ignored) {} }
    return bu;
  }
  static Unit spawn(UnitType t, float x, float y) {
    Unit u = t.create(Team.sharded);
    u.set(x, y);
    u.add();
    return u;
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
      Vars.state.rules.unitCap = 200;
      Vars.logic.play();
      run(20);
      for (int y = 25; y < 175; y++) for (int x = 10; x < 250; x++) {
        Tile t = Vars.world.tile(x, y);
        if (t != null && t.block() != Blocks.air) t.setBlock(Blocks.air);
      }
      run(5);
      place(Blocks.coreShard, 60, 60, Team.sharded);
      run(10);

      // 两台单位贴在墙两边：平均点必然落在墙格里。
      // 【先放单位、按它们**实际**站的行铺墙】单位出生后自己会挪一点点，
      // 按写死的行铺墙会对不上（第一版测试就在这儿假失败）。
      Block wall = Blocks.copperWall;
      int wx = 60, wy = 90;
      float leftX = (wx - 2) * 8f + 4f, rightX = (wx + 6) * 8f + 4f, uy = wy * 8f + 4f;
      Unit a = spawn(UnitTypes.dagger, leftX, uy);
      Unit b = spawn(UnitTypes.dagger, rightX, uy);
      run(5);
      int row = mindustry.core.World.toTile((a.y + b.y) / 2f);
      for (int i = 0; i < 5; i++) place(wall, wx + i, row, Team.sharded);
      run(5);
      int midTile = mindustry.core.World.toTile((a.x + b.x) / 2f);
      Tile mid = Vars.world.tile(midTile, row);
      System.out.println("[MSW] 融合前: A@(格 " + mindustry.core.World.toTile(a.x) + "," + mindustry.core.World.toTile(a.y)
          + ") B@(格 " + mindustry.core.World.toTile(b.x) + "," + mindustry.core.World.toTile(b.y)
          + ") 墙行=" + row + " 平均点格=(" + midTile + "," + row + ") 那格是墙="
          + (mid != null && mid.solid()));
      check("（前置）平均点确实落在实心格里（复现条件）", mid != null && mid.solid());

      Seq<Unit> units = new Seq<>();
      units.add(a); units.add(b);
      Unit mega = (Unit) mergeCls.getMethod("mergeSelected", Seq.class).invoke(null, units);
      if (!isMega(mega)) { System.out.println("[MSW] 没融出巨兽"); System.exit(3); }
      run(1);
      Tile on = Vars.world.tile(mega.tileX(), mega.tileY());
      boolean passable = mega.canPass(mega.tileX(), mega.tileY());
      System.out.println("[MSW] 融合后: 巨兽@(格 " + mega.tileX() + "," + mega.tileY() + ") 血量=" + (int) mega.health()
          + "/" + (int) mega.maxHealth() + " 那格实心=" + (on != null && on.solid()) + " 对它可通行=" + passable
          + " 已死=" + mega.dead + " 在组里=" + Groups.unit.contains(u -> u == mega));
      // 诊断：移动模式/能否飞/脚下那格判定（排查"到底哪条规则会处决它"）
      try {
        var megaCls = mega.getClass();
        System.out.println("[MSW] 诊断: hasGround=" + megaCls.getMethod("hasGround").invoke(mega)
            + " hasNaval=" + megaCls.getMethod("hasNaval").invoke(mega)
            + " canFly=" + megaCls.getMethod("canFly").invoke(mega)
            + " moveMode=" + megaCls.getMethod("moveMode").invoke(mega)
            + " elevation=" + mega.elevation() + " isFlying=" + mega.isFlying()
            + " canDrown=" + mega.canDrown());
      } catch (Throwable t) {
        System.out.println("[MSW] 诊断失败: " + t);
      }
      check("融合后的巨兽没被闷死（在组里、" + (int) mega.health() + " 血)", !mega.dead && Groups.unit.contains(u -> u == mega));
      check("落点对它可通行（不在实心格/水里）", passable && !(on != null && on.solid()));
      run(120);
      boolean alive = Groups.unit.contains(u -> u == mega) && !mega.dead;
      System.out.println("[MSW] 120 帧后: 还在组里=" + Groups.unit.contains(u -> u == mega) + " 血量=" + (int) mega.health()
          + " 位置格=" + mega.tileX() + "," + mega.tileY());
      check("120 帧后巨兽还活着（不是当帧活着、下一帧被闷死）", alive);

      // 【反向验证：证明"闷死"就是原版对"脚下那格不可通行"的处决】
      // 把这只巨兽放回成员的平均点（= 墙格里）跑两帧：原版 UnitComp 会当场 kill 它。
      // 这条不是修复目标，是"机制证据"——说明上面那次挪位确实救了一条命。
      try {
        float midX = (mega.tileX() + 0.5f) * 8f, midY = (mega.tileY() + 0.5f) * 8f;
        mega.set((midTile + 0.5f) * 8f, (row + 0.5f) * 8f);
        run(2);
        boolean died = mega.dead || !Groups.unit.contains(u -> u == mega);
        // 【只记录不判定】原版那条 kill 是"脚下那格不可通行就 kill()"，但碰撞解算可能先把单位
        // 顶到旁边的空格（这堵墙只有 5 格宽，两侧都是空地），所以这里不写死期望值 ——
        // 用户踩到的是"顶不出去"的场合（墙/水把它困死）。记录实际结果供对照。
        System.out.println("[MSW] 反向验证（仅记录）: 把巨兽硬放回墙格(" + midTile + "," + row + ") 跑 2 帧 → 死了=" + died
            + " 现在位置格=" + mega.tileX() + "," + mega.tileY()
            + "（原版规则 = kill entities on tiles that are solid to them）");
      } catch (Throwable t) {
        System.out.println("[MSW] 反向验证失败: " + t);
      }

      System.out.println("[MSW] RESULT " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " (pass=" + pass + ")");
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
  }
}
