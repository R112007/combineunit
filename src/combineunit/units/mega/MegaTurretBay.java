package combineunit.units.mega;

import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.World;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Player;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.ContinuousLiquidTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.blocks.defense.turrets.PayloadAmmoTurret;
import mindustry.world.blocks.defense.turrets.Turret;

import static mindustry.Vars.*;

/**
 * 组合巨兽的<b>炮台舱</b>：玩家点巨兽上的「选取炮台」、再点某座炮台的「添加」时
 * （见 {@code MegaTurretPicker}），把那一座炮台搬进巨兽内部的一个小 World 里，
 * 炮台跟着巨兽移动/旋转并照常开火（参考 ~/sd/q 里 WorldUnit 的"内部世界"做法）。
 *
 * <h3>机制</h3>
 * <ul>
 *     <li>内部世界 {@link #innerWorld} 是一个 {@link World}，只有炮台摆在里面。吸收时把真实世界
 *         的 {@link Building} 整个搬进去：先 {@link Tile#setBlock} 成 air（走原版流程：onRemoved、
 *         从 Groups.build / 队伍索引里摘掉），再<b>绕过 setBlock</b>手动把内部格指给它
 *         （{@link Tile#updateBlockReference} + {@link Tile#build}）——不触发事件、不再进 Groups、
 *         也不污染队伍的 buildingTree/turretTree（否则会在地图角落留下一堆幽灵索敌目标）。</li>
 *     <li>每 tick 把内部世界坐标按巨兽朝向投影到世界坐标（{@link #project}，和原版武器挂载同一套
 *         {@code Angles.trns(rotation - 90)} 口径），再调 {@link Building#update()}。
 *         {@code Vars.world} 全程保持真实世界，这样索敌（Units.closestEnemy）、起火、灭火判定
 *         用的都是真实坐标。</li>
 *     <li><b>补给</b>：物品炮台的弹药从队伍核心里扣（{@link #feedFromCore}，每 tick 只补到一半上限、
 *         一次最多 2 个料，省着用核心库存）；其它炮台（液体/电力类）直接补给
 *         （{@code power.status = 1}、液体直接加满）。</li>
 *     <li><b>摆放布局</b>（用户要求"和武器一样"）：每次吸收后 {@link #relayout()} 重摆全部炮台 ——
 *         <b>偶数座两边各一半（左右严格镜像）、奇数座多出来的那一座在正中间</b>；横向/前后用的是
 *         和武器挂载同一套坐标（内部世界 x 是横向、y 是前后）。</li>
 *     <li><b>解体/释放</b>：把炮台搬回真实世界（{@link #releaseAll}，用标准 setBlock 重新登记），
 *         落点优先巨兽附近，被占了就螺旋外扩。</li>
 * </ul>
 *
 * <h3>同步/存档</h3>
 * 快照只发"哪些炮台摆在哪"（每座 6 字节：类型 id + 朝向 + 内部坐标），客户端照着重建一份本地
 * 副本自己跑（和原版建筑一样：客户端各自模拟用来出子弹特效，服务端结算伤害）；
 * 存档多写血量与弹药。两端都装本模组即天然一致。
 *
 * <h3>已知限制</h3> 不吸收 {@link PayloadAmmoTurret}（它的弹药是单位，没法补给）；
 * 炮台数上限 = {@link #maxTurrets()}（这具巨兽"所有成员单位的武器数量之和"，见那里的说明），
 * 另有 {@link #HARD_MAX_TURRETS} 这道硬闸；内部世界边长上限 {@link #MAX_GRID}。
 */
public class MegaTurretBay{
    /**
     * 携带炮台数的**硬上限**（最后一道闸）。
     * 正常上限由 {@link #maxTurrets()}（= 巨兽身上所有成员单位的武器数量之和）决定，
     * 这里只是防"成员极多/武器极多"时单只巨兽拖垮帧率（每座炮台每帧都要 update + 绘制）。
     */
    public static final int HARD_MAX_TURRETS = 64;
    /** 内部世界边长上下限（格）。按巨兽体型算，一般在 4~16。 */
    public static final int MIN_GRID = 4, MAX_GRID = 64;

    private static final byte TURRET_TAG = 0x54;          // 'T'
    private static final byte VER_COMPACT = 1, VER_FULL = 2;
    /** 带"弹药禁用表"的两档（用户 2026-10-08 要求面板可选）：3=紧凑、4=完整。 */
    private static final byte VER_TUNE_COMPACT = 3, VER_TUNE_FULL = 4;

    private static boolean vFull(byte ver){
        return ver == VER_FULL || ver == VER_TUNE_FULL;
    }

    private static boolean vTune(byte ver){
        return ver == VER_TUNE_COMPACT || ver == VER_TUNE_FULL;
    }

    private final MegaUnitEntity mega;
    private World innerWorld;
    private int grid = 0;
    private final Seq<Building> turrets = new Seq<>();
    /**
     * 玩家在组合面板里勾掉的"不用"弹药（默认空 = 全用）。
     * 会跟着快照/存档走（见 {@link #write}），所以联机两端、读档之后都是同一份。
     */
    private final arc.struct.ObjectSet<Item> bannedAmmo = new arc.struct.ObjectSet<>();
    /** 每座物品炮台"上一次喂到弹药表的第几个"（让它按弹药表循环着打，而不是只用一种）。 */
    private final arc.struct.ObjectMap<Building, Integer> ammoCursor = new arc.struct.ObjectMap<>();
    /** 当前内部世界是按哪个构成签名建出来的，变了才重建（客户端每个快照都会调 read）。 */
    private transient int builtSig = -1;

    public MegaTurretBay(MegaUnitEntity mega){
        this.mega = mega;
    }

    public int size(){
        return turrets.size;
    }

    public boolean isEmpty(){
        return turrets.isEmpty();
    }

    public Seq<Building> all(){
        return turrets;
    }

    /** 面板里可选的弹药（所有物品炮台弹药表的并集），按内容 id 排序 → 列表稳定。 */
    public Seq<Item> ammoList(){
        Seq<Item> out = new Seq<>();
        for(Building b : turrets){
            if(b instanceof ItemTurret.ItemTurretBuild && b.block instanceof ItemTurret it){
                for(Item c : it.ammoTypes.keys()){
                    if(!out.contains(c, true)) out.add(c);
                }
            }
        }
        out.sort((a, c) -> Integer.compare(a.id, c.id));
        return out;
    }

    /** 玩家勾掉的"不用"弹药集合（面板直接改它；联机时服务端改完随快照下来）。 */
    public arc.struct.ObjectSet<Item> bannedAmmo(){
        return bannedAmmo;
    }

    /** 这座炮台能用的弹药：炮台自己的弹药表里，去掉玩家勾掉的。 */
    private Seq<Item> usableAmmo(ItemTurret it){
        Seq<Item> out = new Seq<>();
        for(Item c : it.ammoTypes.keys()){
            if(!bannedAmmo.contains(c)) out.add(c);
        }
        return out;
    }

    public int grid(){
        return grid;
    }

    /**
     * 这具巨兽最多能带几座炮台 = <b>它的所有成员单位的武器数量之和</b>
     * （用户 2026-10-08 口径："把最大舱位调成组合巨兽里所有单位的武器和"）。
     *
     * <p>直接取巨兽自己的武器挂载数：原版 {@code UnitType.init()} 会把 mirror 武器展开成两条，
     * 所以 {@code type.weapons.size} 就是"这个单位的武器数量"，而巨兽的 mounts 正是
     * 全部成员武器依次复制出来的，长度就是那个和。两端（服务端权威判定 / 客户端列不列按钮）
     * 用的是同一个数，联机不会出现"客户端能点、服务端说满了"。
     */
    public int maxTurrets(){
        mindustry.entities.units.WeaponMount[] ms = mega == null ? null : mega.mounts();
        return Mathf.clamp(ms == null ? 1 : ms.length, 1, HARD_MAX_TURRETS);
    }

    /** 巨兽身上摆了哪些炮台（如 "双管炮×2, 扫描仪×1"），给面板/提示用。 */
    public String composition(){
        if(turrets.isEmpty()) return "";
        ObjectMap<Block, Integer> tally = new ObjectMap<>();
        for(Building b : turrets){
            if(b == null || b.block == null) continue;
            tally.put(b.block, tally.get(b.block, 0) + 1);
        }
        if(tally.size == 0) return "";
        Seq<Block> types = tally.keys().toSeq();
        types.sort((a, b) -> Integer.compare(tally.get(b, 0), tally.get(a, 0)));
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < types.size; i++){
            if(i > 0) sb.append(", ");
            Block t = types.get(i);
            sb.append(t.localizedName).append("×").append(tally.get(t, 0));
        }
        return sb.toString();
    }

    // -------------------- 内部世界 --------------------

    /** 内部世界边长（格）：按巨兽体型来，炮台大致摆在巨兽身上。 */
    private int gridSizeFor(){
        float h = Math.max(mega.hitSize(), 8f);
        return Mathf.clamp(Mathf.ceil(h * 2f / tilesize), MIN_GRID, MAX_GRID);
    }

    /** 需要更大的内部世界时重建一个（旧的格引用/建筑全丢，调用方负责重新 install）。 */
    private void recreateWorld(int want){
        grid = Mathf.clamp(want, MIN_GRID, MAX_GRID);
        World w = new World();
        w.resize(grid, grid);
        for(int y = 0; y < grid; y++){
            for(int x = 0; x < grid; x++){
                w.tiles.set(x, y, new Tile(x, y));
            }
        }
        innerWorld = w;
        builtSig = -1;
    }

    /** 保证内部世界存在且边长 ≥ min（不够就换一个更大的重建）。 */
    private void ensureWorld(int min){
        int want = Mathf.clamp(min, MIN_GRID, MAX_GRID);
        if(innerWorld != null && grid >= want) return;
        recreateWorld(want);
    }

    private void clearWorld(){
        turrets.clear();
        if(innerWorld != null){
            for(Tile t : innerWorld.tiles){
                if(t != null){
                    t.build = null;
                    t.updateBlockReference(Blocks.air);
                }
            }
        }
        builtSig = -1;
    }

    /** 这座方块在内部世界里能不能放在 (tx,ty)（锚点格）：不出界、格子上没东西。 */
    private boolean fits(Block block, int tx, int ty){
        int off = -(block.size - 1) / 2;
        for(int dx = 0; dx < block.size; dx++){
            for(int dy = 0; dy < block.size; dy++){
                int x = tx + off + dx, y = ty + off + dy;
                if(x < 0 || y < 0 || x >= grid || y >= grid) return false;
                Tile t = innerWorld.tile(x, y);
                if(t == null || t.block() != Blocks.air) return false;
            }
        }
        return true;
    }

    /** 从中间往外找第一个能放下的格（布局放不下时的兜底）。 */
    private Tile findAnySlot(Block block){
        int cx = grid / 2, cy = grid / 2;
        for(int r = 0; r < grid; r++){
            for(int dy = -r; dy <= r; dy++){
                for(int dx = -r; dx <= r; dx++){
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int tx = cx + dx, ty = cy + dy;
                    if(fits(block, tx, ty)) return innerWorld.tile(tx, ty);
                }
            }
        }
        return null;
    }

    // -------------------- 摆放布局 --------------------

    /**
     * 方块绘制中心在"格"单位下比锚点格多出来的量：
     * 原版 {@code Block.offset = ((size + 1) % 2) * tilesize / 2}（见 {@link Tile#drawx()}），
     * 也就是**奇数尺寸**的方块中心就在锚点格上（0），**偶数尺寸**的多半格（0.5）。
     */
    private static float unitOffset(int size){
        return (size & 1) == 0 ? 0.5f : 0f;
    }

    /** 目标"方块中心"（格单位，巨兽中心 = grid/2）→ 锚点格。 */
    private static int anchorAxis(int size, float centerTiles){
        return Mathf.round(centerTiles - unitOffset(size));
    }

    /**
     * 把右半边某座炮台按巨兽中线（{@code centerTiles} 格）镜像到左半边 ——
     * 镜像的是"方块中心"，所以两侧尺寸不同也能对上（差半格时取最近的格）。
     */
    private static int mirrorAxis(int sizeRight, int sizeLeft, int anchorRight, float centerTiles){
        float centerRight = anchorRight + unitOffset(sizeRight);
        return Mathf.round(2f * centerTiles - centerRight - unitOffset(sizeLeft));
    }

    /** 按目标中心格找锚点格：目标点被占了就就近找（最多外扩 4 格），尽量保持这一侧的观感。 */
    private Tile findSlotNear(Block block, float ccx, float ccy){
        int ax = anchorAxis(block.size, ccx), ay = anchorAxis(block.size, ccy);
        if(fits(block, ax, ay)) return innerWorld.tile(ax, ay);
        for(int r = 1; r <= 4; r++){
            for(int dy = -r; dy <= r; dy++){
                for(int dx = -r; dx <= r; dx++){
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    if(fits(block, ax + dx, ay + dy)) return innerWorld.tile(ax + dx, ay + dy);
                }
            }
        }
        return null;
    }

    /**
     * 重新摆放全部炮台（用户要求：**位置和武器一样**）：
     * <ul>
     * <li><b>偶数座</b> → 左右两边各一半（两侧严格镜像：横向坐标取反、前后位置相同）；</li>
     * <li><b>奇数座</b> → 多出来的那一座摆在正中间（x = 中线），其余照旧两边平分。</li>
     * </ul>
     * "左右 / 前后"用的是和武器挂载同一套坐标：内部世界的 <b>x 是横向</b>、<b>y 是前后</b>，
     * {@link #project} 与 Weapon 一样走 {@code Angles.trns(rotation - 90, x, y)}。
     */
    private void relayout(){
        int n = turrets.size;
        if(n == 0) return;
        // 先全部摘下来：免得"自己挡住自己"、也让 fits() 只看得到空地
        for(Building b : turrets){
            if(b != null) uninstall(b);
        }

        int maxSize = 1;
        for(Building b : turrets){
            if(b != null && b.block != null) maxSize = Math.max(maxSize, b.block.size);
        }

        // 奇数座：多出来的那一座（最后吸收的那座）去正中间；其余按奇偶分到左右两边。
        int midIdx = (n & 1) == 1 ? n - 1 : -1;
        Seq<Integer> rightIdx = new Seq<>(), leftIdx = new Seq<>();
        for(int i = 0; i < n; i++){
            if(i == midIdx) continue;
            if((i & 1) == 0) rightIdx.add(i);
            else leftIdx.add(i);
        }

        int rows = Math.max(rightIdx.size, leftIdx.size);   // 两侧共用同一套角度

        // 【两侧摆在圆环上（用户 2026-10-08："两边的位置和武器一样是环形的"）】
        // 武器是 radius = hitSize*0.55 的圆、每半边可用 150°；炮台是方块还要占格，
        // 所以半径取三者较大者：①武器圆的世界半径换算成格；②至少离开中线 maxSize+1 格
        // （不然一座就盖在正中间了）；③"每座占 maxSize 格"所需的弧长（150° 的弧 ≈ 2.6×半径）。
        float ringRad = Math.max(Math.max(mega.hitSize() * 0.55f / tilesize, maxSize + 1f),
                rows * maxSize / 2.6f);
        int need = Mathf.ceil(2f * (ringRad + maxSize + 2f));
        ensureWorld(Math.max(gridSizeFor(), need));

        float center = grid / 2f;          // 巨兽中心在"格"单位下的位置（方块中心坐标）
        float span = 150f;                 // 和 layoutWeapons 同一套角度范围

        // 正中间那一座
        if(midIdx >= 0) place(turrets.get(midIdx), center, center, -1);

        // 两侧：右侧先落位，左侧按巨兽中线镜像（x 取反、角度相同 → 严格对称）
        for(int k = 0; k < rows; k++){
            float a = rows == 1 ? 0f : (-span / 2f + k * span / (rows - 1));
            float ccx = center + Mathf.cosDeg(a) * ringRad;
            float ccy = center + Mathf.sinDeg(a) * ringRad;
            int mirrorAnchor = -1, mirrorSize = 1;
            if(k < rightIdx.size){
                Building b = turrets.get(rightIdx.get(k));
                if(b != null && b.block != null){
                    place(b, ccx, ccy, -1);
                    mirrorSize = b.block.size;
                    mirrorAnchor = b.tile == null ? anchorAxis(mirrorSize, ccx) : b.tile.x;
                }
            }
            if(k < leftIdx.size){
                Building b = turrets.get(leftIdx.get(k));
                if(b != null && b.block != null){
                    int force = mirrorAnchor >= 0
                            ? mirrorAxis(mirrorSize, b.block.size, mirrorAnchor, center)
                            : -1;
                    place(b, 2f * center - ccx, ccy, force);
                }
            }
        }
    }

    /**
     * 把一座炮台摆到"方块中心落在 (ccx, ccy) 格"的位置；{@code forceAnchorX >= 0} 时直接用这个锚点
     * （左右镜像用），放不下就就近找、再不行随便找个空地（总比飘在世界外好）。
     */
    private void place(Building b, float ccx, float ccy, int forceAnchorX){
        int size = b.block.size;
        int ay = anchorAxis(size, ccy);
        int ax = forceAnchorX >= 0 ? forceAnchorX : anchorAxis(size, ccx);
        Tile slot = fits(b.block, ax, ay) ? innerWorld.tile(ax, ay) : findSlotNear(b.block, ccx, ccy);
        if(slot == null) slot = findAnySlot(b.block);
        if(slot != null) install(slot, b, b.rotation);
    }

    /** 把建筑挂到内部世界的锚点格上（绕过 setBlock：不触发事件、不进 Groups、不污染队伍索引）。 */
    private void install(Tile slot, Building b, int rotation){
        int off = -(b.block.size - 1) / 2;
        for(int dx = 0; dx < b.block.size; dx++){
            for(int dy = 0; dy < b.block.size; dy++){
                Tile t = innerWorld.tile(slot.x + off + dx, slot.y + off + dy);
                if(t != null){
                    t.updateBlockReference(b.block);
                    t.build = b;
                }
            }
        }
        b.tile = slot;
        b.rotation = rotation;
        b.set(slot.drawx(), slot.drawy());
        if(b.power != null) b.power.status = 1f;
        b.enabled = true;
    }

    /** 把建筑从内部世界摘掉（格子的引用清掉）。 */
    private void uninstall(Building b){
        Tile anchor = b.tile;
        if(anchor != null){
            int off = -(b.block.size - 1) / 2;
            for(int dx = 0; dx < b.block.size; dx++){
                for(int dy = 0; dy < b.block.size; dy++){
                    Tile t = innerWorld.tile(anchor.x + off + dx, anchor.y + off + dy);
                    if(t != null && t.build == b){
                        t.build = null;
                        t.updateBlockReference(Blocks.air);
                    }
                }
            }
        }
    }

    // -------------------- 吸收 / 释放 --------------------

    /** 这座建筑能不能被巨兽吃掉：是炮台、同队、不是以单位为弹药的 PayloadAmmoTurret。 */
    public static boolean absorbable(Building b, Team team){
        if(b == null || b.block == null || !(b.block instanceof Turret)) return false;
        if(b.team != team) return false;
        // PayloadAmmoTurret 的弹药是单位（装进载荷舱发射），没有渠道给它补给，不吸收
        if(b.block instanceof PayloadAmmoTurret) return false;
        return true;
    }

    /**
     * 把一座炮台从真实世界搬进巨兽体内。
     * @return 是否成功（格子满了 / 不是炮台 → false）
     */
    public boolean absorb(Building b){
        if(!absorbable(b, mega.team)) return false;
        if(turrets.size >= maxTurrets()) return false;
        ensureWorld(gridSizeFor());
        // 1) 从真实世界移除：走原版流程（onRemoved、Groups.build、队伍索引/索敌树都一起清掉）
        b.tile.setBlock(Blocks.air);
        // 2) 搬进内部世界：位置由 relayout() 统一排（偶数分两边、奇数多的一座留中间）
        turrets.add(b);
        relayout();
        builtSig = -1;
        return true;
    }

    /**
     * 把所有炮台放回真实世界（解体/被击毁/手动释放时调）。
     * @return 成功放出去几座
     */
    public int releaseAll(){
        if(turrets.isEmpty()) return 0;
        int released = 0;
        Seq<Building> copy = new Seq<>(turrets);
        for(Building b : copy){
            if(b == null || b.block == null) continue;
            Tile spot = findReleaseSpot(b);
            if(spot == null){
                // 实在没地方（巨兽悬在大水/界外）：就落在巨兽脚下，总比跟着巨兽一起消失好
                int cx = Mathf.clamp(World.toTile(mega.x), 2, world.width() - 3);
                int cy = Mathf.clamp(World.toTile(mega.y), 2, world.height() - 3);
                spot = world.tile(cx, cy);
                if(spot == null) continue;
            }
            uninstall(b);
            try{
                spot.setBlock(b.block, mega.team, b.rotation, () -> b);
                released++;
            }catch(Throwable t){
                Log.err("[combine] 炮台放回世界失败 @", b.block, t);
            }
        }
        turrets.clear();
        builtSig = -1;
        return released;
    }

    /** 给炮台在巨兽附近找个能放的落脚点：先试巨兽脚下的格，不行就螺旋外扩（最多 24 格）。 */
    private Tile findReleaseSpot(Building b){
        int size = b.block.size;
        int cx = World.toTile(mega.x), cy = World.toTile(mega.y);
        // 两轮：第一轮优先陆地（炮台不能泡在水里），第二轮实在没有陆地就允许浅水
        for(int pass = 0; pass < 2; pass++){
            for(int r = 0; r <= 24; r++){
                for(int dy = -r; dy <= r; dy++){
                    for(int dx = -r; dx <= r; dx++){
                        if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                        int tx = cx + dx, ty = cy + dy;
                        if(canPlace(b.block, size, tx, ty, pass == 0)) return world.tile(tx, ty);
                    }
                }
            }
        }
        return null;
    }

    private boolean canPlace(Block block, int size, int tx, int ty, boolean landOnly){
        int off = -(size - 1) / 2;
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++){
                int x = tx + off + dx, y = ty + off + dy;
                Tile t = world.tile(x, y);
                if(t == null) return false;
                if(t.block() != Blocks.air) return false;
                if(landOnly){
                    var floor = t.floor();
                    if(floor != null && floor.isLiquid && floor.drownTime > 0f) return false;
                }
            }
        }
        return true;
    }

    // -------------------- 每 tick：定位 + 补给 + 更新 --------------------

    public void tick(){
        if(turrets.isEmpty() || mega.dead) return;
        // 【玩家操控：舱里的炮台跟着玩家的鼠标转 + 按玩家的开火键开火】见 driveByPlayer
        Player pilot = mega.controller() instanceof Player p ? p : null;
        for(int i = turrets.size - 1; i >= 0; i--){
            Building b = turrets.get(i);
            if(b == null || b.block == null){
                turrets.remove(i);
                builtSig = -1;
                continue;
            }
            project(b);
            supply(b);
            ensureHeat(b);
            driveByPlayer(b, pilot);
            try{
                b.update();   // = updateConsumption + updateTile：索敌/转向/开火都在真实世界坐标上
            }catch(Throwable t){
                // 一座炮台出问题不能把巨兽带崩
                Log.err("[combine] 巨兽炮台更新失败 @", b.block, t);
            }
        }
    }

    /**
     * 玩家正在操控巨兽时，让舱里的炮台"听玩家的鼠标"：瞄准点 = 玩家的鼠标、开火 = 玩家按着开火键
     *（用户问的"为什么不能控制炮台转向和开火"）。
     *
     * <p>驱动走的是原版**逻辑控制炮台**那条路（{@code TurretBuild.control(LAccess.shoot, …)}，
     * 和逻辑处理器写 {@code control shoot} 完全一样）：它把目标点写进 {@code targetPos}、把
     * {@code logicControlTime} 续到 2 秒、{@code logicShooting} 记住"要不要开火"，炮台随后在
     * {@code updateTile()} 里自己转向/开火。**故意不碰 {@code unit}/controller** —— 直接给炮台的
     * BlockUnit 挂玩家控制器会触发 {@code PlayerComp.unit()} 把 {@code player.unit} 从巨兽换成那个
     * 假单位（那是原版"附身炮台"，一次只能附身一台），巨兽反而丢掉操控者。
     *
     * <p>玩家不再操控巨兽（松开/换人/死亡）时不再续 {@code logicControlTime}，它 2 秒内自己衰减到 0，
     * 炮台就回到自己的 AI 索敌（{@code playerControllable=false} 的炮台一直保持 AI）。
     */
    private void driveByPlayer(Building b, Player pilot){
        if(pilot == null) return;                                     // 没人操控：让它自己衰减回 AI
        if(!(b instanceof Turret.TurretBuild tb)) return;
        if(!(b.block instanceof Turret t) || !t.playerControllable) return;
        tb.control(mindustry.logic.LAccess.shoot,
                mindustry.core.World.conv(mega.aimX()), mindustry.core.World.conv(mega.aimY()),
                pilot.shooting ? 1d : 0d, 0d);
    }

    /** 内部世界坐标 → 巨兽身上的世界坐标（和原版武器挂载同口径：Angles.trns(rotation - 90)）。 */
    public void project(Building b){
        float half = grid * tilesize / 2f;
        float lx = b.tile.x * tilesize + b.block.offset - half;
        float ly = b.tile.y * tilesize + b.block.offset - half;
        float a = mega.rotation - 90f;
        b.x = mega.x + Angles.trnsx(a, lx, ly);
        b.y = mega.y + Angles.trnsy(a, lx, ly);
    }

    /** 补给：电力/液体直接给，物品弹药从核心扣。 */
    private void supply(Building b){
        if(b.power != null) b.power.status = 1f;
        Block block = b.block;
        // 【冷却液/消耗型液体】原版对 ConsumeLiquid 是按"液体够不够"算效率的，效率为 0 就永远
        // 不开火 —— 巨兽体内没有管道，得直接给它灌满（用户报的"有的炮台不发射"：
        // meltdown 要水、lustre 要氮气、埃里克尔的 titan 要氢气……）。
        if(b.liquids != null){
            for(mindustry.world.consumers.Consume cons : block.nonOptionalConsumers) fillLiquid(b, cons);
            for(mindustry.world.consumers.Consume cons : block.optionalConsumers) fillLiquid(b, cons);
        }
        // 液体弹药炮台（LiquidTurret / ContinuousLiquidTurret）：直接把液体加满
        if(b.liquids != null){
            ObjectMap<Liquid, BulletType> ammo = null;
            if(block instanceof LiquidTurret lt) ammo = lt.ammoTypes;
            else if(block instanceof ContinuousLiquidTurret clt) ammo = clt.ammoTypes;
            if(ammo != null && ammo.size > 0){
                Liquid cur = b.liquids.currentAmount() > 0.001f ? b.liquids.current() : null;
                Liquid want = (cur != null && ammo.containsKey(cur)) ? cur : ammo.keys().next();
                float cap = Math.max(block.liquidCapacity, 1f);
                float have = b.liquids.get(want);
                if(have < cap - 0.01f){
                    b.liquids.add(want, cap - have);   // 灌满（同样是"按效率消耗"的反馈）
                }
                return; // 液体炮台不吃核心的料
            }
        }
        // 物品弹药炮台：子弹从核心里扣
        if(block instanceof ItemTurret it && b instanceof ItemTurret.ItemTurretBuild itb){
            feedFromCore(b, it, itb);
        }
    }

    /**
     * 给物品炮台补弹（用户 2026-10-08 口径）：
     * <ul>
     * <li><b>按这座炮台自己的弹药表循环喂</b>：每次只喂 1 个料，从上次停下的位置往后找下一个可用弹药 ——
     * 原版 {@code ammo} 是个栈、{@code peekAmmo()} 就是下一发要打的，所以不同弹药会轮流出现，
     * 打出来的子弹在弹药表里轮换（"发射的弹药为其弹药列表循环发射"）。</li>
     * <li><b>只用玩家没勾掉、且核心里有货的弹药</b>：核心没有对应物品就一个都不补 ——
     * 这一座自然就打不出子弹了（"如果核心没有对应物品就不发射"）；全被勾掉同理。</li>
     * <li>仍然只补到半仓、每 tick 至多 1 个料，细水长流省核心库存。</li>
     * </ul>
     */
    /** 把某个 ConsumeLiquid 需要的液体灌满（冷却液 / 消耗型液体）。 */
    private void fillLiquid(Building b, mindustry.world.consumers.Consume cons){
        if(b.liquids == null) return;
        Liquid lq = null;
        if(cons instanceof mindustry.world.consumers.ConsumeLiquid cl){
            lq = cl.liquid;
        }else if(cons instanceof mindustry.world.consumers.ConsumeLiquidFilter cf){
            // 【冷却液走的是这个】原版 consumeCoolant() 生成的是 ConsumeLiquidFilter（按
            // 温度/可燃性挑液体），不是 ConsumeLiquid —— 只认 ConsumeLiquid 的话
            // meltdown/lustre/埃里克尔的 titan 这些"要冷却液"的炮台永远拿不到液体、效率为 0、
            // 一发都不打（用户报的"有的炮台不发射"）。这里挑一个过滤通过的液体灌满。
            if(cf.filter != null){
                for(Liquid l : content.liquids()){
                    if(cf.filter.get(l)){ lq = l; break; }
                }
            }
        }
        if(lq == null) return;
        float cap = Math.max(b.block.liquidCapacity, 1f);
        float have = b.liquids.get(lq);
        // 【一次灌满】原版消耗型液体是"按效率消耗"的反馈：只补 5/帧时它会在低效率上
        // 稳定下来（实测 sf 屠龙宝刀 效率 0.083、永远打不出来）。直接补满，效率才是 1。
        if(have < cap - 0.01f){
            b.liquids.add(lq, cap - have);
        }
    }

    /**
     * 给"要热量"的炮台（{@code heatRequirement > 0}，例如埃里克尔的 afflict/malign、模组里的
     * 热轨道炮）塞一个**隐形的假热块**：不注册进 content、不占格子、不进任何索引，
     * 只是加进这座炮台的 {@code proximity} 里，让原版 {@code Building.calculateHeat()} 算得出
     * 一个够用的热量 —— 于是 {@code heatReq > 0}、{@code canConsume()} 放行
     *（用户 2026-10-09："有的炮台不发射"）。
     *
     * <p>【为什么不用 {@code Blocks.heatSource}】原版的接触点算法是
     * {@code contactPoints = size/2 + 热源size/2 - 两点距离/8}，格子里"贴着放"也常常算出 0
     * （实测 afflict 4x4 + 1x1 热源贴着放 = 0 接触点、calculateHeat=0），炮台照样打不出来。
     * 这里干脆把热块的坐标设成**和炮台完全重合**（diff=0 → contactPoints≥1），
     * 不依赖版本/格子几何。
     */
    private void ensureHeat(Building b){
        if(!(b.block instanceof Turret t) || t.heatRequirement <= 0f) return;
        if(b.proximity == null) return;
        FakeHeat heat = null;
        for(Building p : b.proximity){
            if(p instanceof FakeHeat fh){
                heat = fh;
                break;
            }
        }
        if(heat == null){
            heat = new FakeHeat();
            heat.block = fakeHeatBlock();
            heat.team = mega.team;
            heat.heatAmount = Math.max(t.heatRequirement, 60f) * 100f;   // 富余一点，够 maxHeatEfficiency 用
            b.proximity.add(heat);
        }
        // 每 tick 跟着炮台走（calculateHeat 是按坐标差算的）
        heat.x = b.x;
        heat.y = b.y;
    }

    /** 隐形假热块：只需要 x/y/team/block + heat()，不摆格子、不 update、不绘制。 */
    public static class FakeHeat extends Building implements mindustry.world.blocks.heat.HeatBlock{
        public float heatAmount = 6000f;
        @Override
        public float heat(){
            return heatAmount;
        }
        @Override
        public float heatFrac(){
            return 1f;
        }
    }

    private static Block fakeHeatBlock;

    private static Block fakeHeatBlock(){
        if(fakeHeatBlock == null){
            Block b = new Block("combineunit-fake-heat");
            b.size = 1;
            b.rotate = false;
            fakeHeatBlock = b;
        }
        return fakeHeatBlock;
    }

    private void feedFromCore(Building b, ItemTurret it, ItemTurret.ItemTurretBuild itb){
        if(itb.cheating()) return;                     // 无限火力模式原版自己管，别重复扣
        // 【补到满仓为止】不能只补半仓：ammoPerShot 大的炮台（阻碍每发30/仓30、死诏每发8/仓24…）
        // 半仓根本凑不齐一发，表现就是"怎么都不开火"；同时按弹药表轮流喂，仍是"打一发换一种"。
        if(itb.totalAmmo >= it.maxAmmo) return;
        Building core = mega.team == null ? null : mega.team.core();
        if(core == null || core.items == null) return;
        Seq<Item> usable = usableAmmo(it);
        if(usable.isEmpty()) return;                   // 弹药全被玩家勾掉了
        int start = Math.max(ammoCursor.get(b, 0), 0) % usable.size;
        Item pick = null;
        int next = start;
        for(int k = 0; k < usable.size; k++){
            int idx = (start + k) % usable.size;
            if(core.items.get(usable.get(idx)) > 0){
                pick = usable.get(idx);
                next = (idx + 1) % usable.size;
                break;
            }
        }
        if(pick == null) return;                       // 核心没有可用弹药 → 不补 → 打不出去
        // 【一次补够"一发"的量】原版 hasAmmo() 要求栈顶那份弹药 ≥ ammoPerShot ——
        // 每次只喂 1 个料的话，多弹药炮台（ammoPerShot>1）永远凑不齐一发、看着就是"不发射"
        // （实测 scathe 每发 15、titan 每发 4）。一次补 ammoPerShot 个，既凑够一发，
        // 又是"打一发换一种"的循环。
        int batch = Math.max(1, it.ammoPerShot);
        int space = Math.max(it.maxAmmo - itb.totalAmmo, 0);
        batch = Math.min(batch, Math.min(core.items.get(pick), space));
        if(batch <= 0) return;
        for(int i = 0; i < batch; i++){
            if(!itb.acceptItem(itb, pick)) break;
            core.items.remove(pick, 1);
            itb.handleItem(itb, pick);
        }
        ammoCursor.put(b, next);
    }

    // -------------------- 序列化 --------------------

    public void write(Writes write, boolean full){
        byte[] body;
        try{
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            Writes w = new Writes(new java.io.DataOutputStream(bos));
            // 只有"真的有禁用弹药"时才升到带 tune 的版本 —— 空表就写老版本，
            // 旧 jar 读新档也不会因为多出来的一段而读歪。
            boolean tune = !bannedAmmo.isEmpty();
            w.b(full ? (tune ? VER_TUNE_FULL : VER_FULL) : (tune ? VER_TUNE_COMPACT : VER_COMPACT));
            w.b(grid);
            w.b(turrets.size);
            for(Building b : turrets){
                if(b == null || b.block == null) continue;
                w.s(b.block.id);
                w.b((byte)b.rotation);
                w.b(b.tile == null ? 0 : (byte)b.tile.x);
                w.b(b.tile == null ? 0 : (byte)b.tile.y);
                if(full){
                    w.f(b.health);
                    writeAmmo(w, b);
                }
            }
            if(tune){
                // 【弹药禁用表】放在炮台列表之后（体本身带长度前缀，整块读进内存，读到哪算哪）
                int n = 0;
                for(Item it : bannedAmmo) if(it != null) n++;
                w.b(n);
                for(Item it : bannedAmmo) if(it != null) w.s((short)it.id);
            }
            body = bos.toByteArray();
        }catch(Throwable t){
            Log.err("[combine] 巨兽炮台序列化失败（本次按空舱写出）", t);
            body = new byte[]{VER_COMPACT, 0, 0};
        }
        write.b(TURRET_TAG);
        write.i(body.length);
        write.b(body);
    }

    private void writeAmmo(Writes w, Building b){
        if(b instanceof ItemTurret.ItemTurretBuild itb && b.block instanceof ItemTurret it){
            int n = Math.min(itb.ammo.size, 8);
            w.b(n);
            for(int i = 0; i < n; i++){
                ItemTurret.ItemEntry e = (ItemTurret.ItemEntry)itb.ammo.get(i);
                BulletType bt = it.ammoTypes.get(e.item);
                // 存"喂了几个料"而不是弹药点数，读回来逐个 handleItem 还原
                w.s((short)e.item.id);
                w.f(bt == null ? 1f : Math.max(1f, Math.round(e.amount / Math.max(bt.ammoMultiplier, 1f))));
            }
        }else if(b.liquids != null && b.liquids.currentAmount() > 0.001f){
            w.b(1);
            w.s((short)b.liquids.current().id);
            w.f(b.liquids.currentAmount());
        }else{
            w.b(0);
        }
    }

    public void read(Reads read){
        byte tag = read.b();
        if(tag != (TURRET_TAG & 0xFF)){
            // 不是炮台段（老存档/老快照）：不消费任何字节，保持空舱
            return;
        }
        int len = Math.min(Math.max(read.i(), 0), 4096);
        byte[] body;
        try{
            body = read.b(len);
        }catch(Throwable t){
            Log.err("[combine] 巨兽炮台段读取失败，保持空舱", t);
            return;
        }
        try{
            Reads r = new Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(body)));
            byte ver = r.b();
            boolean fullV = vFull(ver), tuneV = vTune(ver);
            int g = r.b() & 0xFF;
            int n = r.b() & 0xFF;
            int[] ids = new int[n], rots = new int[n], txs = new int[n], tys = new int[n];
            float[] hps = new float[n];
            // 弹药：物品炮台存物品，液体炮台存液体；两种不会同时出现，分开记
            Item[][] ammoItems = new Item[n][];
            float[][] ammoAmt = new float[n][];
            Liquid[] liqs = new Liquid[n];
            float[] liqAmt = new float[n];
            for(int i = 0; i < n; i++){
                ids[i] = r.s();
                rots[i] = r.b();
                txs[i] = r.b();
                tys[i] = r.b();
                if(fullV){
                    hps[i] = r.f();
                    int m = r.b() & 0xFF;
                    Item[] its = null;
                    float[] amts = null;
                    for(int k = 0; k < m; k++){
                        short cid = r.s();
                        float amt = r.f();
                        Item it = content.item(cid);
                        if(it != null){
                            if(its == null){ its = new Item[m]; amts = new float[m]; }
                            its[k] = it;
                            amts[k] = amt;
                        }else{
                            Liquid lq = content.liquid(cid & 0xFFFF);
                            if(lq != null){ liqs[i] = lq; liqAmt[i] = amt; }
                        }
                    }
                    ammoItems[i] = its;
                    ammoAmt[i] = amts;
                }
            }
            // 弹药禁用表：服务端权威，直接覆盖本地（客户端改的会被快照盖回去）
            if(tuneV){
                int m = Math.min(r.b() & 0xFF, 64);
                if(m > 0){
                    bannedAmmo.clear();
                    for(int i = 0; i < m; i++){
                        Item it = content.item(r.s());
                        if(it != null) bannedAmmo.add(it);
                    }
                }else{
                    bannedAmmo.clear();
                }
            }
            // 构成签名变了才重建（客户端每秒几十个快照，绝大多数时候什么都不用做）
            int sig = sig(g, ids, rots, txs, tys);
            if(sig != builtSig){
                rebuild(g, ids, rots, txs, tys, hps, ammoItems, ammoAmt, liqs, liqAmt, fullV);
                builtSig = sig;
            }
        }catch(Throwable t){
            Log.err("[combine] 巨兽炮台段解析失败，保留上一份炮台", t);
        }
    }

    private int sig(int g, int[] ids, int[] rots, int[] txs, int[] tys){
        int s = g * 31 + ids.length;
        for(int i = 0; i < ids.length; i++){
            s = s * 31 + ids[i] * 7 + rots[i] * 3 + txs[i] * 5 + tys[i];
        }
        return s;
    }

    private void rebuild(int g, int[] ids, int[] rots, int[] txs, int[] tys, float[] hps,
                         Item[][] ammoItems, float[][] ammoAmt, Liquid[] liqs, float[] liqAmt, boolean fullV){
        clearWorld();
        if(ids.length == 0) return;
        // 边长照服务端发来的来（两端布局一致）；老快照没带边长就按体型算。
        // 必须按这个边长**重建**内部世界：客户端可能刚因为别的构成换过尺寸。
        recreateWorld(g > 0 ? g : gridSizeFor());
        for(int i = 0; i < ids.length; i++){
            Block block = content.block(ids[i]);
            if(block == null || !(block instanceof Turret)) continue;
            int tx = Mathf.clamp(txs[i], 0, grid - 1), ty = Mathf.clamp(tys[i], 0, grid - 1);
            if(!fits(block, tx, ty)) continue;
            Building b;
            try{
                b = block.newBuilding();
                if(b == null) continue;
                b.create(block, mega.team);
            }catch(Throwable t){
                Log.err("[combine] 重建巨兽炮台失败 @", block, t);
                continue;
            }
            Tile slot = innerWorld.tile(tx, ty);
            install(slot, b, rots[i]);
            if(fullV){
                try{
                    b.health = Math.max(1f, Math.min(hps[i], b.maxHealth()));
                    if(ammoItems != null && ammoItems[i] != null && b instanceof ItemTurret.ItemTurretBuild itb){
                        for(int k = 0; k < ammoItems[i].length; k++){
                            int cnt = Math.max(1, Math.round(ammoAmt[i][k]));
                            for(int c = 0; c < cnt; c++) itb.handleItem(itb, ammoItems[i][k]);
                        }
                    }else if(b.liquids != null && liqs != null && i < liqs.length && liqs[i] != null){
                        b.liquids.add(liqs[i], Math.min(liqAmt[i], Math.max(block.liquidCapacity, 1f)));
                    }
                }catch(Throwable ignored){
                }
            }
            turrets.add(b);
        }
    }
}
