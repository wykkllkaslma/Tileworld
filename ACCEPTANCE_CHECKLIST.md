# Strategy Acceptance Checklist

每次你修改策略前后，都跑一次：

`tileworld.StrategyAcceptanceChecklist`

它会自动执行两部分：

1. **阶段0硬约束检查**
2. **Config One / Config Two 的10种子统计验收**

---

## A. 阶段0硬约束（必须通过）

- 感知半径：`defaultSensorRange == 3`（Chebyshev邻域）
- 初始燃料：`defaultFuelLevel == 500`
- 总步数：`endTime == 5000`
- 核心动作：`TWAction` 包含 `MOVE/PICKUP/PUTDOWN/REFUEL`

并会提示你关注以下代码级约束（不应破坏）：

- `TWAgent.moveDir` 中有效移动会 `fuel--`
- `TWEnvironment.createAgent` 中时序：先 `sense+communicate`（order=2），后 `think+act`（order=3）
- `TWEnvironment.step` 每步清空消息 `messages.clear()`
- `TWObjectCreator` 使用 `Parameters.lifeTime` 决定对象寿命

---

## B. 统计验收标准（建议）

### Config One（Phase1 vs Random）
- 看 `MEAN / VAR(pop) / STD / CV / MIN / MAX`
- 期望：`Phase1 mean > Random mean`

### Config Two（Phase2 vs Phase1 vs Random）
- 看 `MEAN / VAR(pop) / STD / CV / MIN / MAX`
- 关注：
  - `Phase2 mean` 是否高于 `Phase1 mean`
  - `Phase2 CV` 是否过高（例如 > 0.5）

若 `Phase2` 波动很大（CV高）或均值未提升，优先考虑进入阶段3/4（参数自适应、通信协作、目标冲突规避）。

---

## C. 结果记录模板（建议）

每次改动记录：

- Commit/改动说明：
- Config One：mean/std/cv/min/max
- Config Two：mean/std/cv/min/max
- 是否通过阶段0硬约束：
- 结论：保留/回滚/继续调参

