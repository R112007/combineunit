package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.entities.units.*;
import mindustry.game.*; import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;
import mindustry.world.blocks.environment.Floor;

/**
 * 用户报的两条（都跟**悬浮成员** ElevationMoveUnit / elude 有关，外加巨兽的 cell 贴图）：
 *
 * <ol>
 *   <li>"组合巨兽没画 cell"：原版 {@code UnitType.draw()} 里 `drawBody` 之后有一段
 *       `if(drawCell) drawCell(unit)`（画 `cellRegion`）；巨兽的派生类型把 `drawCell` 关了
 *       （绘制全在 MegaUnitType.draw 里自定义），而自定义绘制里没接这一段 —— 于是带 cell 的
 *       成员（爬虫/腿/悬浮这类）合体后那块 cell 就没了。</li>
 *   <li>"ElevationMoveUnit 在液体上不受液体 buff"：原版 {@code UnitEntity.update()} 里
 *       `if(isGrounded() && !type.hovering) apply(floor.status, ...)` —— 悬浮单位靠
 *       {@code type.hovering} 免掉地形状态（wet/tarred…）。巨兽派生类型从没设置过 `hovering`，
 *       于是**成员不吃的液体状态，合体后反而全吃**。</li>
 *   <li>"elude 的武器合体后变成极其不精准的炮、子弹不拐弯了"：elude 的武器是导弹
 *       （MissileBulletType，靠 homingPower 拐弯）；要量清楚巨兽身上这门武器到底差在哪。</li>
 * </ol>
 *
 * 本测试先量事实（cell 区域/液体状态/导弹转向），再据此断言"巨兽要和悬浮成员一致"。
 */
public class MegaHoverTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaHoverTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MH] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
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
    static Unit noAi(Unit u, float x, float y){
        u.controller(new DummyController());
        u.set(x, y);
        u.add();
        return u;
    }
    static Unit mergeAt(float x, float y, UnitType... types){
        try{
            Seq<Unit> us = new Seq<>();
            for(int i = 0; i < types.length; i++){
                Unit u = types[i].create(Team.sharded);
                u.set(x + i * 14f, y);
                u.add();
                us.add(u);
            }
            run(2);
            Object mega = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("mergeSelected", Seq.class).invoke(null, us);
            run(2);
            return mega instanceof Unit mu ? mu : null;
        }catch(Throwable t){ System.out.println("[MH] 融合失败: " + t); return null; }
    }
    /** 让 u 每帧对着 target 按住扳机开火。 */
    static void aimAndShoot(Unit u, Teamc target){
        if(u == null || !u.isValid()) return;
        if(u.mounts() != null) for(WeaponMount m : u.mounts()){
            m.target = target;
            m.aimX = target.x(); m.aimY = target.y();
            m.shoot = true; m.rotate = true;
            if(m.reload > 0.001f) m.reload = 0f;   // 测转向/精度时别等装填
        }
        u.aimX = target.x(); u.aimY = target.y();
        u.isShooting(true);
    }
    static String weaponFacts(UnitType t){
        StringBuilder sb = new StringBuilder();
        for(Weapon w : t.weapons){
            sb.append("\n    ").append(w.getClass().getName())
              .append(" bullet=").append(w.bullet == null ? "null" : w.bullet.getClass().getSimpleName())
              .append(" homingPower=").append(w.bullet == null ? "-" : w.bullet.homingPower)
              .append(" homingRange=").append(w.bullet == null ? "-" : w.bullet.homingRange)
              .append(" homingDelay=").append(w.bullet == null ? "-" : w.bullet.homingDelay)
              .append(" inaccuracy=").append(w.inaccuracy).append(" bullet.inaccuracy=").append(w.bullet == null ? "-" : w.bullet.inaccuracy)
              .append(" rotate=").append(w.rotate).append(" autoTarget=").append(w.autoTarget)
              .append(" controllable=").append(w.controllable)
              .append(" mountType=").append(w.mountType == null ? "-" : w.mountType.getClass().getSimpleName())
              .append(" x/y=").append((int)w.x).append("/").append((int)w.y);
        }
        return sb.toString();
    }
    static int totalShots(Unit u){
        int n = 0;
        if(u != null && u.mounts() != null) for(WeaponMount m : u.mounts()) n += m.totalShots;
        return n;
    }

    /** 按**挂载**打印真实武器（WeaponMount.weapon）：巨兽的 type.weapons 只是幽灵武器，
     *  真实武器在 mounts 上（见 MegaUnitEntity.rebuildMounts）。 */
    static String mountWeaponFacts(Unit u){
        if(u == null || u.mounts() == null) return "（无挂载）";
        StringBuilder sb = new StringBuilder();
        for(WeaponMount m : u.mounts()){
            Weapon w = m.weapon;
            sb.append("\n    挂载 ").append(m.getClass().getSimpleName())
              .append(" 武器=").append(w == null ? "null" : w.getClass().getName())
              .append(" bullet=").append(w == null || w.bullet == null ? "null" : w.bullet.getClass().getSimpleName())
              .append(" homingPower=").append(w == null || w.bullet == null ? "-" : w.bullet.homingPower)
              .append(" homingDelay=").append(w == null || w.bullet == null ? "-" : w.bullet.homingDelay)
              .append(" inaccuracy=").append(w == null ? "-" : w.inaccuracy)
              .append(" x/y=").append(w == null ? "-" : ((int)w.x + "/" + (int)w.y));
        }
        return sb.toString();
    }

    /** 发弹并统计"子弹转向量"（每颗子弹累计 |Δrotation|）+ 命中情况。 */
    static void fireReport(String tag, Unit shooter, Unit enemy, int ticks){
        arc.struct.ObjectMap<Bullet, Float> turn = new arc.struct.ObjectMap<>();
        arc.struct.ObjectMap<Bullet, Float> lastRot = new arc.struct.ObjectMap<>();
        int shots = 0;
        float enemyStart = enemy.health();
        java.util.Set<String> bulletKinds = new java.util.LinkedHashSet<>();
        for(int i = 0; i < ticks; i++){
            aimAndShoot(shooter, enemy);
            run(1);
            if(shooter != null && shooter.mounts() != null)
                for(WeaponMount m : shooter.mounts()) shots += 0;   // totalShots 见下
            for(Bullet b : Groups.bullet){
                if(b.owner != shooter) continue;
                bulletKinds.add(b.type.getClass().getSimpleName() + "(homing=" + b.type.homingPower + ")");
                if(b.time < 1f) bulletKinds.add("首帧 aim=(" + (int)b.aimX + "," + (int)b.aimY + ") aimTile="
                    + (b.aimTile == null ? "null" : b.aimTile.floor().name));
                Float lr = lastRot.get(b);
                if(lr == null){ lastRot.put(b, b.rotation()); turn.put(b, 0f); }
                else{
                    float d = Math.abs(arc.math.Angles.angleDist(lr, b.rotation()));
                    turn.put(b, turn.get(b, 0f) + d);
                }
            }
        }
        int shots2 = 0;
        if(shooter != null && shooter.mounts() != null) for(WeaponMount m : shooter.mounts()) shots2 += m.totalShots;
        float totalTurn = 0f;
        int n = 0;
        for(var e : turn.entries()){ totalTurn += e.value; n++; }
        System.out.println("[MH] " + tag + " 子弹种类=" + bulletKinds);
        System.out.println("[MH] " + tag + ": 开火次数=" + shots2 + " 记录的子弹数=" + n
            + " 平均每颗累计转向=" + (n == 0 ? "-" : String.format("%.2f", totalTurn / n)) + "°"
            + " 敌方掉血=" + (int)(enemyStart - enemy.health()));
    }

    /**
     * "玩家操控"式开火：只写 unit.aimX/aimY（玩家的准星）+ mount.shoot，
     * 不替游戏设置 mount.aimX/aimY/target ——这就和玩家按住鼠标、鼠标停在敌人身上一样。
     * 返回"子弹首帧瞄准点"与目标点的偏差（越大越偏），并统计转向量。
     */
    static void playerStyleReport(String tag, Unit shooter, float tx, float ty, int ticks){
        float worst = -1f, sum = 0f; int n = 0; float turn = 0f; int bullets = 0;
        arc.struct.ObjectMap<Bullet, Float> lastRot = new arc.struct.ObjectMap<>();
        for(int i = 0; i < ticks; i++){
            if(shooter != null && shooter.isValid()){
                shooter.aimX = tx; shooter.aimY = ty;
                if(shooter.mounts() != null) for(WeaponMount m : shooter.mounts()){
                    m.shoot = true;
                    if(m.reload > 0.001f) m.reload = 0f;
                }
                shooter.isShooting(true);
            }
            run(1);
            for(Bullet b : Groups.bullet){
                if(b.owner != shooter) continue;
                bullets++;
                if(b.time < 1.5f){
                    float d = arc.math.Mathf.dst(b.aimX, b.aimY, tx, ty);
                    sum += d; n++; worst = Math.max(worst, d);
                }
                Float lr = lastRot.get(b);
                if(lr == null) lastRot.put(b, b.rotation());
                else{ turn += Math.abs(arc.math.Angles.angleDist(lr, b.rotation())); lastRot.put(b, b.rotation()); }
            }
        }
        System.out.println("[MH] " + tag + "（玩家式瞄准）: 子弹首帧瞄准点偏离目标 平均="
            + (n == 0 ? "-" : String.format("%.1f", sum / n)) + " 最差=" + (int)worst
            + " 累计转向/子弹=" + (bullets == 0 ? "-" : String.format("%.1f", turn / bullets)) + "°");
    }

    /**
     * **自然 AI 路径**：不替游戏设置任何瞄准/目标，只把敌人放进射程里，看单位自己会不会瞄准/开火。
     * 打印 unit.aim、每个挂载的 target/aim/开火次数 —— 玩家看到的"极其不精准的直线炮"
     * 通常就是"根本没瞄准"（aim 停在 0,0 或只按身体朝向打）。
     */
    static void aiReport(String tag, Unit u, Unit enemy, int ticks){
        int before = totalShots(u);
        for(int i = 0; i < ticks; i++) run(1);
        int after = totalShots(u);
        StringBuilder sb = new StringBuilder();
        if(u != null && u.mounts() != null) for(WeaponMount m : u.mounts()){
            sb.append("\n      target=").append(m.target == null ? "null"
                : m.target.getClass().getSimpleName() + "@" + (int)m.target.x() + "," + (int)m.target.y())
              .append(" mount.aim=").append((int)m.aimX).append(",").append((int)m.aimY)
              .append(" shoot=").append(m.shoot).append(" 枪数=").append(m.totalShots);
        }
        System.out.println("[MH] " + tag + "（自然 AI）: unit.aim=" + (u == null ? "-" : ((int)u.aimX + "," + (int)u.aimY))
            + " 射击次数 " + before + "→" + after + " 控制器=" + (u == null || u.controller() == null ? "-" : u.controller().getClass().getSimpleName())
            + " 挂载:" + sb);
    }

    /**
     * 【围一圈 + 左右不串】用户要求：武器仍然围着本体一圈，但"原来在左边的在圆的左边、
     * 原来在右边的在圆的右边"。可观测的不变量：
     *   ①每把武器都落在圆环上（到本体中心距离 ≈ hitSize*0.55，误差 < 8%）；
     *   ②镜像搭档（otherSide 互相指向，原版就是 x 取反的一对）必须落在圆的**两侧**
     *     （横向坐标反号）—— 修前它们被摊成"右/前"（一个 x>0、一个 x≈0），这条会挂；
     *   ③没有两把武器重叠在同一个槽位。
     */
    /** 采样 cellColor 的亮度，返回脉冲幅度（max-min）。低血量时原版 cell 会闪，幅度应明显 > 0。 */
    static float cellFlashAmp(UnitType dom, Unit u, int ticks){
        float min = 9f, max = -9f;
        for(int i = 0; i < ticks; i++){
            arc.graphics.Color c = dom.cellColor(u);
            float b = (c.r + c.g + c.b) / 3f;
            min = Math.min(min, b); max = Math.max(max, b);
            run(1);
        }
        return max - min;
    }

    static void weaponRingReport(String tag, Unit u, Weapon[] origOf){
        if(u == null || u.mounts() == null) return;
        WeaponMount[] ms = u.mounts();
        if(ms.length == 0) return;
        float rad = u.hitSize() * 0.55f;
        int mirror = 0, mirrorOpposite = 0, onRing = 0, overlaps = 0, sideKept = 0, sideTotal = 0;
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < ms.length; i++){
            Weapon wi = ms[i].weapon;
            float len = arc.math.Mathf.len(wi.x, wi.y);
            if(Math.abs(len - rad) <= Math.max(rad * 0.08f, 1.5f)) onRing++;
            for(int j = i + 1; j < ms.length; j++){
                Weapon wj = ms[j].weapon;
                if(arc.math.Mathf.dst(wi.x, wi.y, wj.x, wj.y) < Math.max(rad * 0.2f, 1.5f)) overlaps++;
            }
            if(wi.otherSide < 0 || wi.otherSide >= ms.length) continue;
            mirror++;
            Weapon wo = ms[wi.otherSide].weapon;
            boolean sideOk = (wi.x > 0.001f && wo.x < -0.001f) || (wi.x < -0.001f && wo.x > 0.001f);
            if(sideOk) mirrorOpposite++;
            if(origOf != null){
                Weapon ow = origOf[i % origOf.length];
                if(Math.abs(ow.x) > 0.5f){
                    sideTotal++;
                    if(Math.signum(wi.x) == Math.signum(ow.x)) sideKept++;
                }
                sb.append(" [原 (").append((int)ow.x).append(",").append((int)ow.y).append(")");
            } else sb.append(" [原 -");
            sb.append("\n      [").append(i).append("] x=").append((int)wi.x).append(",y=").append((int)wi.y)
              .append(" ↔ [").append(wi.otherSide).append("] x=").append((int)wo.x).append(",y=").append((int)wo.y)
              .append(" 左右分居=").append(sideOk);
        }
        System.out.println("[MH] " + tag + " 武器圆: 在圆环上 " + onRing + "/" + ms.length
            + "、镜像搭档分居两侧 " + mirrorOpposite + "/" + mirror + "、左右不串 " + sideKept + "/" + sideTotal
            + "、重叠 " + overlaps + "；圆半径=" + (int)rad + sb);
        if(sideTotal > 0) check("原来在左/右的武器合体后仍在圆的同一侧（" + sideKept + "/" + sideTotal + "）", sideKept == sideTotal);
        if(mirror > 0) check("镜像武器对在圆上左右分居（" + mirrorOpposite + "/" + mirror + "）", mirrorOpposite == mirror);
        check("武器没有重叠在同一槽位（重叠 " + overlaps + "）", overlaps == 0);
        if(u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity"))
            check("所有武器都在武器圆上（" + onRing + "/" + ms.length + "）", onRing == ms.length);
    }

    /**
     * 【cell 的低血量闪烁】原版 `cellColor(unit)` 里有一段
     * `Mathf.absin(Time.time, f*5f, 1f) * (1f-f)`（f = 血量比例）—— 血量不满时 cell 的颜色
     * 会随帧脉冲（玩家看到的"受伤闪烁"）。这里按帧采样 cellColor 的亮度，量脉冲幅度：
     * 满血应该几乎不变，低血量应该有明显波动（≥ 0.05）。
     */
    static void cellFlashReport(String tag, UnitType dom, Unit u, int ticks){
        float min = 9f, max = -9f;
        for(int i = 0; i < ticks; i++){
            arc.graphics.Color c = dom.cellColor(u);
            float b = (c.r + c.g + c.b) / 3f;
            min = Math.min(min, b); max = Math.max(max, b);
            run(1);
        }
        System.out.println("[MH] " + tag + " cell 亮度: " + String.format("%.3f", min) + " ~ "
            + String.format("%.3f", max) + "（脉冲幅度 " + String.format("%.3f", max - min) + "，血量 "
            + (int)(u.healthf() * 100) + "%）");
        return; // 判定在调用处统一做
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
        UnitType hover = null;
        for(UnitType t : Vars.content.units()) if(t.name.equals("elude")) hover = t;
        if(hover == null){ System.out.println("[MH] 没有 elude"); System.exit(3); }

        System.out.println("[MH] elude: 实体=" + hover.constructor.get().getClass().getSimpleName()
            + " hovering=" + hover.hovering + " canDrown=" + hover.canDrown + " flying=" + hover.flying
            + " drawCell=" + hover.drawCell + " cellRegion=" + (hover.cellRegion == null ? "null" : hover.cellRegion + "")
            + " 武器:" + weaponFacts(hover));

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.waves = false;
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(10);
        int ox=-1, oy=-1;
        outer:
        for(int y=45;y<150;y++) for(int x=40;x<220;x++){
            boolean ok = true;
            for(int dy=-2;dy<=2 && ok;dy++) for(int dx=-3;dx<=3;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ System.out.println("[MH] 没找到陆地"); System.exit(3); }
        place(Blocks.coreShard, ox + 26, oy + 14, Team.sharded);
        run(10);
        float cx = ox * 8f, cy = oy * 8f;

        // ---------- ① 武器：本体 elude vs 巨兽（含 elude） ----------
        Unit enemy = UnitTypes.dagger.create(Team.crux);
        enemy.set(cx + 130f, cy);
        enemy.add();
        enemy.maxHealth(1e9f); enemy.health(1e9f);
        run(5);
        Unit solo = noAi(hover.create(Team.sharded), cx - 20f, cy);
        run(5);
        System.out.println("[MH] elude 挂载:" + mountWeaponFacts(solo));
        weaponRingReport("elude 本体（对照）", solo, null);
        fireReport("elude 本体（对照）", solo, enemy, 120);
        playerStyleReport("elude 本体（对照）", solo, enemy.x, enemy.y, 120);
        aiReport("elude 本体（对照）", solo, enemy, 60);
        solo.remove();
        run(5);
        Unit beast = mergeAt(cx, cy, hover, hover);
        if(beast == null){ check("两只 elude 能融合（前置）", false); }
        else{
            check("两只 elude 能融合（前置）", true);
            beast.controller(new DummyController());
            beast.set(cx - 20f, cy);
            run(5);
            System.out.println("[MH] 巨兽 type.weapons（幽灵武器，仅供原版类型级判定）:" + weaponFacts(beast.type));
            System.out.println("[MH] 巨兽真实挂载:" + mountWeaponFacts(beast));
            weaponRingReport("巨兽（2 只 elude）", beast, UnitTypes.elude.weapons.toArray(mindustry.type.Weapon.class));
            fireReport("巨兽（含 2 只 elude）", beast, enemy, 120);
            // 玩家式瞄准：巨兽 vs 敌对靶子（只写 unit.aim + mount.shoot）
            playerStyleReport("巨兽", beast, enemy.x, enemy.y, 120);
            // 自然 AI：巨兽自己（有 CommandAI/UnitAI）会不会瞄准并开火
            aiReport("巨兽", beast, enemy, 60);
        }

        // ---------- ② 液体状态：本体悬浮成员 vs 巨兽 ----------
        Floor liq = null;
        for(Block b : Vars.content.blocks())
            if(b instanceof Floor f && f.isLiquid && f.status != null && f.status != StatusEffects.none){ liq = f; break; }
        if(liq == null){
            System.out.println("[MH] 数据目录里没有\"带状态效果的液体地形\"，跳过液体状态检查");
        }else{
            Tile t = Vars.world.tile(ox + 3, oy + 3);
            t.setFloor(liq);
            Tile t2 = Vars.world.tile(ox + 6, oy + 3);
            t2.setFloor(liq);
            run(5);
            Unit hoverOnLiquid = noAi(hover.create(Team.sharded), t.worldx(), t.worldy());
            run(30);
            boolean memberAffected = hoverOnLiquid.hasEffect(liq.status);
            System.out.println("[MH] 液体地形=" + liq.name + " 状态=" + liq.status.name
                + " → 本体 elude 吃到=" + memberAffected + "（原版悬浮单位应当 false）");
            check("悬浮成员（elude）在带状态的液体上不吃液体状态（前置）", !memberAffected);
            hoverOnLiquid.remove();
            run(5);
            Unit hoverBeast = mergeAt(t2.worldx(), t2.worldy(), hover, hover);
            if(hoverBeast == null) check("液体上两只 elude 能融合（前置）", false);
            else{
                hoverBeast.controller(new DummyController());
                hoverBeast.set(t2.worldx(), t2.worldy());
                run(30);
                boolean beastAffected = hoverBeast.hasEffect(liq.status);
                System.out.println("[MH] 巨兽（含 2 只 elude）在同样地形上吃到=" + beastAffected
                    + " type.hovering=" + hoverBeast.type.hovering);
                check("巨兽（悬浮成员）也不吃液体状态（用户报的「受液体 buff」）", !beastAffected);
            }
        }

        // ---------- ③ cell 的低血量闪烁：单独 crawler vs crawler 巨兽（都压到 30% 血） ----------
        Unit crawlLow = UnitTypes.crawler.create(Team.sharded);
        crawlLow.set(cx - 60f, cy + 60f);
        crawlLow.add();
        crawlLow.health(crawlLow.maxHealth() * 0.3f);
        run(5);
        float loneAmp = cellFlashAmp(UnitTypes.crawler, crawlLow, 90);
        System.out.println("[MH] 单独 crawler（30% 血）cell 脉冲幅度=" + String.format("%.3f", loneAmp));
        check("单独 crawler 低血量时 cell 在闪（脉冲幅度 > 0.05）", loneAmp > 0.05f);
        crawlLow.remove();
        run(5);
        Unit crawlBeastLow = mergeAt(cx + 60f, cy + 60f, UnitTypes.crawler, UnitTypes.crawler);
        if(crawlBeastLow == null) check("两只 crawler 能融合（前置）", false);
        else{
            crawlBeastLow.health(crawlBeastLow.maxHealth() * 0.3f);
            run(5);
            float beastAmp = cellFlashAmp(UnitTypes.crawler, crawlBeastLow, 90);
            System.out.println("[MH] crawler 巨兽（30% 血）cell 脉冲幅度=" + String.format("%.3f", beastAmp)
                + " healthf=" + crawlBeastLow.healthf());
            check("巨兽低血量时 cell 也要闪（脉冲幅度 > 0.05）", beastAmp > 0.05f);
        }

        System.out.println("[MH] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
