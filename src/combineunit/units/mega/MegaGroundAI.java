package combineunit.units.mega;

import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.ai.types.GroundAI;
import mindustry.gen.Teamc;
import mindustry.world.Tile;

/**
 * 巨兽专用的"波次进攻"AI（派生类型的 {@code aiController}，见 {@link MegaUnitType}）。
 *
 * <p><b>为什么需要它</b>：原版 {@link GroundAI} 的移动**完全**依赖流场 ——
 * {@code AIController.pathfind()} 从 {@code Pathfinder.fieldCore} 取"下一格"，
 * 而 {@code Pathfinder.getTargetTile()} 在"当前格在自己的代价表里是 impassable（-1）"
 * 时会把**当前格**当下一格返回，紧接着 {@code AIController.pathfind()} 里那句
 * {@code if(tile == targetTile && stopAtTargetTile) return;} 直接 return ——
 * 单位一步都不走，而且只要它不离开这一格就永远走不了（每帧都同一个死循环）。
 *
 * <p>而巨兽偏偏很容易待在这种格子上：
 * <ul>
 *   <li>组里有飞行成员（`canFly`）→ 每帧 elevation 钉在 1、**永远悬空**，
 *       悬空不撞墙，所以它经常就悬在天然岩壁/悬崖那一格；</li>
 *   <li>组里有 canBoost 成员（nova / pulsar / quasar / vela）→ 派生类型继承 canBoost，
 *       原版 {@code updateBoosting()} 在实心格/深水上会让它自动悬空（isFlying=true，
 *       `solidity()==null` 所以活着），它也就能待在那样的格子上。</li>
 * </ul>
 * 这些格子在 costGround / costLegs / costHover **都是 impassable**（天然岩壁、深水），
 * 于是"合体出生点落在岩壁/深水上"（tarFields 的波次出生点就在这种格子上）时，
 * 组合巨兽永远停在原地 —— 用户报的"第 10 波（nova+crawler）/ 第 34 波（spiroct+horizon）
 * 不向玩家核心进攻"。
 *
 * <p><b>修法</b>：流场给出死路（下一格 == 当前格）时，直接朝最近的敌方核心推进 ——
 * 能悬空的巨兽这一步就脱困了，离开那一格之后流场照常接管正常寻路。
 * 正常路径（流场给得出下一格）完全走原版逻辑，地貌绕行不受影响；
 * 不能悬空的巨兽在这里被地形挡住也只是维持现状（原版本来就会把它当场清掉）。
 */
public class MegaGroundAI extends GroundAI{

    @Override
    public void pathfind(int pathTarget, boolean stopAtTargetTile, boolean avoidance){
        Tile tile = unit == null ? null : unit.tileOn();

        if(tile != null){
            // 流场认为"当前格就是能到的最近一格"= 自己脚下这格在自己的代价表里不可达（死路）
            Tile next = Vars.pathfinder.getField(unit.team, unit.type.flowfieldPathType, pathTarget).getNextTile(tile);
            if(next == tile){
                Teamc core = unit.closestEnemyCore();
                // 离核心还远才推进（贴近之后交给原版接敌逻辑）
                if(core != null && !unit.within(core, Math.max(unit.type.range * 0.5f, 40f))){
                    Vec2 move = vec.trns(unit.angleTo(core), prefSpeed());
                    unit.movePref(move);
                }
                return;
            }
        }

        super.pathfind(pathTarget, stopAtTargetTile, avoidance);
    }
}
