package combineunit;

import combineunit.units.UnitComboBind;
import combineunit.units.UnitComboDamage;
import combineunit.units.UnitComboFire;
import combineunit.units.UnitComboMerge;
import arc.util.Log;
import mindustry.Vars;
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
    // 组合单位共享承伤/修复：用镜像实体类替换全部原版单位实体，拦截 rawDamage/heal
    // 按"同 comboId 同队伍且在范围内"的组合成员当前生命占比分摊。
    // 必须在 content 装配之后、任何单位被造出来之前跑：替换的是
    // UnitType.constructor 与 EntityMapping（网络重建/存档读回都按它走）。
    UnitComboDamage.register();

    // 组合火力共享：正在开火的单位可调用组里空闲单位的就绪武器代打（治疗类武器不代打）。
    UnitComboFire.register();

    // 手动编组 UI：指挥模式下选中己方单位后，命令按钮区出现"组合"按钮
    // （无头服务端没有 UI，跳过）。
    if (!Vars.headless)
      UnitComboBind.register();

    // 组合巨兽：单位组（或一批相邻单位）可融合为单一巨兽——血量/护甲/护盾/武器/能力
    // 全部叠加保留，贴图取数量最多的成员类型并按综合 hitSize 缩放，飞行/海军形态按
    // 成员构成判定；点选巨兽可随时解体。类型与实体 ID 登记（固定槽 250）
    // 必须服务端/客户端都执行。
    UnitComboMerge.register();

    Log.info("[combineunit] 组合单位已加载：共享承伤/火力、手动编组、组合巨兽（实体槽 @）",
        UnitComboMerge.MEGA_ENTITY_SLOT);
  }
}
