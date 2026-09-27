package combineunit;

import arc.Core;
import arc.Events;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Groups;
import mindustry.gen.Player;

/**
 * 防闪退兜底：**玩家名字不能是 null**。
 *
 * <p>crash_1790334593570.txt（安卓客户端）的堆栈是
 * {@code MinimapRenderer.drawEntities → drawLabel → GlyphLayout.setText(null)} ——
 * 小地图给玩家画名字那一段直接拿 {@code player.name} 去排版，
 * 只要 {@code Groups.player} 里有一个"名字还没填"的 Player（设置里没存 name、
 * 联机里玩家对象刚建好还没赋值、别的模组造的临时 Player…），
 * {@code CharSequence.length()} 就 NPE 闪退。这一段是游戏自己的代码，我们改不了，
 * 所以在自己这边兜住数据：**任何时候都不让 Groups.player 里出现空名字**。
 *
 * <p>成本：玩家最多十几个，每 20 帧扫一遍（外加加入/读档时立刻扫一次）。
 */
public class UnitComboGuard {
  static float timer = 0f;

  public static void register() {
    // 服务端没有小地图，不用白跑（函数本身两端都能调，便于测试）
    if (Vars.headless)
      return;
    Events.run(Trigger.update, UnitComboGuard::update);
    Events.on(PlayerJoin.class, e -> fixPlayerNames());
    Events.on(WorldLoadEvent.class, e -> fixPlayerNames());
  }

  static void update() {
    timer -= Time.delta;
    if (timer > 0f)
      return;
    timer = 20f;
    fixPlayerNames();
  }

  /** 给所有"名字为 null 或空"的玩家补一个名字（本地玩家优先用设置里的名字）。 */
  public static void fixPlayerNames() {
    try {
      if (Groups.player == null)
        return;
      for (Player p : Groups.player) {
        if (p == null)
          continue;
        if (p.name != null && !p.name.isEmpty())
          continue;
        String fallback = p == Vars.player ? Core.settings.getString("name", "Player") : null;
        if (fallback == null || fallback.isEmpty())
          fallback = "player-" + p.id;
        p.name = fallback;
        Log.warn("[combineunit] 玩家 @ 没有名字（小地图画名字会闪退），已兜底为「@」", p.id, fallback);
      }
    } catch (Throwable t) {
      Log.err("[combineunit] 玩家名字体检失败（不影响其它功能）", t);
    }
  }
}
