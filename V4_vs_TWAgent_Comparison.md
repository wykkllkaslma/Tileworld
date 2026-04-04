# V4 MyTeamAgent vs 原始 TWAgent 技术对比文档

本文档逐模块对比 V4 (`MyTeamAgent`) 相对于框架原始 `TWAgent` 的所有新增功能。

---

## 模块总览

| 模块 | 原始 TWAgent | V4 MyTeamAgent | 新增/改动 |
|------|-------------|---------------|----------|
| 感知 (Sense) | 7×7 传感器 | 相同 + 燃料站主动检测 | ✅ 新增 |
| 通信 (Communicate) | 空消息 | 广播 5 字段消息 | ✅ 完全重写 |
| 决策 (Think) | 抽象方法 | BDI + Q-value 统一评估 | ✅ 完全实现 |
| 行动 (Act) | 仅移动 | 4 种动作完整处理 | ✅ 完全实现 |
| 记忆 (Memory) | 基础时间戳认知图 | 新增全记忆扫描 + 自适应阈值 | ✅ 新增方法 |
| 导航 (Navigation) | 无 | A* 寻路 + 直接移动降级 | ✅ 全新模块 |
| 协调 (Coordination) | 无 | 区域分工 + 任务认领 | ✅ 全新模块 |
| 燃料管理 (Fuel) | 无策略 | 共享发现 + 安全裕量 | ✅ 全新模块 |
| 探索 (Exploration) | 无 | 弓形扫描 + 初始化扫描 | ✅ 全新模块 |

---

## 1. 感知模块 (Sense)

### 原始 TWAgent
```java
public void sense() {
    sensor.sense();  // TWAgentSensor 扫描 7×7 邻域，写入 memory
}
```
- 仅调用 `TWAgentSensor.sense()` 将感知范围内对象存入 `TWAgentWorkingMemory`
- 不做额外处理

### V4 MyTeamAgent 新增
- **燃料站主动检测**：在 `communicate()` 阶段（sense 之后立即执行），遍历传感器范围检测 `TWFuelStation`
```java
for (int dx = -r; dx <= r; dx++) {
    for (int dy = -r; dy <= r; dy++) {
        if (obj instanceof TWFuelStation) {
            sharedFuelStationPos = new Int2D(nx, ny);
        }
    }
}
```
- 原始框架无此功能——`inFuelStation()` 需物理到达同一格才触发

---

## 2. 通信模块 (Communicate)

### 原始 TWAgent
```java
public void communicate() {
    Message message = new Message("","","");
    this.getEnvironment().receiveMessage(message);
}
```
- 发送空消息，无任何信息交换

### V4 MyTeamAgent — 完全重写
- **消息类**：新增 `MyTeamMessage extends Message`，携带 5 个信息字段：

| 字段 | 内容 | 用途 |
|------|------|------|
| `senderPos` | 发送者当前坐标 | 用于任务认领距离比较 |
| `claimedTarget` | 当前认领的目标坐标 | 避免多智能体争抢同一目标 |
| `observedTile` | 传感器范围内最近瓦片 | 扩展全队感知范围 |
| `observedHole` | 传感器范围内最近洞 | 扩展全队感知范围 |
| `fuelStationStr` | 已知燃料站坐标 | 全队共享，避免重复探索 |

- **全队燃料站共享**：使用 `volatile static Int2D sharedFuelStationPos`，首个发现者广播给全队

---

## 3. 决策模块 (Think)

### 原始 TWAgent
```java
abstract protected TWThought think();
```
- 纯抽象方法，无任何默认实现

### V4 MyTeamAgent — 完整 BDI 决策引擎

#### 决策优先级架构（从高到低）：

**Phase 0：机会性动作（零移动成本）**
```
IF 站在燃料站上 → REFUEL
IF 携带瓦片 AND 当前格为洞 → PUTDOWN（零成本得分）
IF 未满载 AND 当前格为瓦片 → PICKUP（零成本投资）
```

**Phase 1：燃料安全管理**
```
IF 燃料 ≤ 到燃料站距离 + FUEL_SAFETY → 前往燃料站
```

**Phase 2：初始化扫描 / 应急扫描**
```
IF 燃料站未知 AND (步数 ≤ INIT_SWEEP_STEPS OR 燃料 < 200)：
    可选：携带瓦片时偏离扫描路线配送至近处洞
    前往扫描目标
```

**Phase 3：统一动作–价值评估（Q-Learning 启发）**

核心创新：将所有候选动作放在同一效用量表上竞争：

$$Q_{\text{deliver}}(h) = \frac{1 + |\text{carried}| \times \text{CARRY\_BONUS}}{d(agent, h) + zp + \text{DELIVER\_OFFSET}}$$

$$Q_{\text{pickup}}(t) = \frac{1}{d(agent, t) + \text{HOLE\_WEIGHT} \cdot d(t, \text{近洞}) + zp + \text{PICKUP\_OFFSET}}$$

选择全局最大 Q 值对应的动作执行。

**Phase 4：探索（弓形扫描）**
```
无已知目标 → 继续弓形区域扫描
```

#### V4 新增的超参数（自适应计算）

| 超参数 | 默认公式 | Config1 值 | Config2 值 | 含义 |
|--------|---------|-----------|-----------|------|
| `MEM_THRESH` | `min(200, lifeTime × 2.5)` | 200 | 75 | 记忆有效窗口 |
| `FUEL_SAFETY` | 70 | 70 | 70 | 燃料安全裕量 |
| `ZONE_PENALTY` | `max(3, min(10, xDim/3/5+2))` | 5 | 7 | 区域外目标惩罚 |
| `DIST_CAP` | `min(xDim/3+8, lifeTime)` | 25 | 26 | 最大追逐距离 |
| `SWEEP_Y_STEP` | `sensorRange × 2` | 6 | 6 | 扫描行间距 |
| `INIT_SWEEP_STEPS` | `min(250, lifeTime × 7)` | 250 | 210 | 初始扫描步数 |
| `SWEEP_DELIVERY_RANGE` | 3 | 3 | 3 | 扫描中的配送偏离范围 |
| `Q_DELIVER_CARRY_BONUS` | 0.1 | 0.1 | 0.1 | 配送Q值中携带量加成 |
| `Q_DELIVER_OFFSET` | 1.0 | 1.0 | 1.0 | 配送Q值分母偏移 |
| `Q_PICKUP_HOLE_WEIGHT` | 0.6 | 0.6 | 0.6 | 拾取Q值中瓦片到洞距离权重 |
| `Q_PICKUP_OFFSET` | 2.0 | 2.0 | 2.0 | 拾取Q值分母偏移 |
| `Q_CARRY_BONUS_MULT` | 1.3 | 1.3 | 1.3 | 已携带时近洞奖励倍率 |
| `Q_CARRY_BONUS_DIST` | 5 | 5 | 5 | 触发携带奖励的最大洞距离 |

---

## 4. 行动模块 (Act)

### 原始 TWAgent
```java
abstract protected void act(TWThought thought);
```
- 纯抽象方法，`SimpleTWAgent` 示例仅实现了 `move()`

### V4 MyTeamAgent — 4 种完整动作处理

| 动作 | 处理逻辑 | 失败回退 |
|------|---------|---------|
| `PICKUP` | 验证当前格有 TWTile → `pickUpTile()` | 清除路径，重新规划 |
| `PUTDOWN` | 验证当前格有 TWHole → `putTileInHole()` | 清除路径，重新规划 |
| `REFUEL` | 调用 `refuel()` | — |
| `MOVE` | 沿 `navigateTo()` 返回的方向移动 | `CellBlockedException` → 清除路径 |

---

## 5. 记忆模块 (TWAgentWorkingMemory)

### 原始框架提供
| 功能 | 方法 | V4 是否使用 |
|------|------|-----------|
| 时间戳认知图 | `objects[x][y] = TWAgentPercept` | ✅ 核心数据结构 |
| 传感器更新 | `updateMemory(Bag, IntBag, ...)` | ✅ 每步由 sensor 调用 |
| 传感器内最近对象 | `getClosestObjectInSensorRange(Class)` | ✅ 在 communicate() 中使用 |
| 障碍检测 | `isCellBlocked(x, y)` | ✅ A* 寻路使用 |
| 移除记忆 | `removeAgentPercept(x, y)` | ✅ pickup/putdown 后清除 |
| GUI 记忆网格 | `memoryGrid` + `getMemoryGrid()` | ✅ GUI 可视化 |

### V4 新增
| 功能 | 方法 | 说明 |
|------|------|------|
| **全记忆瓦片扫描** | `getKnownTilePositions(threshold)` | 遍历整个 `objects[][]`，返回阈值内所有记忆瓦片坐标；替代原始的 `getNearbyTile()` 单个返回 |
| **全记忆洞扫描** | `getKnownHolePositions(threshold)` | 同上，用于洞；支持 Q-value 全局最优评估 |

**设计差异**：原始 `getNearbyObject()` 基于螺旋搜索（从近到远），仅返回第一个满足时间阈值的对象。V4 的全扫描返回所有候选，支持全局最优选择。

### V4 删除的未使用方法（原框架遗留）
- `getNearbyTile()` / `getNearbyHole()` / `getNearbyObject()` — 螺旋搜索单个最近对象
- `decayMemory()` — 记忆衰减（原为空实现）
- `updateMemory(TWEntity[][], int, int)` — 二维数组版更新（未使用）
- `removeObject(TWEntity)` — 按实体移除（V4 直接用坐标版）
- `getMemorySize()` — 记忆大小计数
- `spiral` 静态字段 — 螺旋搜索路径
- `MAX_TIME` / `MEM_DECAY` 常量 — 未使用的衰减参数

---

## 6. 导航模块（全新）

### 原始 TWAgent
- 无任何导航功能，仅提供 `move(TWDirection)` 原子移动

### V4 MyTeamAgent
| 功能 | 实现 | 说明 |
|------|------|------|
| **A* 寻路** | `navigateTo(tx, ty)` → `AstarPathGenerator.findPath()` | 考虑障碍物的最优路径 |
| **路径缓存** | `currentPath` + `currentGoal` | 目标不变时复用已有路径 |
| **降级直接移动** | `directMove(tx, ty)` | A* 失败时按曼哈顿最大分量方向移动 |
| **曼哈顿距离** | `manhattanDist(x1,y1,x2,y2)` | 所有距离计算的基础 |

---

## 7. 协调模块（全新）

### 原始 TWAgent
- 无协调机制，智能体完全独立

### V4 MyTeamAgent
| 功能 | 实现 | 说明 |
|------|------|------|
| **静态区域分工** | 构造函数计算 `zoneMinX/MaxX/MinY/MaxY` | 6 智能体 → 2行×3列子区域，避免重复覆盖 |
| **区域外惩罚** | `isInZone(x,y)` + `ZONE_PENALTY` | 区域外目标 Q 值降低，鼓励在区域内操作 |
| **任务认领去重** | `isClaimedByCloserTeammate(tx,ty)` | 队友已认领且更近（距离差≤3格）→ 跳过该目标 |
| **消息处理** | `processMessages()` | 解析全队消息，构建 `teamClaimedTargets`、`teamSharedTiles`、`teamSharedHoles` |
| **全队燃料站共享** | `volatile static sharedFuelStationPos` | 单个智能体发现，全队立即可用 |
| **静态状态重置** | `resetSharedState()` | 多次实验间清除静态变量 |

---

## 8. 燃料管理模块（全新）

### 原始 TWAgent
- `refuel()` 方法存在但无调用策略
- 无燃料不足预警

### V4 MyTeamAgent
| 功能 | 实现 | 说明 |
|------|------|------|
| **安全裕量计算** | `fuelLevel ≤ distToFuel + FUEL_SAFETY` | 确保有足够燃料到达燃料站 |
| **溢出安全** | `long distToFuel`，默认 `Long.MAX_VALUE / 2` | 避免 V1 的 int 溢出 bug |
| **应急扫描** | 燃料站未知 + 燃料 < 200 → 进入纯区域扫描 | 加大发现燃料站的概率 |
| **自动加油** | 踩在燃料站 + 燃料不满 → `REFUEL` | 最高优先级，不浪费加油机会 |

---

## 9. 超参数网格搜索结果

### 搜索空间

对 4 个关键超参数进行全组合搜索（144 组合 × 5 次迭代取均值）：

| 参数 | 含义 | 搜索范围 | 默认值 |
|------|------|---------|-------|
| `memThreshMult` | 记忆过期阈值倍率 | [2.0, 2.5, 3.0, 3.5] | 2.5 |
| `zonePenalty` | 区域协作惩罚 | [3, 5, 7] | 自适应 |
| `distCapOffset` | 距离截断偏移 | [6, 8, 10] | 8 |
| `pickupHoleWeight` | pickup中hole距离权重 | [0.3, 0.5, 0.6, 0.8] | 0.6 |

### Config1 结果（50×50, tileMean=0.2, lifeTime=100）

| 排名 | 分数 | memThreshMult | zonePenalty | distCapOffset | pickupHoleWeight |
|------|------|--------------|------------|--------------|-----------------|
| 1 | **582.4** | 2.0 | 3 | 6 | 0.3 |
| 2 | 581.8 | 3.0 | 3 | 6 | 0.3 |
| 3 | 581.6 | 3.0 | 7 | 6 | 0.6 |
| 4 | 580.0 | 3.0 | 5 | 8 | 0.6 |
| 5 | 579.4 | 3.5 | 3 | 6 | 0.6 |

### Config2 结果（80×80, tileMean=2.0, lifeTime=30）

| 排名 | 分数 | memThreshMult | zonePenalty | distCapOffset | pickupHoleWeight |
|------|------|--------------|------------|--------------|-----------------|
| 1 | **952.2** | 3.0 | 5 | 10 | 0.3 |
| 2 | 934.6 | 2.0 | 5 | 6 | 0.3 |
| 3 | 934.4 | 3.5 | 3 | 8 | 0.3 |
| 4 | 929.8 | 2.5 | 5 | 10 | 0.3 |
| 5 | 929.0 | 2.0 / 3.5 | 5 | 8 | 0.3 |

### 20 次迭代验证结果

| 配置 | 基线分数 | 优化后分数 | 提升幅度 | 最优参数 |
|------|---------|-----------|---------|---------|
| Config1 (50×50) | 560.6 | **567.1** | **+1.2%** | mem=2, zp=3, dc=6, phw=0.3 |
| Config2 (80×80) | 889.8 | **907.2** | **+2.0%** | mem=3, zp=5, dc=10, phw=0.3 |

### 关键发现

1. **`pickupHoleWeight=0.3` 是最关键参数**：两个配置的 Top 5 中几乎全部使用 0.3，说明降低 hole 距离对 pickup 评估的权重能提升拾取效率
2. **`distCapOffset=6` 在小地图最优，`=10` 在大地图最优**：小地图中更紧凑的距离截断有利，大地图需要更宽松的范围
3. **`zonePenalty` 需适配地图规模**：50×50 适合 zp=3（弱协作惩罚），80×80 适合 zp=5（更强的区域分工）
4. **`memThreshMult` 影响相对较小**：各值在 Top 5 均有出现，但低值（2.0）在小地图略优，高值（3.0）在大地图略优

---

## 9. 探索模块（全新）

### 原始 TWAgent
- 无探索策略，`SimpleTWAgent` 示例使用随机移动

### V4 MyTeamAgent
| 功能 | 实现 | 说明 |
|------|------|------|
| **弓形扫描 (Boustrophedon)** | `sweepX/Y/DirX` + `advanceSweep()` | 在分配区域内进行系统性 S 形覆盖 |
| **零盲区扫描** | `SWEEP_Y_STEP = sensorRange × 2 = 6` | 相邻扫描行的传感器覆盖边界完全相接 |
| **靠近才推进** | `dist(agent, sweepTarget) ≤ 1` 时才推进目标 | 修复 V1-V3 "追赶目标"缺陷 |
| **初始化扫描阶段** | 前 `INIT_SWEEP_STEPS` 步优先扫描 | 保证发现燃料站，消除灾难性失败 |
| **扫描中配送** | `SWEEP_DELIVERY_RANGE` 内有洞 → 偏离配送 | 扫描阶段也能零/低成本得分 |

---

## 10. 知识库构建（Think 内部，全新）

### 原始 TWAgent
- 无知识库概念

### V4 MyTeamAgent
每步 `think()` 开始时构建候选知识库：
```
allTiles = memory.getKnownTilePositions(MEM_THRESH)  // 本地记忆
         + teamSharedTiles                             // 队友广播
         → 去重 + isStillTile() 验证

allHoles = memory.getKnownHolePositions(MEM_THRESH)
         + teamSharedHoles
         → 去重 + isStillHole() 验证
```

**双重验证**：
1. 时间阈值过滤（`MEM_THRESH`）— 排除过期记忆
2. 实时验证（`getObjectGrid().get(x,y)`）— 确认对象仍存在

---

## 11. 文件变更总结

| 文件 | 类型 | 改动说明 |
|------|------|---------|
| `MyTeamAgent.java` | **新增** | V4 完整智能体实现（~400 行） |
| `MyTeamMessage.java` | **新增** | 广播消息结构体（~40 行） |
| `TWAgentWorkingMemory.java` | **修改** | 新增 `getKnownTilePositions()` / `getKnownHolePositions()`；删除 V4 未使用的遗留方法 |
| `TWEnvironment.java` | **修改** | 创建 6 个 `MyTeamAgent`；调用 `resetSharedState()` |
| `Parameters.java` | **修改** | 环境参数支持系统属性覆盖（用于网格搜索） |
| `TileworldMain.java` | **修改** | 支持参数化迭代次数和种子 |

---

## 12. V4 超参数完整清单

以下为 V4 所有可调超参数及其系统属性名：

| 超参数 | 系统属性 | 默认值 | 自适应公式 |
|--------|---------|--------|-----------|
| 记忆阈值倍率 | `tw.memThreshMult` | 2.5 | `MEM_THRESH = min(200, lifeTime × mult)` |
| 燃料安全裕量 | `tw.fuelSafety` | 70 | — |
| 区域外惩罚 | `tw.zonePenalty` | 自适应 | `max(3, min(10, xDim/3/5+2))` |
| 距离上限偏移 | `tw.distCapOffset` | 8 | `DIST_CAP = min(xDim/3+offset, lifeTime)` |
| 扫描配送范围 | `tw.sweepDeliveryRange` | 3 | — |
| 配送携带加成 | `tw.deliverCarryBonus` | 0.1 | Q_deliver 公式中 |
| 配送偏移量 | `tw.deliverOffset` | 1.0 | Q_deliver 分母 |
| 拾取洞距离权重 | `tw.pickupHoleWeight` | 0.6 | Q_pickup 公式中 |
| 拾取回退洞距离 | `tw.pickupFallbackHoleDist` | 8.0 | 无已知洞时的估计距离 |
| 拾取偏移量 | `tw.pickupOffset` | 2.0 | Q_pickup 分母 |
| 携带近洞加成倍率 | `tw.carryBonusMult` | 1.3 | 已携带时近洞 Q 值乘子 |
| 携带近洞触发距离 | `tw.carryBonusDist` | 5 | 触发 carryBonusMult 的最大洞距离 |
