// 复现 CT2起源（creators 5.30）的写法（那边 27 处都是这个样子）：
//
//   MyUnit.constructor = prov(() => extend(UnitTypes.<某个原版单位>.constructor.get().class, {}));
//
// 也就是"拿某个原版单位构造器产出实例的 class 当自己的超类"。
// 组合模组把原版单位的构造器换成自己的镜像类之后，这句 extend 就会去继承**模组类**；
// 安卓上 Rhino 的 JavaAdapter 是在内存 dex 里定义适配器类的，那个类加载器看不见别的模组的类
// → "Failed to define class / Failed resolution of: Lcombineunit/units/entities/CUnitEntity"
// → 整个 combineunit 加载失败、游戏崩（用户给的 1 (1).txt 就是这个）。

const compatUnit = extend(UnitType, "ctcompat-unit", {});
compatUnit.constructor = prov(() => extend(UnitTypes.eclipse.constructor.get().class, {}));

/*
 * 另一些模组不是在加载脚本时、而是在**更晚**（例如第一次进世界）才设构造器 —— 那时组合模组
 * 已经替换过一轮了，所以组合模组还要在每次世界加载后再补扫一遍（wrapForeignConstructors）。
 * 这个单位用来验那条兜底路径。
 */
const lateUnit = extend(UnitType, "ctcompat-late-unit", {});
Events.on(WorldLoadEvent, e => {
    lateUnit.constructor = prov(() => extend(UnitTypes.corvus.constructor.get().class, {}));
});
