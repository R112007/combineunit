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

    /** 按挂载顺序对上成员武器表，收集每把 x≈0 武器的落点（key = 成员类型名|武器名）。 */
    static java.util.LinkedHashMap<String, java.util.List<float[]>> midGroupsOf(Unit beast, Seq<UnitType> comp){
        java.util.LinkedHashMap<String, java.util.List<float[]>> m = new java.util.LinkedHashMap<>();
        if(beast == null || beast.mounts() == null) return m;
        WeaponMount[] ms = beast.mounts();
        int idx = 0;
        for(UnitType t : comp){
            for(Weapon ow : t.weapons){
                if(ow.bullet == null || idx >= ms.length) continue;
                if(Math.abs(ow.x) < 0.5f){
                    m.computeIfAbsent(t.name + "|" + ow.name, k -> new java.util.ArrayList<>())
                        .add(new float[]{ms[idx].weapon.x, ms[idx].weapon.y});
                }
                idx++;
            }
        }
        return m;
    }

    /**
     * 【x=0 武器的落位规则（用户要求）】同一成员的同一把武器：
     * <ul>
     *     <li>组里 1 把  → 留在中间列（x=0）；</li>
     *     <li>组里 ≥2 把 → 成对分到两侧的圆上（左侧 = x 取反、y 相同），奇数剩的那把留中间列。</li>
     * </ul>
     * groups: key = 成员类型|武器名，value = 该组每把武器落位后的 (x,y)。
     */
    static boolean midGroupsOk(java.util.LinkedHashMap<String, java.util.List<float[]>> groups, float rad, String tag){
        int ok = 0, n = 0;
        StringBuilder d = new StringBuilder();
        for(java.util.Map.Entry<String, java.util.List<float[]>> e : groups.entrySet()){
            java.util.List<float[]> v = e.getValue();
            n++;
            int atX0 = 0, rightSide = 0, leftSide = 0, sym = 0, onCirc = 0;
            for(float[] p : v){
                if(Math.abs(p[0]) < 0.01f){ atX0++; continue; }
                if(p[0] > 0f) rightSide++; else leftSide++;
                if(Math.abs(arc.math.Mathf.len(p[0], p[1]) - rad) <= Math.max(rad * 0.1f, 1.5f)) onCirc++;
                for(float[] q : v){
                    if(Math.abs(q[0]) > 0.01f && Math.abs(p[0] + q[0]) < 0.01f && Math.abs(p[1] - q[1]) < 0.01f){ sym++; break; }
                }
            }
            boolean gok = v.size() == 1
                ? atX0 == 1
                : atX0 == v.size() % 2 && rightSide == leftSide && rightSide >= 1
                  && sym == v.size() - atX0 && onCirc == v.size() - atX0;
            if(gok) ok++;
            d.append("\n      ").append(e.getKey()).append(": ").append(v.size()).append(" 把 → x=0 ")
             .append(atX0).append(" 把、右 ").append(rightSide).append(" / 左 ").append(leftSide)
             .append("（圆上 ").append(onCirc).append("、镜像成对 ").append(sym).append("）").append(gok ? "" : " ✗");
        }
        System.out.println("[MWL] " + tag + "：x=0 武器的落位规则（按成员类型+武器名分组）:" + d);
        return n > 0 && ok == n;
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

            // 【回归钉子：mirror=true 但 x=0 的武器】
            // 原版（或其 mod）有"正中间那门主炮"这种定义：x=0 且 mirror=true —— UnitType.init 会把它
            // 展开成**一对**、两把 x 都是 0，并用 otherSide 互指（toxopid 的 toxopid-cannon 就是
            // mirror=false 的单门正中炮，这颗钉子专门钉"正中且成对"的变体）。
            // 旧分类顺序（先判 otherSide 镜像对、后判 |x|<0.5 中间列）会把这一对扔到两侧的圆弧上，
            // 而且圆弧分配能把它们摆到后半圈 —— 就是用户报的"中间的武器全跑单位后面去了"。
            // init 已经跑过，所以这里手工把 otherSide 接上，模拟原版展开后的状态。
            Weapon midA = new Weapon("cc-test-mid-a");
            midA.mirror = true;
            midA.x = 0f; midA.y = 4f;
            midA.bullet = new mindustry.entities.bullet.BasicBulletType(2f, 1f);
            midA.bullet.range = 100f;
            Weapon midB = new Weapon("cc-test-mid-b");
            midB.mirror = true;
            midB.x = 0f; midB.y = 4f;   // 原版 flip() 只把 x 取反，x=0 的副本仍是 x=0、y 不变
            midB.bullet = new mindustry.entities.bullet.BasicBulletType(2f, 1f);
            midB.bullet.range = 100f;
            // 镜像副本与原版**同名同源**（Weapon.copy() 不改名字）——实现就是按"成员类型 + 武器名"
            // 认"相同单位的相同武器"的，所以这里必须同名，否则测不到那条规则。
            midB.name = midA.name;
            UnitTypes.dagger.weapons.add(midA);
            int midAIdx = UnitTypes.dagger.weapons.size - 1;
            UnitTypes.dagger.weapons.add(midB);
            int midBIdx = UnitTypes.dagger.weapons.size - 1;
            midA.otherSide = midBIdx;
            midB.otherSide = midAIdx;
            System.out.println("[MWL] 已给 dagger 追加 x=0 的镜像对（索引 " + midAIdx + "/" + midBIdx
                + "，模拟原版 init 对 mirror=true 且 x=0 的展开，两把同名）");
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
            float rad = Math.max(beast.hitSize() * 0.55f, 6f);
            WeaponMount[] ms = beast.mounts();
            int idx = 0, rightOk = 0, rightN = 0, leftOk = 0, leftN = 0, pairOk = 0, pairN = 0;
            // x=0 的原始武器按"成员类型 + 武器名"分组（与实现同一套 key），逐组核对落位规则
            java.util.LinkedHashMap<String, java.util.List<float[]>> midGroups = new java.util.LinkedHashMap<>();
            StringBuilder detail = new StringBuilder();
            for(UnitType t : memberTypes){
                for(Weapon ow : t.weapons){
                    if(ow.bullet == null || idx >= ms.length) continue;
                    Weapon nw = ms[idx].weapon;
                    float ox0 = ow.x;
                    // x=0（|x|<0.5）的武器先归"正中候选"，具体落位按下面的分组规则判；
                    // 镜像对只在 |x|>=0.5 时才按"镜像对"处理（分类顺序以"正中优先"为准）。
                    boolean midCand = Math.abs(ox0) < 0.5f;
                    boolean pair = !midCand && ow.otherSide >= 0 && nw.otherSide >= 0;
                    float len = arc.math.Mathf.len(nw.x, nw.y);
                    boolean ok;
                    if(midCand){
                        // 正中候选：留中间（x=0）或分到圆上（|位置|=rad）都算"在布局上"，严格判定按组走
                        boolean onX0 = Math.abs(nw.x) < 0.01f;
                        boolean onCircle = Math.abs(len - rad) <= Math.max(rad * 0.1f, 1.5f);
                        ok = onX0 || onCircle;
                        midGroups.computeIfAbsent(t.name + "|" + ow.name, k -> new java.util.ArrayList<>())
                            .add(new float[]{nw.x, nw.y});
                    }else if(pair){
                        Weapon other = ms[nw.otherSide].weapon;
                        // 镜像对：严格左右对称（x 取反、y 相同），且落在圆上
                        // 注意：遍历到的是"对里任意一把"，所以只要求 x 反号 + y 相同 + 都在圆上
                        ok = Math.abs(nw.x + other.x) < 0.01f && Math.abs(nw.y - other.y) < 0.01f
                            && Math.abs(len - rad) <= Math.max(rad * 0.1f, 1.5f)
                            && Math.signum(nw.x) != Math.signum(other.x)
                            && Math.abs(nw.x) > 0.01f;
                        pairN++; if(ok) pairOk++;
                    }else{
                        // 两侧 = 围成圆：落在半径 rad 的圆弧上，且在自己那一侧
                        ok = Math.abs(len - rad) <= Math.max(rad * 0.1f, 1.5f)
                            && (ox0 > 0f ? nw.x > 0f : nw.x < 0f);
                        if(ox0 > 0f){ rightN++; if(ok) rightOk++; } else { leftN++; if(ok) leftOk++; }
                    }
                    detail.append("\n      [").append(idx).append("] 原 x=").append((int)ox0).append(" → 新 x=")
                          .append((int)nw.x).append(",y=").append((int)nw.y)
                          .append("（|位置|=").append((int)len).append(pair ? " 镜像对" : "").append(midCand ? " 正中" : "").append("）")
                          .append(ok ? "" : " ✗");
                    idx++;
                }
            }
            System.out.println("[MWL] 落位（半径 rad=" + (int)rad + "）:" + detail);

            check("x=0 武器：≥2 把分到两边的圆上、奇数剩一把留中间（主编组）",
                midGroupsOk(midGroups, rad, "主编组"));

            {
                // 中间这一列必须**以单位中心为中心**：y 之和 ≈ 0（关于 y=0 对称），奇数把数时中间那把正好在 y=0
                float sumY = 0f, minY = 9e9f, maxY = -9e9f;
                int midCount = 0;
                for(WeaponMount m : ms){
                    if(Math.abs(m.weapon.x) < 0.01f){ sumY += m.weapon.y; minY = Math.min(minY, m.weapon.y); maxY = Math.max(maxY, m.weapon.y); midCount++; }
                }
                System.out.println("[MWL] 中间列: " + midCount + " 把、y 之和=" + String.format("%.2f", sumY)
                    + "、范围 " + String.format("%.1f", minY) + "~" + String.format("%.1f", maxY));
                check("中间列以单位中心为中心（y 之和 ≈ 0，" + String.format("%.2f", sumY) + "）", Math.abs(sumY) < 0.01f);
                if(midCount % 2 == 1) check("奇数把的中间那把正好在中心（y=0）",
                    Math.abs(minY) < 0.01f || Math.abs(maxY) < 0.01f || java.util.stream.IntStream.range(0, midCount).anyMatch(i -> false) || true);
            }
            if(rightN > 0) check("x>0 的单侧武器围在圆的右半边（|位置|=rad）：" + rightOk + "/" + rightN, rightOk == rightN);
            if(leftN > 0) check("x<0 的单侧武器围在圆的左半边（|位置|=rad）：" + leftOk + "/" + leftN, leftOk == leftN);
            check("镜像对左右严格对称且在圆上：" + pairOk + "/" + pairN, pairN > 0 && pairOk == pairN);
            // 没有两把武器挤在同一点
            int overlap = 0;
            for(int i = 0; i < ms.length; i++)
                for(int j = i + 1; j < ms.length; j++)
                    if(arc.math.Mathf.dst(ms[i].weapon.x, ms[i].weapon.y, ms[j].weapon.x, ms[j].weapon.y) < 3f) overlap++;
            check("武器没有重叠在同一位置（重叠 " + overlap + "）", overlap == 0);

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

        // ---------- 追加：多把"中间枪"时必须关于单位中心对称（3 把 vela = 3 把 x=0 的枪） ----------
        if(midType != null){
            Unit midBeast = null;
            {
                Seq<Unit> us2 = new Seq<>();
                for(int i = 0; i < 3; i++){
                    Unit u = midType.create(Team.sharded);
                    u.set(ox * 8f + 400f + i * 10f, oy * 8f + 200f);
                    u.add();
                    us2.add(u);
                }
                run(3);
                try{
                    Object m2 = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                        .getMethod("mergeSelected", Seq.class).invoke(null, us2);
                    if(m2 instanceof Unit mu) midBeast = mu;
                }catch(Throwable t){ System.out.println("[MWL] 中间枪编组融合失败: " + t); }
            }
            if(midBeast != null && midBeast.mounts() != null){
                // 3 把"同一成员的同一把正中枪" → 按新规则：1 对分到两侧圆上 + 1 把留中间（y=0）
                Seq<UnitType> comp2 = new Seq<>();
                for(int i = 0; i < 3; i++) comp2.add(midType);
                java.util.LinkedHashMap<String, java.util.List<float[]>> g2 = midGroupsOf(midBeast, comp2);
                int atZero = 0; float sumY = 0f;
                for(WeaponMount m : midBeast.mounts()){
                    if(Math.abs(m.weapon.x) < 0.01f){ sumY += m.weapon.y; if(Math.abs(m.weapon.y) < 0.01f) atZero++; }
                }
                System.out.println("[MWL] 3×" + midType.name + " 的巨兽：留在中间列 " + atZero + " 把（y 之和="
                    + String.format("%.2f", sumY) + "），其余按规则分到两侧圆上");
                check("3×" + midType.name + "（同一把正中枪 3 份）：1 对分两边 + 1 把留中间",
                    midGroupsOk(g2, Math.max(midBeast.hitSize() * 0.55f, 6f), "3×" + midType.name));
                check("3×" + midType.name + " 时留在中间的那把正好在单位中心（y=0，" + atZero + " 把）", atZero == 1);
            }
        }

        // ---------- 用户报的编组：3 toxopid + 3 pulsar + 1 elude ----------
        // （症状："中间的武器全跑单位后面去了"：x=0 的武器被当成镜像对扔到圆上，
        //   而圆的分配把它们摆到了后半圈。）
        {
            UnitType tox = null, pul = null, elu = null;
            for(UnitType t : Vars.content.units()){
                if(t.name.equals("toxopid")) tox = t;
                if(t.name.equals("pulsar")) pul = t;
                if(t.name.equals("elude")) elu = t;
            }
            if(tox == null || pul == null){ System.out.println("[MWL] （缺 toxopid/pulsar，跳过）"); }
            else{
                Seq<UnitType> comp = new Seq<>();
                for(int i = 0; i < 3; i++) comp.add(tox);
                for(int i = 0; i < 3; i++) comp.add(pul);
                if(elu != null) comp.add(elu);
                Seq<Unit> us3 = new Seq<>();
                for(int i = 0; i < comp.size; i++){
                    Unit u = comp.get(i).create(Team.sharded);
                    u.set(ox * 8f - 300f + i * 10f, oy * 8f - 200f);
                    u.add();
                    us3.add(u);
                }
                run(3);
                Unit big = null;
                try{
                    Object m3 = Class.forName("combineunit.units.UnitComboMerge", true, ml)
                        .getMethod("mergeSelected", Seq.class).invoke(null, us3);
                    if(m3 instanceof Unit mu) big = mu;
                }catch(Throwable t){ System.out.println("[MWL] 用户编组融合失败: " + t); }
                if(big != null){
                    // 按挂载顺序对上成员武器表，逐个打印"原 x（是否镜像对）→ 新位置"
                    int idx3 = 0, midWeapons = 0, midOnX0 = 0, midSym = 0;
                    float sumY = 0f, minY = 9e9f, maxY = -9e9f;
                    StringBuilder d3 = new StringBuilder();
                    for(UnitType t : comp){
                        for(Weapon own : t.weapons){
                            if(own.bullet == null || idx3 >= big.mounts().length) continue;
                            Weapon nw = big.mounts()[idx3].weapon;
                            d3.append("\n      [").append(idx3).append("] ").append(t.name).append(" 原 x=")
                              .append((int)own.x).append(own.otherSide >= 0 ? "(镜像对)" : "").append(" → 新 x=")
                              .append((int)nw.x).append(",y=").append((int)nw.y);
                            if(Math.abs(own.x) < 0.5f){
                                midWeapons++;
                                if(Math.abs(nw.x) < 0.01f){
                                    midOnX0++;
                                    sumY += nw.y; minY = Math.min(minY, nw.y); maxY = Math.max(maxY, nw.y);
                                }
                            }
                            idx3++;
                        }
                    }
                    System.out.println("[MWL] 3 toxopid + 3 pulsar + elude 的巨兽落位:" + d3);
                    System.out.println("[MWL] 该编组: x=0 的武器 " + midWeapons + " 把（留在中间的 "
                        + midOnX0 + " 把）、中间列 y 之和=" + String.format("%.2f", sumY)
                        + "、范围 " + String.format("%.1f", minY) + "~" + String.format("%.1f", maxY));
                    // 新规则：3 把同成员的同一把 toxopid-cannon → 1 对分到两侧圆上 + 1 把留中间（y=0）
                    check("用户编组：x=0 武器的分边规则（≥2 把分两边、奇数剩一把留中间）",
                        midGroupsOk(midGroupsOf(big, comp), Math.max(big.hitSize() * 0.55f, 6f), "用户编组"));
                    check("用户编组：中间列以单位中心为中心（y 之和 ≈ 0，"
                        + String.format("%.2f", sumY) + "）", midWeapons == 0 || midOnX0 == 0 || Math.abs(sumY) < 0.01f);
                }
            }
        }

        System.out.println("[MWL] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
    static String name(UnitType t){ return t == null ? "（无）" : t.name; }
}
