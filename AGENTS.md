# 组合单位（combineunit）仓库规则

单位侧机制（组合巨兽、共享承伤/修复、共享火力、手动编组 UI、单位镜像实体类）在本仓库维护；
建筑侧（组合单位工厂/升级厂/构造机/发射台/着陆台、组合连接器/节点、组合仓库……）在
`/root/combine` 仓库。两边共用的规矩（验证器、proot 线程规则、交付流程）见
`/root/combine/AGENTS.md` 与 `/root/.codex/AGENTS.md`，这里只重复最要紧的几条：

## 改完必须跑验证器（别只读代码下结论）

```bash
cd /root/combineunit && ./gradlew --offline deploy     # 1) 编译（含 classes.dex，安卓可装）
verify/make-dataset.sh /tmp/mp_unit/data               # 2) 数据目录：**只装 combineunit.jar**
verify/run-headless.sh mx /tmp/mp_unit/data combineunit.dbg.MegaEnvTest   # 3) 逻辑测试（11 项，见 verify/README.md）
verify/run-client.sh  mx /tmp/mp_unit/data mega        # 4) 真客户端截图（绘制/面板改动必跑，图在 ~/sd/shots/）
verify/deliver.sh                                      # 5) 交付：安卓兼容编译 + 查调试残留 + 只放 ~/sd/combineunit.jar
```

- 组合单位测试**必须**在没有 combine.jar 的数据目录里跑（脚本看到 `combine.jar` 直接 exit 4）：
  否则单位侧机制来自 combine 那一份，本仓库的改动根本没被验到。
- 跨仓库联动（工厂造单位要打组合标记）用 `/tmp/mp_both/data`（combine + combineunit 两个 jar）跑
  `combine.dbg.UnitComboBridgeTest`（测试类在 combine 仓库的 `verify/tests/`）。
- 截图必须用看图工具确认（腿/机甲腿比例、巨兽画出来没有、面板图标是不是代表成员的），别只看日志。

## proot：长跑/高频工具线程数必须有界

**不许 per-packet / per-event 起线程或进程**；常驻工具要自带线程数自检，超上限主动退出。
proot 是单线程 ptrace 事件循环，几个常驻线程 + 高频 futex 就能把它拖成活锁，
**整个会话（含 codex 自己）一起冻死**（2026-09-22 实测两次）。

## 验证次数上限：同一个问题最多跑 4 次验证

一次改动/一个现象最多跑 4 次验证（编译算 1 次，成套测试的一次运行算 1 次），之后必须写结论：
全绿，或把"没验到的部分 + 原因"如实写出来。同一份代码 + 同一套数据 + 同一个测试不许重复跑。
**并行跑多个 java/客户端不会更快**（proot 单线程 ptrace），只会互相拖慢还制造假超时 —— 一次只跑一个。

## 其他

- 临时探针/调试日志（`[dbg]`、`System.out.print`）用完就删，交付脚本会 grep 检查。
- 提交信息用中文，说清"根因 + 改法"。
