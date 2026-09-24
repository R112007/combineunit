package combineunit.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.*;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*;
import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.type.*; import mindustry.entities.units.WeaponMount; import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 用户要求（武器位置与射程精修）：
 * <ol>
 *     <li>x=0 的武器单独放**中间一列**；</li>
 *     <li>mirror=true 的镜像对**左右对称**；</li>
 *     <li>mirror=false 且 x&gt;0 放**右边**，x&lt;0 放**左边**；</li>
 *     <li>`maxRange` 重新算（按实际武器，而不是以前写死的 260）。</li>
 * </ol>
 *
 * <p>测试把"中间枪 / 只有右边的枪 / 只有左边的枪 / 左右镜像对"四种成员凑成一只巨兽，
 * 然后逐把检查落位（按挂载顺序能对上成员武器表），并检查 range/maxRange。
 */
public class MegaWeaponLayoutTest implements ApplicationListener{
    static String dataDir="/tmp/mp_unit/data";
    static int pass=0, fail=0;
    static ClassLoader ml;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new MegaWeaponLayoutTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[MWL] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        return Vars.world.build(ax,ay);
    }
    /** 找一个"有武器、且武器都满足 pred"的单位类型。 */
    static UnitType find(java.util.function.Predicate<UnitType> pred){
        for(UnitType t : Vars.content.units()){
            if(t.weapons == null || t.weapons.size == 0) continue;
            boolean any = false, all = true;
            for(Weapon w : t.weapons){
                if(w.bullet == null) continue;
                any = true;
                if(!pred.test(t)){ all = false; }
            }
            if(any && all && pred.test(t)) return t;
        }
        return null;
    }
    static boolean hasMid(UnitType t){ for(Weapon w : t.weapons) if(w.bullet != null && Math.abs(w.x) < 0.5f) return true; return false; }
    static boolean hasRightSingle(UnitType t){ for(Weapon w : t.weapons) if(w.bullet != null && w.otherSide < 0 && w.x > 0.5f) return true; return false; }
    static boolean hasLeftSingle(UnitType t){ for(Weapon w : t.weapons) if(w.bullet != null && w.otherSide < 0 && w.x < -0.5f) return true; return false; }
    static boolean hasMirror(UnitType t){ for(Weapon w : t.weapons) if(w.bullet != null && w.otherSide >= 0) return true; return false; }

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

        // 找四种成员（找不到就跳过那一类）
        UnitType midType = null, rightType = null, leftType = null, pairType = null;
        for(UnitType t : Vars.content.units()){
            if(t.weapons == null) continue;
            if(pairType == null && hasMirror(t)) pairType = t;
            if(midType == null && hasMid(t)) midType = t;
            if(rightType == null && hasRightSingle(t)) rightType = t;
            if(leftType == null && hasLeftSingle(t)) leftType = t;
        }
        System.out.println("[MWL] 成员类型: 中间枪=" + name(midType) + " 只右=" + name(rightType)
            + " 只左=" + name(leftType) + " 镜像对=" + name(pairType));
        if(pairType == null){ System.out.println("[MWL] 找不到镜像对武器类型的单位"); System.exit(3); }

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
        for(int y=45;y<150;y++) for(int x=40;x<220;x++){
            boolean ok = true;
            for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-4;dx<=4;dx++){
                Tile t = Vars.world.tile(x+dx, y+dy);
                if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
            }
            if(ok){ ox=x; oy=y; break outer; }
        }
        if(ox < 0){ System.out.println("[MWL] 没找到陆地"); System.exit(3); }
        place(Blocks.coreShard, ox + 30, oy + 16, Team.sharded);
        run(10);

        // 【补两把"单侧武器"】这个数据集里原版单位都是"镜像对/正中"，没有 mirror=false 的单侧武器，
        // 测不到"右列/左列"这两条规则；给 dagger（本来就是镜像对成员）追加一把只有右边、一把只有左边的武器。
        {
            Weapon right = new Weapon("cc-test-right");
            right.mirror = false;
            right.x = 5f;
            right.y = 0f;
            right.bullet = new mindustry.entities.bullet.BasicBulletType(2f, 1f);
            right.bullet.range = 100f;
            Weapon left = new Weapon("cc-test-left");
            left.mirror = false;
            left.x = -5f;
            left.y = 0f;
            left.bullet = new mindustry.entities.bullet.BasicBulletType(2f, 1f);
            left.bullet.range = 100f;
            UnitTypes.dagger.weapons.add(right);
            UnitTypes.dagger.weapons.add(left);
            System.out.println("[MWL] 已给 dagger 追加单侧武器：右(x=5) / 左(x=-5)（原版没有这种）");
        }

        // 凑一只巨兽：每个成员记录它自己的武器表（挂载顺序 = 成员顺序 × 成员武器顺序）
        Seq<UnitType> members = new Seq<>();
        Seq<UnitType> memberTypes = new Seq<>();
        for(UnitType t : new UnitType[]{pairType, midType, rightType, leftType}){
            if(t != null && !members.contains(t)){ members.add(t); memberTypes.add(t); }
        }
        Seq<Unit> us = new Seq<>();
        for(int i = 0; i < members.size; i++){
            Unit u = members.get(i).create(Team.sharded);
            u.set(ox * 8f + i * 10f, oy * 8f);
            u.add();
            us.add(u);
        }
        run(3);
        Object mega = null;
        try{
            mega = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                .getMethod("mergeSelected", Seq.class).invoke(null, us);
        }catch(Throwable t){ System.out.println("[MWL] 融合失败: " + t); }
        if(!(mega instanceof Unit beast)){ check("拼出一只巨兽（前置）", false); }
        else{
            check("拼出一只巨兽（前置）", true);
            float colX = Math.max(beast.hitSize() * 0.55f, 6f);
            WeaponMount[] ms = beast.mounts();
            int idx = 0, midOk = 0, midN = 0, rightOk = 0, rightN = 0, leftOk = 0, leftN = 0, pairOk = 0, pairN = 0;
            StringBuilder detail = new StringBuilder();
            for(UnitType t : memberTypes){
                for(Weapon ow : t.weapons){
                    if(ow.bullet == null || idx >= ms.length) continue;
                    Weapon nw = ms[idx].weapon;
                    float ox0 = ow.x;
                    boolean pair = ow.otherSide >= 0 && nw.otherSide >= 0;
                    float tol = Math.max(colX * 0.25f, 2f);
                    boolean ok;
                    if(pair){
                        Weapon other = ms[nw.otherSide].weapon;
                        ok = Math.abs(Math.abs(nw.x) - colX) <= tol && Math.abs(Math.abs(other.x) - colX) <= tol
                            && Math.signum(nw.x) != Math.signum(other.x) && Math.abs(nw.y - other.y) < 0.01f;
                        pairN++; if(ok) pairOk++;
                    }else if(Math.abs(ox0) < 0.5f){
                        ok = Math.abs(nw.x) < 0.01f;
                        midN++; if(ok) midOk++;
                    }else if(ox0 > 0f){
                        ok = Math.abs(nw.x - colX) <= tol;
                        rightN++; if(ok) rightOk++;
                    }else{
                        ok = Math.abs(nw.x + colX) <= tol;
                        leftN++; if(ok) leftOk++;
                    }
                    detail.append("\n      [").append(idx).append("] 原 x=").append((int)ox0).append(" → 新 x=")
                          .append((int)nw.x).append(",y=").append((int)nw.y).append(pair ? "（镜像对）" : "")
                          .append(ok ? "" : " ✗");
                    idx++;
                }
            }
            System.out.println("[MWL] 落位（colX=" + (int)colX + "）:" + detail);
            if(midN > 0) check("x=0 的武器落在中间一列（x=0）：" + midOk + "/" + midN, midOk == midN);
            if(rightN > 0) check("mirror=false 且 x>0 落在右列（x=+colX）：" + rightOk + "/" + rightN, rightOk == rightN);
            if(leftN > 0) check("mirror=false 且 x<0 落在左列（x=-colX）：" + leftOk + "/" + leftN, leftOk == leftN);
            check("镜像对左右对称（±colX、同一行）：" + pairOk + "/" + pairN, pairN > 0 && pairOk == pairN);

            // 射程重算
            float maxBullet = 0f;
            for(WeaponMount m : ms) if(m.weapon != null && m.weapon.bullet != null) maxBullet = Math.max(maxBullet, m.weapon.bullet.range);
            System.out.println("[MWL] 射程: type.range=" + beast.type.range + " type.maxRange=" + beast.type.maxRange
                + " range()=" + beast.range() + "（最大弹体射程=" + maxBullet + "，hitSize=" + (int)beast.hitSize() + "）");
            check("type.range 不再是写死的 260（按武器重算）", Math.abs(beast.type.range - 260f) > 0.001f
                || Math.abs(maxBullet - 260f) < 1f);
            check("type.maxRange == type.range（同一个实测值）", Math.abs(beast.type.maxRange - beast.type.range) < 0.01f);
            check("range() 与 type.range 一致", Math.abs(beast.range() - beast.type.range) < 0.01f);
            check("射程 ≥ 最大弹体射程（" + beast.type.range + " ≥ " + maxBullet + "）", beast.type.range >= maxBullet - 0.01f);
        }

        System.out.println("[MWL] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static String name(UnitType t){ return t == null ? "（无）" : t.name; }
}
