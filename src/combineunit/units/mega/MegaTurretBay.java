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
 * 组合巨兽的<b>炮台舱</b>：巨兽合体时把附近的炮台吸收进来，摆进巨兽内部的一个小 World 里，
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
 * 炮台数上限 {@link #MAX_TURRETS}、内部世界边长上限 {@link #MAX_GRID}。
 */
public class MegaTurretBay{
    /** 最多携带的炮台数（每座每帧都要更新+绘制，多了贵）。 */
    public static final int MAX_TURRETS = 16;
    /** 内部世界边长上下限（格）。按巨兽体型算，一般在 4~16。 */
    public static final int MIN_GRID = 4, MAX_GRID = 16;

    private static final byte TURRET_TAG = 0x54;          // 'T'
    private static final byte VER_COMPACT = 1, VER_FULL = 2;

    private final MegaUnitEntity mega;
    private World innerWorld;
    private int grid = 0;
    private final Seq<Building> turrets = new Seq<>();
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

    public int grid(){
        return grid;
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

    private void ensureWorld(){
        if(innerWorld != null) return;
        grid = gridSizeFor();
        innerWorld = new World();
        innerWorld.resize(grid, grid);
        for(int y = 0; y < grid; y++){
            for(int x = 0; x < grid; x++){
                innerWorld.tiles.set(x, y, new Tile(x, y));
            }
        }
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

    /** 从中间往外找第一个能放下的格（炮台尽量摆巨兽中间）。 */
    private Tile findSlot(Block block){
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
        if(turrets.size >= MAX_TURRETS) return false;
        ensureWorld();
        Tile slot = findSlot(b.block);
        if(slot == null) return false;
        int rotation = b.rotation;
        // 1) 从真实世界移除：走原版流程（onRemoved、Groups.build、队伍索引/索敌树都一起清掉）
        b.tile.setBlock(Blocks.air);
        // 2) 搬进内部世界
        install(slot, b, rotation);
        turrets.add(b);
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
        for(int i = turrets.size - 1; i >= 0; i--){
            Building b = turrets.get(i);
            if(b == null || b.block == null){
                turrets.remove(i);
                builtSig = -1;
                continue;
            }
            project(b);
            supply(b);
            try{
                b.update();   // = updateConsumption + updateTile：索敌/转向/开火都在真实世界坐标上
            }catch(Throwable t){
                // 一座炮台出问题不能把巨兽带崩
                Log.err("[combine] 巨兽炮台更新失败 @", b.block, t);
            }
        }
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
                if(have < cap - 0.5f){
                    b.liquids.add(want, Math.min(cap - have, 4f));
                }
                return; // 液体炮台不吃核心的料
            }
        }
        // 物品弹药炮台：子弹从核心里扣
        if(block instanceof ItemTurret it && b instanceof ItemTurret.ItemTurretBuild itb){
            feedFromCore(it, itb);
        }
    }

    private void feedFromCore(ItemTurret it, ItemTurret.ItemTurretBuild itb){
        if(itb.cheating()) return;                     // 无限火力模式原版自己管，别重复扣
        if(itb.totalAmmo >= it.maxAmmo / 2) return;    // 够打一阵子就先不动核心库存
        Building core = mega.team == null ? null : mega.team.core();
        if(core == null || core.items == null) return;
        // 优先沿用炮台里现有的弹药类型，否则找核心里有库存的弹药
        Item cur = itb.ammo.size > 0 && itb.getAmmoContent() instanceof Item it0 ? it0 : null;
        if(cur == null || core.items.get(cur) <= 0){
            cur = null;
            for(Item c : it.ammoTypes.keys()){
                if(core.items.get(c) > 0){ cur = c; break; }
            }
        }
        if(cur == null) return;
        BulletType bt = it.ammoTypes.get(cur);
        int space = it.maxAmmo - itb.totalAmmo;
        // 一次最多补 2 个料（够好几轮齐射了），细水长流省核心
        int batch = Math.min(2, core.items.get(cur));
        batch = Math.min(batch, (int)Math.ceil(space / Math.max(bt.ammoMultiplier, 1f)));
        for(int i = 0; i < batch; i++){
            if(!itb.acceptItem(itb, cur)) break;
            core.items.remove(cur, 1);
            itb.handleItem(itb, cur);
        }
    }

    // -------------------- 序列化 --------------------

    public void write(Writes write, boolean full){
        byte[] body;
        try{
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            Writes w = new Writes(new java.io.DataOutputStream(bos));
            w.b(full ? VER_FULL : VER_COMPACT);
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
                if(ver >= VER_FULL){
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
            // 构成签名变了才重建（客户端每秒几十个快照，绝大多数时候什么都不用做）
            int sig = sig(g, ids, rots, txs, tys);
            if(sig != builtSig){
                rebuild(g, ids, rots, txs, tys, hps, ammoItems, ammoAmt, liqs, liqAmt, ver);
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
                         Item[][] ammoItems, float[][] ammoAmt, Liquid[] liqs, float[] liqAmt, byte ver){
        clearWorld();
        if(ids.length == 0) return;
        grid = g > 0 ? Mathf.clamp(g, MIN_GRID, MAX_GRID) : gridSizeFor();
        if(innerWorld == null){
            innerWorld = new World();
            innerWorld.resize(grid, grid);
            for(int y = 0; y < grid; y++) for(int x = 0; x < grid; x++) innerWorld.tiles.set(x, y, new Tile(x, y));
        }
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
            if(ver >= VER_FULL){
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
