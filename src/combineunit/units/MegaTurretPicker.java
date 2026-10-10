package combineunit.units;

import arc.Core;
import arc.Events;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.TextButton;
import arc.struct.Seq;
import arc.util.Log;
import combineunit.units.mega.MegaUnitEntity;
import combineunit.units.mega.MegaTurretBay;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.ui.Styles;

/**
 * 「选取炮台」交互（用户要求）：<b>点组合巨兽 → 巨兽头上出现"选取炮台"按钮 → 点它进入选取模式
 * （附近每座可吸收的炮台各画一个"添加"按钮）→ 点某座炮台的"添加" → 那座炮台被吸进巨兽体内</b>。
 *
 * <p>按钮是挂在 {@code Core.scene.root} 上的真实 arc 元素（不是画出来的贴图）：触摸端 / 桌面
 * 都能点，位置每帧按 {@code Core.camera.project} 跟着世界坐标走（挂 root 而不是 hudGroup 的原因
 * 见 combine 仓库 {@code SuperTurretPlacer}：hudGroup 里还叠着铺满屏幕的可点容器，挂里面的按钮
 * 会"看得见、点不动"）。
 *
 * <p>吸收本身是服务端权威的（{@link UnitComboMerge#requestAbsorbTurret}，联机走
 * {@link MegaOrderPacket}）：这里只负责"指哪"。
 *
 * <h3>入口：点一下单位就显示、再点一下就关掉（用户 2026-10-10 口径）</h3>
 * 在世界里点中<b>己方组合巨兽本体</b> → 它头上出现"选取炮台"（点了才显示，不点不显示）；
 * <b>再点同一只巨兽</b> → 连选取模式、各"添加"一起收掉（点另一只巨兽则换过去）。
 * 以前是"正在操控 / 指挥模式选中 / 鼠标悬停就自动浮出来"，现在显隐完全由玩家点的那一下决定。
 * 主按钮自己也是开关（"选取炮台" ↔ "完成选取"）；炮台被吸完 / 巨兽没了 / 退出地图 /
 * 弹出对话框时也会自动收掉。
 */
public class MegaTurretPicker{
    /** 正在"选取炮台"的巨兽（null = 不在选取模式）。 */
    private static Unit picking;
    /** 玩家点开的巨兽（点了它主按钮才显示，再点一下收掉；null = 没点开）。 */
    private static Unit toggled;
    /** 主按钮当前跟的那只巨兽（选取模式下 = picking，否则 = 点开的 toggled）。 */
    private static Unit mainAnchor;
    private static TextButton mainBtn;

    /** 附近炮台上的"添加"按钮（与 {@link #addTargets} 一一对应）。 */
    private static final Seq<TextButton> addBtns = new Seq<>();
    private static final Seq<Building> addTargets = new Seq<>();

    /** 点击回调里不直接改场景（arc 还在派发这次输入事件），下一帧再执行。 */
    private static Runnable pending;
    /** 每帧复用，免得每帧 new 一个 Seq。 */
    private static final Seq<Building> scratchTargets = new Seq<>();
    /** 主按钮当前显示的文字（变了才重新 pack，省得每帧排版）。 */
    private static String mainText;

    /** 在 mod init 时调用（无头服务端跳过，见 Main）。 */
    public static void register(){
        Events.run(Trigger.update, MegaTurretPicker::tick);
        // 【输入】排在场景**之后**（arc 的 InputMultiplexer 按加入顺序问，场景在 UI.init 时
        // 就先加了）：场景已经吃掉的点击（点在我们的按钮或别的面板上）不会进到这里；只有"点到
        // 世界里"的才进来 —— 这时：
        //   · 选取模式：点在某座待选炮台上 = 点了它那颗「添加」（吃掉这次点击；用户报过"按钮点了
        //     不合体"：触点/鼠标落在炮台本体上而不是那颗浮标上时，原版只会当成对巨兽下移动令）。
        //     点在本体上 = "再点一下就关掉" → 收掉。
        //   · 平时：点中己方巨兽本体 = 切换主按钮的显示/隐藏（**不**吃掉点击：原版还要靠它
        //     选中 / 操控这只单位）。
        if(Core.input != null){
            Core.input.addProcessor(new Processor());
        }
    }

    private static class Processor implements arc.input.InputProcessor{
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, arc.input.KeyCode button){
            if(pointer != 0 || button != arc.input.KeyCode.mouseLeft) return false;
            if(Vars.state == null || !Vars.state.isGame() || Core.camera == null) return false;
            Vec2 w = Core.camera.unproject(screenX, screenY);
            return tapWorld(w.x, w.y);
        }
    }

    /**
     * 世界里的一次点击（输入处理器 → 这里）。
     *
     * <p><b>选取模式</b>：落点在某座待选炮台身上就当点了那颗「添加」（吃掉这次点击，顺带不会变成
     * 对巨兽下移动令）；落在巨兽本体上 = 玩家"再点了一下" → 收掉整个选取（点的是另一只巨兽则换过去）。
     *
     * <p><b>平时</b>：点中己方巨兽本体 = 切换「选取炮台」按钮的显示（点一下开、再点一下关）。
     * 这条路**不消费**这次点击 —— 原版还要靠它选中 / 操控这只单位。
     *
     * @return 吃没吃掉这次点击
     */
    public static boolean tapWorld(float wx, float wy){
        if(picking != null){
            if(picking.dead) return false;
            Building hit = pickUnder(wx, wy);
            if(hit != null){
                pending = () -> absorbSafely(hit);
                return true;
            }
            Unit u = unitUnder(wx, wy);
            if(u != null){
                pending = () -> {
                    if(u == picking) closeAll();      // 再点一下这只巨兽 → 关掉
                    else show(u);                     // 点另一只巨兽 → 换过去
                };
                return true;
            }
            return false;
        }
        Unit u = unitUnder(wx, wy);
        if(u != null){
            pending = () -> toggleMain(u);
        }
        return false;                                     // 平时不吃点击：原版要选中/操控它
    }

    /** 落点上的己方组合巨兽（点本体就是点开关）。只认己方 —— 点敌人不会弹出按钮。 */
    private static Unit unitUnder(float wx, float wy){
        Unit best = null;
        float bestD = Float.MAX_VALUE;
        for(Unit u : Groups.unit){
            if(!pickable(u)) continue;
            // 判定半径照原版口径来（hitSize 是"半径量级"；太苛刻在手机上点不中）
            float r = Math.max(8f, u.hitSize() * 0.9f);
            float d = u.dst(wx, wy);
            if(d > r || d >= bestD) continue;
            best = u;
            bestD = d;
        }
        return best;
    }

    /** 点巨兽：没开 → 显示「选取炮台」；开着 → 连选取模式一起收掉（用户 2026-10-10 口径）。 */
    private static void toggleMain(Unit mega){
        if(!pickable(mega)) return;
        if(toggled == mega && picking == null){
            closeAll();
        }else{
            toggled = mega;
            mainAnchor = mega;
        }
    }

    /** 全部收掉：主按钮 + 选取模式 + 各「添加」（"再点一下巨兽"走这条）。 */
    private static void closeAll(){
        toggled = null;
        exit();
        mainAnchor = null;
    }

    /** 落点在某座待选炮台的格子里就返回它（炮台本体也能点）。 */
    private static Building pickUnder(float wx, float wy){
        if(picking == null) return null;
        float r = targetRadius((MegaUnitEntity)picking);
        for(Building b : Groups.build){
            if(!MegaTurretBay.absorbable(b, picking.team())) continue;
            if(picking.dst(b) > r) continue;
            float half = b.block.size * 4f + 2f;
            if(Math.abs(wx - b.x) <= half && Math.abs(wy - b.y) <= half) return b;
        }
        return null;
    }

    /**
     * 吸收一座炮台：点下去那一帧拿到的建筑引用可能是旧的（客户端读快照/世界重建时会换对象），
     * 这里按"格"重新取当前那座；取不到就是已经不在了，留一行日志跳过。
     */
    private static void absorbSafely(Building b){
        if(picking == null) return;
        Building now = b.tile == null || Vars.world == null ? null : Vars.world.build(b.tile.x, b.tile.y);
        if(now == null) now = b;
        UnitComboMerge.requestAbsorbTurret((MegaUnitEntity)picking, now);
    }

    /** 当前是不是在选取炮台模式（面板等地方想知道时可以问一句）。 */
    public static boolean picking(){
        return picking != null;
    }

    /** 主按钮现在显示着没有（点开的那只巨兽还在，或在选取模式里）——验证器/面板想知道时可以问一句。 */
    public static boolean shown(){
        return picking != null || toggled != null;
    }

    /**
     * 进入"选取炮台"模式（面板上的"选取炮台"按钮 / 巨兽头上的主按钮 / 验证器直接调）。
     * 传入非巨兽或巨兽已失效则退出。
     */
    public static void show(Unit mega){
        if(!(mega instanceof MegaUnitEntity) || !mega.isValid()){
            exit();
            return;
        }
        toggled = mega;
        picking = mega;
        mainAnchor = mega;
        refreshAddButtons();
    }

    /** 退出选取模式，收掉"添加"按钮（主按钮留在原地 = 那只巨兽的「选取炮台」）。 */
    public static void exit(){
        picking = null;
        clearAddButtons();
    }

    // -------------------- 每帧 --------------------

    private static void tick(){
        if(Vars.headless || Vars.ui == null || Core.scene == null || Core.scene.root == null
                || Vars.state == null || !Vars.state.isGame()){
            reset();
            return;
        }
        if(pending != null){
            Runnable r = pending;
            pending = null;
            try{
                r.run();
            }catch(Throwable t){
                Log.err("[combineunit] 选取炮台操作失败", t);
            }
        }

        // 正在选取 / 点开的那只巨兽没了、换队了 → 收掉
        if(picking != null && !pickable(picking)){
            exit();
        }
        if(toggled != null && !pickable(toggled)){
            toggled = null;
        }

        // 有弹窗时把浮动按钮收起来（可见性而已，状态保留）：别糊在弹窗上挡点击
        if(Core.scene.hasDialog()){
            setVisible(false);
            return;
        }

        // 【显隐完全跟着"玩家点没点过这只巨兽"走】不点不显示（用户 2026-10-10 口径）：
        // 点一下巨兽 → 主按钮出现并一直跟着它；再点一下 → toggled 清掉、按钮收掉。
        if(picking == null){
            mainAnchor = toggled;
            if(mainAnchor != null) showMain(); else hideMain();
        }else{
            mainAnchor = picking;
            showMain();
            refreshAddButtons();
        }
        setVisible(true);
    }

    private static void setVisible(boolean vis){
        if(mainBtn != null) mainBtn.visible = vis;
        for(TextButton b : addBtns) b.visible = vis;
    }

    /** 己方、活着、还是组合巨兽。 */
    private static boolean pickable(Unit u){
        return u instanceof MegaUnitEntity && u.isValid() && !u.dead
                && Vars.player != null && u.team() == Vars.player.team();
    }

    // -------------------- 主按钮（巨兽头上） --------------------

    private static void showMain(){
        if(mainBtn == null){
            mainBtn = new TextButton("选取炮台", Styles.defaultt);
            mainBtn.getLabel().setFontScale(0.85f);
            mainBtn.getLabel().setWrap(false);   // 四个字别被 pack() 折成两行
            mainBtn.addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, arc.input.KeyCode button){
                    // 用 pending 延后处理：点回调里动场景会把正在派发的元素摘掉（arc 的 input NPE）
                    pending = MegaTurretPicker::onMainClick;
                    return true;
                }
            });
            Core.scene.root.addChild(mainBtn);
            mainText = null;   // 新按钮没排过版，走下面那条"pack 一次"的路
        }
        String text = picking == null ? "选取炮台" : "完成选取";
        if(!text.equals(mainText)){
            mainText = text;
            mainBtn.setText(text);
            mainBtn.pack();
        }
        mainBtn.visible = true;
        mainBtn.update(MegaTurretPicker::placeMain);
    }

    private static void hideMain(){
        if(mainBtn != null){
            try{
                mainBtn.remove();
            }catch(Throwable ignored){
            }
            mainBtn = null;
        }
    }

    private static void placeMain(){
        if(mainBtn == null || mainAnchor == null) return;
        // 底边抬到巨兽身体上缘之上（hitSize 是半径量级），再给 8px 固定缝（见 placeAbove）
        placeAbove(mainBtn, mainAnchor.x, mainAnchor.y, mainAnchor.hitSize() * 0.9f);
    }

    private static void onMainClick(){
        if(picking != null){
            exit();
        }else if(pickable(mainAnchor)){
            show(mainAnchor);
            if(Vars.ui != null){
                try{
                    if(bayFull((MegaUnitEntity)mainAnchor)){
                        // 舱位满了就别让玩家点半天没反应（用户 2026-10-08 就是踩了这个）
                        Vars.ui.showInfoFade("[orange]炮台舱已满（这只巨兽最多 "
                            + ((MegaUnitEntity)mainAnchor).bay().maxTurrets()
                            + " 座 = 成员武器数之和），先「释放全部炮台」或拆掉几座再选[]");
                    }else{
                        Vars.ui.showInfoFade("[accent]点附近炮台上的「添加」（或直接点那座炮台）把它收进巨兽体内[]");
                    }
                }catch(Throwable ignored){
                }
            }
        }
    }

    /** 炮台舱满了（到上限就吃不进去了 —— 吸收会直接 return false）。 */
    private static boolean bayFull(MegaUnitEntity mega){
        return mega != null && mega.hasTurrets() && mega.bay().size() >= mega.bay().maxTurrets();
    }

    // -------------------- 炮台上的"添加"按钮 --------------------

    /** 附近能吸收的炮台（同队、在吸收半径内）；顺序稳定，按钮不与目标错位。 */
    private static void collectTargets(Seq<Building> out){
        out.clear();
        if(picking == null) return;
        if(bayFull((MegaUnitEntity)picking)) return;   // 舱位满了：一座也不列（点了也吃不进去）
        float r = targetRadius((MegaUnitEntity)picking);
        for(Building b : Groups.build){
            if(!MegaTurretBay.absorbable(b, picking.team())) continue;
            if(picking.dst(b) > r) continue;
            out.add(b);
        }
    }

    /**
     * 列出/可点选的半径：服务端的 {@link UnitComboMerge#absorbRadius} **减 8px 余量**。
     * 联机时服务端按它自己那份（权威）坐标再算一次半径，客户端插值出来的位置可能差一点点 ——
     * 贴着边界的那种（客户端显示、服务端判超距）点了会"没反应"。留一档余量后，
     * 能显示出来的就一定在服务端的半径内。
     */
    private static float targetRadius(MegaUnitEntity mega){
        return Math.max(16f, UnitComboMerge.absorbRadius(mega) - 8f);
    }

    /** 目标集合变了就重建按钮（吸走一座、走近一座都会变）。 */
    private static void refreshAddButtons(){
        if(picking == null){
            clearAddButtons();
            return;
        }
        Seq<Building> targets = scratchTargets;
        collectTargets(targets);
        boolean same = targets.size == addTargets.size;
        if(same){
            for(int i = 0; i < targets.size; i++){
                if(targets.get(i) != addTargets.get(i)){
                    same = false;
                    break;
                }
            }
        }
        if(!same){
            clearAddButtons();
            addTargets.addAll(targets);
            for(int i = 0; i < addTargets.size; i++){
                Building b = addTargets.get(i);
                TextButton btn = new TextButton("添加", Styles.defaultt);
                btn.getLabel().setFontScale(0.8f);
                btn.pack();
                btn.addListener(new InputListener(){
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, arc.input.KeyCode button){
                        pending = () -> absorbSafely(b);
                        return true;
                    }
                });
                btn.update(() -> placeAbove(btn, b.x, b.y, b.block == null ? 8f : b.block.size * 4f));
                Core.scene.root.addChild(btn);
                addBtns.add(btn);
            }
        }
    }

    private static void clearAddButtons(){
        for(TextButton b : addBtns){
            try{
                b.remove();
            }catch(Throwable ignored){
            }
        }
        addBtns.clear();
        addTargets.clear();
    }

    // -------------------- 世界 → 屏幕 --------------------

    /**
     * 把元素摆在世界坐标 (wx, wy) 的**正上方**（底边离开目标 {@code worldLift} 个世界单位）。
     *
     * <p>【坐标系】arc 的 {@code Camera.project} 返回的是**左下原点、y 向上**的屏幕坐标
     * （见 arc 的 {@code Viewport.toScreenCoordinates}：要拿"左上原点、y 向下"的坐标得在 project
     * 之后再 {@code height - y} 翻一次），而 arc 的 Scene 用的也是同一套 —— {@code Scene.act()} 里
     * {@code root.y = marginBottom}、{@code Element.setPosition} 的注释明说设的是"bottom left
     * corner"。所以这里**不能**再翻一次 y、也不能减 lift：那样按钮会整体上下镜像 + 往下偏，
     * 就是用户报的"按钮没有一直显示在炮台上面，有偏移"。root 的原点在
     * (marginLeft, marginBottom)（刘海屏留白），所以还要加回来。
     *
     * <p>{@code worldLift} 是**世界单位**，按相机缩放换算成像素 —— 镜头放大（软渲染验证器开了
     * 3 倍）时炮台贴图也跟着放大，按固定像素抬的话按钮会压在炮台身上。
     */
    private static void placeAbove(Element e, float wx, float wy, float worldLift){
        if(e == null || Core.camera == null || Core.graphics == null || Core.scene == null) return;
        Vec2 p = Core.camera.project(wx, wy);
        float zoom = Core.camera.height > 0.01f ? Core.graphics.getHeight() / Core.camera.height : 1f;
        e.setPosition(Core.scene.marginLeft + p.x - e.getWidth() / 2f,
                Core.scene.marginBottom + p.y + worldLift * zoom + 8f);
    }

    private static void reset(){
        toggled = null;
        exit();
        hideMain();
        mainAnchor = null;
        pending = null;
    }
}
