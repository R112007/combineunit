package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*;
import mindustry.entities.units.UnitController;
import mindustry.game.*; import mindustry.gen.*; import mindustry.io.SaveIO; import mindustry.maps.Map; import mindustry.mod.*;
import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户报："附身组合巨兽身上后退出地图后重新进去，不能攻击，得重新附身才行"。
 *
 * <p>怀疑的链路（读档时）：
 * <ol>
 *     <li>{@code SaveIO.load} → {@code Logic.reset()} → **{@code Groups.clear()}** —— 玩家也被从
 *         {@code Groups.player} 里清掉了；</li>
 *     <li>{@code UnitEntity.read()} 里 `this.controller = TypeIO.readController(read, this.controller)`，
 *         存档里写的是"玩家控制器"（type 0 + player id），可 `Groups.player.getByID(id)` 此时是空的
 *         → 原版 `return prev`（= 新建实体的 controller，null）；</li>
 *     <li>我们的 {@code refreshDerived()} 有"controller == null 就按类型造一个"的兜底
 *         → 巨兽被塞了一个 **AI 控制器**；</li>
 *     <li>AI 控制器每帧在 {@code AIController.updateWeapons()} 里复位 `mount.shoot/mount.rotate`
 *         → 玩家的开火输入被覆盖 → "不能攻击"；重新附身（`player.unit(mega)` → `unit.controller(player)`）
 *         之后 AI 不再跑 → 又能打了。</li>
 * </ol>
 *
 * <p>本测试在 headless 里把这条路走一遍（attach → 开枪 → SaveIO.save/load → 再开枪 → 重新附身再开枪），
 * 打印每个阶段的控制器/玩家单位/开火数。
 */
public class MegaPossessLoadTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaPossessLoadTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MPL] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void log(String s){ System.out.println("[MPL] " + s); }
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
            for(int i = 0; i < types.length; i++) us.add(spawn(types[i], x + i * 14f, y));
            run(2);
            Object mega = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("mergeSelected", Seq.class).invoke(null, us);
            run(2);
            if(mega instanceof Unit mu){ mu.set(x, y); return mu; }
        }catch(Throwable t){ log("融合失败: " + t); }
        return null;
    }
    static long shots(Unit u){
        long n = 0;
        for(var m : u.mounts()) n += m.totalShots;
        return n;
    }
    /** 有多少把武器"当前处于开火状态"（玩家的开火输入有没有被 AI 覆写，看这个最直接）。 */
    static int shootCount(Unit u){
        int n = 0;
        for(var m : u.mounts()) if(m.shoot) n++;
        return n;
    }
    /** 模拟玩家开火（DesktopInput.updateMovement 的尾巴）。 */
    static void fireTick(Unit u){
        // 瞄点放在"机身当前朝向的正前方"：原版 Weapon.update 对可转炮塔要求
        // `within(mount.rotation, mount.targetRotation, shootCone)`，而机身朝向会被原版
        // 移动逻辑（rotateMove）慢慢拧回去 —— 沿机身朝向瞄，炮塔就不用追着一个固定的世界方向转。
        u.aim(u.x + arc.math.Angles.trnsx(u.rotation(), 200f),
              u.y + arc.math.Angles.trnsy(u.rotation(), 200f), true);
        u.controlWeapons(true, true);
    }
    static Unit findMega(){
        for(Unit u : Groups.unit) if(u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")) return u;
        return null;
    }
    /** 打印每把武器的运行时状态（排查"为什么没开火"）。 */
    static String dumpMounts(Unit u){
        StringBuilder sb = new StringBuilder();
        for(var m : u.mounts()){
            sb.append("\n      [").append(m.weapon.name).append("] shoot=").append(m.shoot)
              .append(" rotate=").append(m.rotate).append(" warmup=").append(String.format("%.2f", m.warmup))
              .append(" reload=").append(String.format("%.1f", m.reload))
              .append(" mountRot=").append((int)m.rotation).append(" targetRot=").append((int)m.targetRotation)
              .append(" unitRot=").append((int)u.rotation()).append(" cone=").append((int)m.weapon.shootCone)
              .append(" controllable=").append(m.weapon.controllable).append(" side=").append(m.side)
              .append(" flipSprite=").append(m.weapon.flipSprite).append(" otherSide=").append(m.weapon.otherSide)
              .append(" canShoot=").append(u.canShoot()).append(" isFlying=").append(u.isFlying());
        }
        return sb.toString();
    }

    /** 读一个私有/继承字段（反射）。 */
    static Object fval(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                }catch(Throwable ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){ }
        return null;
    }

    static String ctrlName(Unit u){
        UnitController c = u == null ? null : u.controller();
        return c == null ? "null" : c.getClass().getSimpleName();
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

        // 【不碰 Vars.player】headless 建不了 Vars.control（SoundControl 要原生 soloud），
        // 而 Logic.update → GlobalVars 只在"本地玩家 Vars.player != null"时才读 control.sound。
        // 这里用"一个服务端侧的玩家对象"来当附身者：对 TypeIO.writeController/readController 来说一模一样
        // （都是把 player id 写进存档、按 Groups.player.getByID 找回来）。

        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.state.rules.waves = false;
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.disableUnitCap = true;
        Vars.logic.play();
        run(20);
        for(int y=25;y<175;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(10);
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
        if(ox < 0){ log("没找到陆地"); System.exit(4); }
        place(Blocks.coreShard, ox + 30, oy + 16, Team.sharded);
        run(10);

        // 造一个"附身者"玩家（不设 Vars.player，理由见上面注释）
        Player owner = Player.create();
        owner.name = "probe";
        owner.team(Team.sharded);
        owner.add();
        log("附身者玩家: " + owner.name + " id=" + owner.id + " 已加入=" + owner.isAdded());

        Unit mega = mergeAt(ox * 8f + 300f, oy * 8f, UnitTypes.dagger, UnitTypes.dagger);
        if(mega == null){ check("巨兽能融合（前置）", false); System.exit(4); }
        check("巨兽能融合（前置）", true);

        // ---- ① 附身 ----
        owner.unit(mega);
        run(2);
        log("附身后: owner.unit()==巨兽? " + (owner.unit() == mega) + " 巨兽控制器=" + ctrlName(mega));
        check("附身后玩家成了巨兽的控制器", mega.controller() == (UnitController)owner);

        long s0 = shots(mega);
        for(int i = 0; i < 300; i++){ fireTick(mega); run(1); }
        long before = shots(mega) - s0;
        log("附身后开火 60 tick = " + before + " 发" + dumpMounts(mega));
        check("附身后玩家的开火输入能落到武器上（" + shootCount(mega) + "/" + mega.mounts().length
            + " 把 shoot=true）", shootCount(mega) == mega.mounts().length);
        check("附身后真的打出了子弹（" + before + " 发）", before > 0);

        // ---- ② 存盘 + 读档（= 用户说的"退出地图后重新进去"） ----
        arc.files.Fi file = Core.settings.getDataDirectory().child("mp_possess.msav");
        SaveIO.save(file);
        log("已存盘: " + file.absolutePath() + " (" + file.length() + " 字节)");
        SaveIO.load(file);
        // 原版客户端是在 WorldLoadEvent 里把本地玩家重新 add 回来的（Control: player.add()）——
        // headless 没有那个监听器，这里手工补上，模拟"重新进图之后玩家又在了"。
        owner.add();
        // 【读档后要让世界真的在跑】SaveIO.load 之后状态可能是 menu/暂停，那样 Groups.unit.update 根本不执行
        // （第一次写这个测试就踩了：单位不更新 → AI 不会覆写武器状态 → 什么都测不出来）
        Vars.logic.play();
        if(Vars.state.isPaused()) Vars.state.set(GameState.State.playing);
        run(30);

        Unit loaded = findMega();
        log("读档后: Groups.unit=" + Groups.unit.size() + " Groups.player=" + Groups.player.size()
            + "（玩家 id=" + owner.id + "，存档里记的附身者 id 见下）找到巨兽=" + (loaded != null));
        if(loaded != null){
            log("读档后: 存档里记的附身者 id=" + fval(loaded, "savedOwnerId")
                + " name=" + fval(loaded, "savedOwnerName")
                + " | Groups.player=" + Groups.player.size()
                + " 玩家=" + (owner.isAdded() ? owner.name : "未加入") + " id=" + owner.id
                + " getByID(" + owner.id + ")=" + Groups.player.getByID(owner.id));
            log("读档后: owner.unit()=" + (owner.unit() == null ? "null" : owner.unit().getClass().getSimpleName())
                + " 巨兽控制器=" + ctrlName(loaded) + " 巨兽还活着=" + !loaded.dead());
            long s1 = shots(loaded);
            for(int i = 0; i < 300; i++){ fireTick(loaded); run(1); }
            long after = shots(loaded) - s1;
            log("读档后开火 60 tick = " + after + " 发（控制器=" + ctrlName(loaded) + "）");
            check("读档后玩家仍然控制着巨兽（控制器=" + ctrlName(loaded) + "）",
                loaded.controller() == (UnitController)owner);
            check("读档后 owner.unit() 还是这只巨兽（" + (owner.unit() == loaded) + "）", owner.unit() == loaded);
            check("读档后玩家的开火输入还能落到武器上（" + shootCount(loaded) + "/" + loaded.mounts().length + " 把）",
                shootCount(loaded) == loaded.mounts().length);
            check("读档后不重新附身也能打出子弹（" + after + " 发）", after > 0);

            // ---- ③ 用户的兜底办法：重新附身 ----
            owner.unit(loaded);
            run(2);
            long s2 = shots(loaded);
            for(int i = 0; i < 60; i++){ fireTick(loaded); run(1); }
            long again = shots(loaded) - s2;
            log("重新附身后开火 60 tick = " + again + " 发（控制器=" + ctrlName(loaded) + "）");
            check("重新附身之后能开火（" + again + " 发）—— 用户说的兜底办法有效", again > 0);
        }

        System.out.println("[MPL] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
