package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.entities.units.UnitController;
import mindustry.game.*; import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户问："坦克合体后的履带绘制和碾压伤害还在吗"。
 *
 * <p>查证结论：
 * <ul>
 *     <li><b>履带绘制在</b>：{@code MegaUnitType.drawAttachments} 的 {@code ATT_TANK} 分支调原版
 *         {@code UnitType.drawTank}（贴图/滚动帧取代表类型），滚动相位由
 *         {@code MegaUnitEntity.updateAttachments} 维护（{@code treadTime} / {@code walkedState}）；
 *         真客户端截图见 {@code 094_tank_mega.png}。</li>
 *     <li><b>碾压伤害确实丢了</b>：原版碾压写在 {@code TankComp.update()} 里（类图：{@code TankUnit}
 *         实现 {@code Tankc}），而巨兽继承的是最普通的 {@code UnitEntity} ——
 *         生成类的接口表是 {@code UnitEntity implements ... Unitc, Velc, Weaponsc}，**没有 Tankc/TankComp**，
 *         所以 {@code super.update()} 里根本没有这段；而且派生类型也从没推导过
 *         {@code type.crushDamage} / {@code type.crushFragile}（一直是 0 / false）。</li>
 * </ul>
 *
 * <p>本测试先量事实（原版坦克自己的碾压范围/伤害），再断言巨兽与之一致或更强：
 * ①类型推导（伤害取最大、脆弱取并集）；②履带绘制所需的运行时状态（attKind/treadRects/treadTime/walked）；
 * ③碾压伤害（敌方墙掉血）；④脆弱方块被秒碎；⑤反例（机甲巨兽什么都不碾）。
 */
public class MegaTankTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaTankTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MT] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
    }
    static class DummyController implements UnitController{
        Unit u;
        @Override public void unit(Unit u){ this.u = u; }
        @Override public Unit unit(){ return u; }
    }
    /** 造一只"不听 AI 指挥"的单位（不然它会自己乱跑，测不了位置）。 */
    static Unit spawn(UnitType t, float x, float y){
        Unit u = t.create(Team.sharded);
        u.controller(new DummyController());
        u.set(x, y);
        u.add();
        return u;
    }
    static Unit mergeAt(float x, float y, UnitType... types){
        try{
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = spawn(types[i], x + i * 14f, y);
                us.add(u);
            }
            run(2);
            Object mega = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("mergeSelected", Seq.class).invoke(null, us);
            run(2);
            if(mega instanceof Unit mu){
                mu.controller(new DummyController());
                mu.set(x, y);
            }
            return mega instanceof Unit mu ? mu : null;
        }catch(Throwable t){ System.out.println("[MT] 融合失败: " + t); return null; }
    }
    /** 原版口径的碾压半径（格）：r = hitSize*0.75/tilesize，判定用 r-1。 */
    static int crushR(Unit u){ return Math.max((int)(u.hitSize() * 0.75f / Vars.tilesize), 0); }
    /**
     * 让单位原地"走"起来：每帧给一个 ±1px/tick 的来回速度 ——
     * 这样 {@code deltaLen()} ≈ 1（碾压判定要求 ≥ 0.01），而位置几乎不动，
     * 单位**不会跨到隔壁格**（碾压范围是按"格偏移"算的，跨格会让参照组直接失效）。
     */
    static void jiggle(Unit u, int ticks){
        for(int i = 0; i < ticks; i++){
            u.vel().set(i % 2 == 0 ? 1f : -1f, 0f);
            run(1);
        }
    }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();
        ml = Vars.mods.getMod("combineunit").main.getClass().getClassLoader();

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.waves = false;
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.disableUnitCap = true;
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(10);

        // ---- 找坦克 / 参照单位 ----
        UnitType tank = null, tankBig = null;
        for(UnitType t : Vars.content.units()){
            if(t.crushDamage <= 0f) continue;
            try{
                if(!(t.constructor.get() instanceof Tankc)) continue;
            }catch(Throwable ignored){ continue; }
            if(tank == null) tank = t;
            if(tankBig == null || t.hitSize > tankBig.hitSize) tankBig = t;
        }
        System.out.println("[MT] 原版坦克: " + (tank == null ? "（无）" : tank.name + " hitSize=" + tank.hitSize
            + " crushDamage=" + tank.crushDamage + " crushFragile=" + tank.crushFragile
            + " treadRects=" + tank.treadRects.length + " r=" + (int)(tank.hitSize * 0.75f / 8f))
            + " | 体型最大: " + (tankBig == null ? "（无）" : tankBig.name + " hitSize=" + tankBig.hitSize));
        if(tank == null){ check("数据集里有碾压坦克（前置）", false); System.exit(4); }

        // 找一块平地放核心
        int ox=-1, oy=-1;
        outer:
        for(int y=45;y<120;y++) for(int x=40;x<200;x++){
            boolean ok = true;
            for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-4;dx<=4;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ System.out.println("[MT] 没找到陆地"); System.exit(4); }
        place(Blocks.coreShard, ox + 30, oy + 16, Team.sharded);
        run(10);

        // ---------- ① 类型推导：碾压伤害取最大、脆弱取并集 ----------
        {
            Unit one = mergeAt(ox * 8f, oy * 8f, tank, tank);   // 合体至少要 2 个成员
            if(one == null){ check("两台坦克能合体（前置）", false); }
            else{
                check("两台同型坦克合体后碾压伤害 = 成员值（" + one.type.crushDamage + "）",
                    Math.abs(one.type.crushDamage - tank.crushDamage) < 0.001f);
                check("两台同型坦克合体后 crushFragile=" + tank.crushFragile,
                    one.type.crushFragile == tank.crushFragile);
                one.kill();   // 后面的碾压场景必须干净，不然这些巨兽会去碾别人的靶子
            }
            Unit three = mergeAt(ox * 8f + 400f, oy * 8f, tank, tank, tankBig);
            if(three == null){ check("坦克编组能合体（前置）", false); }
            else{
                float expect = Math.max(tank.crushDamage, tankBig.crushDamage);
                check("多台坦克合体：碾压伤害取成员最大（" + three.type.crushDamage + " = max("
                    + tank.crushDamage + ", " + tankBig.crushDamage + ")）",
                    Math.abs(three.type.crushDamage - expect) < 0.001f);
                check("多台坦克合体：crushFragile 取并集（" + three.type.crushFragile + "）",
                    three.type.crushFragile == (tank.crushFragile || tankBig.crushFragile));
                three.kill();
            }
            Unit mech = mergeAt(ox * 8f + 800f, oy * 8f, UnitTypes.dagger, UnitTypes.dagger);
            if(mech != null){
                check("机甲编组的巨兽没有碾压（crushDamage=0、crushFragile=false）",
                    mech.type.crushDamage <= 0f && !mech.type.crushFragile);
                mech.kill();
            }
        }

        // ---------- ② 履带绘制所需的运行时状态 ----------
        {
            Unit u = spawn(tank, ox * 8f - 200f, oy * 8f);
            Unit big = mergeAt(ox * 8f - 100f, oy * 8f, tank, tank);
            if(big != null){
                Object att = field(Class.forName("combineunit.units.mega.MegaUnitEntity", true, ml), big, "attKind");
                int kind = att instanceof Integer i ? i : -1;
                System.out.println("[MT] 坦克巨兽: attKind=" + kind + "（ATT_TANK=4）代表类型=" + big.type.name
                    + " treadRects=" + tank.treadRects.length + " treadRegion.found="
                    + (tank.treadRegion == null ? "（headless 没 atlas，跳过）" : tank.treadRegion.found())
                    + " treadFrames=" + tank.treadFrames);
                check("坦克巨兽的部件种类判成 ATT_TANK（履带才走原版 drawTank）", kind == 4);
                check("代表坦克类型有履带贴图数据（treadRects=" + tank.treadRects.length + "）",
                    tank.treadRects.length > 0);
                Tankc tk = (Tankc)big;
                float t0 = tk.treadTime();
                jiggle(big, 30);
                float t1 = tk.treadTime();
                System.out.println("[MT] 履带滚动相位 treadTime: " + t0 + " → " + t1
                    + "（走起来后必须增长，否则贴图定格在某一帧）");
                check("坦克巨兽走起来后履带相位在涨（" + String.format("%.1f", t1 - t0) + "）", t1 > t0 + 1f);
                check("坦克巨兽移动时 walked()=true（原版 drawTank/TankComp 都读它）", tk.walked());
                big.kill();
            }
            u.kill();
        }

        // ---------- ③ 碾压伤害：贴着敌方墙走，墙要掉血 ----------
        {
            // 参照组：体型最大的原版坦克自己先压一遍（必须 > 0，否则这个场景本身就无效）
            float dmgRef = crushScenario(spawn(tankBig, (ox + 4) * 8f, (oy + 4) * 8f), ox + 4, oy + 4);
            // 实验组：坦克巨兽（体型更大 → 覆盖格数更多）
            float dmgMega = crushScenario(merge(ox + 30, oy + 4, tank, tank, tankBig), ox + 30, oy + 4);
            // 对照组：机甲巨兽（没有坦克成员）
            float dmgMech = crushScenario(merge(ox + 56, oy + 4, UnitTypes.dagger, UnitTypes.dagger), ox + 56, oy + 4);
            System.out.println("[MT] 碾压实测（60 tick 内敌方墙掉的血）: 原版" + tankBig.name + "=" + dmgRef
                + "、坦克巨兽=" + dmgMega + "、机甲巨兽=" + dmgMech);
            check("原版坦克自己压得动敌方建筑（该场景有效，" + tankBig.name + "=" + dmgRef + "）", dmgRef > 0f);
            check("坦克巨兽也压得动（" + dmgMega + "）", dmgMega > 0f);
            check("坦克巨兽碾压不比原版坦克差（" + dmgMega + " ≥ " + dmgRef + "）", dmgMega >= dmgRef - 0.001f);
            check("没有坦克成员的机甲巨兽完全不碾压（" + dmgMech + "）", dmgMech == 0f);
        }

        // ---------- ④ 脆弱方块（传送带/处理器这类）被秒碎 ----------
        {
            Block fragile = null;
            for(Block b : Vars.content.blocks()){
                boolean cand = b.crushFragile && b.size == 1;
                if(cand) System.out.println("[MT]   crushFragile 方块候选: " + b.name + " solid=" + b.solid
                    + " size=" + b.size + " health=" + b.health);
                if(cand){ fragile = b; break; }
            }
            System.out.println("[MT] 脆弱方块候选: " + (fragile == null ? "（无）" : fragile.name));
            if(fragile != null){
                Unit big = merge(ox + 78, oy + 4, tank, tank);
                if(big != null){
                    int bx = (int)(big.x / 8f) + 1, by = (int)(big.y / 8f);
                    Building b = place(fragile, bx, by, Team.crux);
                    float hp0 = b == null ? 0f : b.health;
                    // 原版 TankComp 的脆弱方块判定看的是**身周 8 格**（dx,dy ∈ {-1,0,1}，不含自己这格），
                    // 所以靶子放在紧邻的一格（巨兽体型大、平时很少能贴这么近，但机制本身要能生效）
                    run(2);
                    boolean gone = b == null || b.dead || !b.isValid() || b.health < hp0;
                    System.out.println("[MT] 脆弱方块 " + fragile.name + " 血量 " + hp0 + " → "
                        + (b == null ? "null" : (b.dead || !b.isValid() ? "已碎" : b.health)));
                    check("敌方脆弱方块被坦克巨兽按原版规则秒碎（" + fragile.name + "）", gone);
                    big.kill();
                }
                check("坦克巨兽能融合出来（脆弱方块用例前置）", big != null);
            }
        }

        System.out.println("[MT] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }

    /** 按格子坐标融合出一只巨兽（失败返回 null）。 */
    static Unit merge(int tx, int ty, UnitType... types){
        return mergeAt(tx * 8f, ty * 8f, types);
    }

    /**
     * 碾压场景：单位站在格子 (tx,ty)，把一面**敌方**墙放在它斜对角 (d,d) 格处（d = r-1），来回走 60 tick。
     *
     * <p>为什么是斜对角 d = r-1，而不是贴脸：原版碾压判定的范围是"格偏移" `max(|dx|,|dy|) <= r-1`，
     * 而单位的碰撞半径是 hitSize/2 —— 斜对角上墙离中心的距离 ≈ 11.3d px，比正对（8d px）远，
     * 正好落进"在碾压范围内、又不和墙的碰撞体重叠"的那条窄缝里（正对着站会被墙挡住、deltaLen 归零就碾不动）。
     */
    static float crushScenario(Unit u, int tx, int ty){
        if(u == null) return -1f;
        int r = crushR(u);
        int d = Math.max(r - 1, 1);
        Building w = place(Blocks.copperWall, tx + d, ty + d, Team.crux);
        float hp0 = w == null ? 0f : w.health;
        System.out.println("[MT]   碾压场景: " + u.type.name + " hitSize=" + u.hitSize() + " r=" + r
            + " crushDamage=" + u.type.crushDamage + " 墙在 (+" + d + ",+" + d + ") 格（斜距 ≈"
            + (int)(d * 8f * 1.4142f - 4f * 1.4142f) + "px、碰撞半径 " + (int)(u.hitSize() / 2f) + "px）");
        jiggle(u, 60);
        float hpNow = (w == null || !w.isValid()) ? 0f : w.health;
        float dmg = hp0 - hpNow;
        System.out.println("[MT]     墙 " + hp0 + " → " + hpNow + "（掉血 " + dmg + "）");
        u.kill();
        return dmg;
    }

    static Object field(Class<?> c, Object o, String name){
        try{
            java.lang.reflect.Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        }catch(Throwable t){ return null; }
    }
}
