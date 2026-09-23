package combineunit;

import arc.util.Log;
import mindustry.mod.Mod;

/**
 * 组合单位（combineunit）：从「组合工厂 / combine」里拆出来的单位侧功能。
 *
 * 建筑侧的组合（组合工厂/钻机/炮台/仓库/连接器/节点/组合墙、内容替换器 Replacer 等）留在
 * combine 仓库；这里只放**单位**相关的机制：
 *   · 单位合体（组合巨兽）：UnitComboMerge / units/mega
 *   · 共享承伤 / 共享火力 / 手动编组 UI：UnitComboDamage / UnitComboFire / UnitComboBind
 *   · 单位自定义实体类（组合单位工厂造出来的那些）：units/entities
 *   · 合体指令的网络包：MegaOrderPacket
 */
public class Main extends Mod {

  @Override
  public void init() {
    Log.info("[combineunit] 组合单位已加载（拆分进行中：机制本体正在从 combine 搬过来）");
  }
}
