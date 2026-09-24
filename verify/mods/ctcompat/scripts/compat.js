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
exports.compatUnit = compatUnit;
