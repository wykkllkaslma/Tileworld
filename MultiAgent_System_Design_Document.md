# Multi-agent System 设计文档

## 1. 文档目的

本文档用于系统化说明当前 Tileworld 六智能体系统的设计方案，内容对齐课程 assignment 对汇报内容的要求，重点覆盖以下问题：

- 系统环境的 setting 是什么
- 我们的优化目标是什么
- 六个 agent 各自是怎么设计的
- 每个 agent 的职责、任务、边界与 scope 是什么
- 六个 agent 是如何协作的

本文档描述的是当前项目中的多智能体设计方案，不做与其他实现的对比。

---

## 2. 系统环境 Setting

### 2.1 问题环境

系统运行在 Tileworld 环境中。环境是一个二维网格世界，包含以下关键对象：

- `Agent`：可以在网格中移动的智能体
- `Tile`：可被 agent 拾取并携带
- `Hole`：需要用 tile 填补的目标位置
- `Obstacle`：不可穿越的障碍
- `Fuel Station`：用于补充 fuel 的站点

系统 reward 的获得方式是：agent 将 tile 运送到 hole 所在格，并完成填补动作后，hole 被填平，系统总 reward 增加。

### 2.2 Agent 行为约束

根据 assignment 规定，agent 的行为受到以下约束：

- 感知范围有限：Chebyshev distance `<= 3`
- 动作集合有限：`MOVE / PICKUP / PUTDOWN / REFUEL`
- 最多同时携带 `3` 块 tile
- 每次移动消耗 `1` 单位 fuel
- fuel 耗尽后将无法继续移动
- 每个时间步执行一次 `Sense -> Communicate -> Plan -> Act`

### 2.3 通信约束

所有 agent 属于同一队伍，因此消息是全队广播式的。  
通信没有距离限制，但每一步的消息只在当前 step 有效，因此消息必须简洁、明确、可直接支持当前规划。

### 2.4 评测配置

课程 competition 使用三类环境配置：

- `Config One`
  - 地图大小：`50 x 50`
  - 平均生成率：`mu = 0.2, sigma = 0.05`
  - lifetime：`100`

- `Config Two`
  - 地图大小：`80 x 80`
  - 平均生成率：`mu = 2, sigma = 0.5`
  - lifetime：`30`

- `Config Three`
  - 未知地图大小
  - 未知生成率
  - 未知 lifetime

固定参数：

- 总步数：`5000`
- 初始 fuel：`500`
- 每个配置会跑 `10` 个不同随机种子

### 2.5 实现边界

为了完全符合 assignment 约束，当前系统遵守以下边界：

- agent 继承自 `TWAgent`
- 主要通过 `communicate()`、`think()`、`act()` 完成设计
- 不修改 `tileworld.environment` 包
- reward 仍然只通过原框架的 `putTileInHole()` 增加

---

## 3. 系统优化目标

### 3.1 总体优化目标

当前系统的总体优化目标是：

**在 5000 个 time steps 内最大化 team total reward，同时保持六个 agent 之间的低冲突、高覆盖、高转化率协作。**

### 3.2 具体优化目标

为了实现总体目标，系统进一步拆解为以下子目标：

- 尽早发现 fuel station，避免 agent 因 fuel 耗尽失效
- 尽快建立对 tile 与 hole 的高质量共享认知
- 减少多个 agent 同时追逐同一目标造成的浪费
- 通过分区和角色化设计降低重复探索
- 在短寿命对象环境中提高目标发现到转化的速度
- 在未知配置下仍然保持较好的适应性和鲁棒性

### 3.3 设计原则

本系统遵循以下原则：

- **角色化**：六个 agent 不再完全同质，而是承担不同职责
- **通信最小但有效**：消息字段少而关键，直接服务于当步规划
- **局部负责 + 全局协同**：每个 agent 有自己的 scope，但仍服从全队收益最大化
- **确定性行为**：系统不依赖随机 fallback，而是使用职责驱动的确定性规划

---

## 4. 系统总体架构

### 4.1 统一控制流

所有 agent 共享同一基本决策流程：

1. `Sense`：感知周围局部对象并更新本地记忆
2. `Communicate`：广播关键共享信息
3. `Plan`：结合本地记忆和共享信息，计算当前最优动作
4. `Act`：执行移动、拾取、投放或加油

### 4.2 统一模块

六个 agent 虽然角色不同，但底层模块一致，主要包括：

- **Memory 模块**
  - 记录本地观察到的 tile / hole / obstacle
  - 支持提取当前阈值内的有效目标集合
  - 支持共享知识与本地知识的融合

- **Communication 模块**
  - 广播 `role`
  - 广播已知 `fuel`
  - 广播 `seenTile`
  - 广播 `seenHole`
  - 广播 `intent`

- **Planning 模块**
  - 优先处理零成本高收益动作
  - 进行 fuel 安全检查
  - 在需要时执行探索 sweep
  - 对 pickup / deliver 候选进行 Q-style 评分
  - 执行目标认领与冲突规避

- **Coordination 模块**
  - 使用 claim 机制避免争抢
  - 使用 zone / role 限制 agent 的默认活动范围
  - 使用 closer 做全图补位

### 4.3 当前实现文件

- 主要策略实现：`src/tileworld/agent/Phase4Agent.java`
- 记忆扩展：`src/tileworld/agent/TWAgentWorkingMemory.java`
- 六角色职责摘要：`AGENT_ROLE_SYSTEMS.md`

---

## 5. 六个 Agent 的设计方案

## 5.1 Agent 1: Fuel Scout

### 角色定位

Fuel Scout 是全队的燃料侦察与中轴信息保障 agent。

### 主要职责

- 尽快发现 fuel station
- 将 fuel station 坐标广播给全队
- 保持地图中心 corridor 的持续可见性
- 在中心带内处理近距离高价值任务

### 任务内容

- 早期优先执行中心十字巡航
- 一旦发现 fuel station，立即广播
- 后续留在中心 corridor，维持全队 fuel 信息稳定可用
- 仅对中心区域内的 tile / hole 机会目标做短距离处理

### 边界与 Scope

- 活动范围主要是地图中心 corridor
- 不负责大范围扫图
- 不承担东西两侧主力采集任务
- 其价值不在于个人拿最多分，而在于保证全队不会因 fuel 崩盘

### 对全队的贡献

- 降低低分 seed 的出现概率
- 为全部 agent 提供 fuel 安全基准
- 稳定整队行动半径

---

## 5.2 Agent 2: West Scout

### 角色定位

West Scout 是西北区域的主侦察 agent。

### 主要职责

- 扫描西北区域
- 快速发现新的 tile / hole
- 将西侧 sighting 广播给 collector

### 任务内容

- 执行固定 sweep 路线
- 优先保证覆盖率与信息新鲜度
- 对特别近的机会目标可以顺手处理
- 默认不长距离离开西北区域

### 边界与 Scope

- 主要 scope 为地图左上象限
- 不承担主力投递
- 不负责东侧资源

### 对全队的贡献

- 提高西侧 collector 的目标发现效率
- 减少西侧盲搜成本

---

## 5.3 Agent 3: East Scout

### 角色定位

East Scout 是东北区域的主侦察 agent。

### 主要职责

- 扫描东北区域
- 快速发现新的 tile / hole
- 将东侧 sighting 广播给 collector

### 任务内容

- 执行东侧固定 sweep 路线
- 优先信息获取，而非长距离抢目标
- 对局部极近目标可 opportunistically 处理

### 边界与 Scope

- 主要 scope 为地图右上象限
- 不承担主力投递
- 不负责西侧资源

### 对全队的贡献

- 为东侧 collector 提供持续目标流
- 降低东侧探索成本

---

## 5.4 Agent 4: West Collector

### 角色定位

West Collector 是西南区域的主力 reward 转化 agent。

### 主要职责

- 在西南区域拾取 tile
- 寻找西侧 hole 并完成投递
- 将局部资源高效转化为 reward

### 任务内容

- 当未满载时优先 pickup
- 当已携带 tile 时优先 deliver
- 结合 scout 提供的信息选择高价值任务
- 在本区域内部尽快完成 pickup -> deliver 闭环

### 边界与 Scope

- 默认 scope 为西南区域
- 不主动越界去承担东侧资源
- 不负责全图扫尾

### 对全队的贡献

- 是西侧 reward 的主要生产者
- 将 west scout 的信息直接转化成分数

---

## 5.5 Agent 5: East Collector

### 角色定位

East Collector 是东南区域的主力 reward 转化 agent。

### 主要职责

- 在东南区域拾取 tile
- 寻找东侧 hole 并完成投递
- 在东侧形成稳定的资源闭环

### 任务内容

- 与 West Collector 对称
- 依赖 east scout 的 sighting 信息
- 在自己负责区域内追求最高的 reward conversion rate

### 边界与 Scope

- 默认 scope 为东南区域
- 不负责中心 fuel 控制
- 不负责全图补位

### 对全队的贡献

- 是东侧 reward 的主要生产者
- 保证东侧不会因为缺少执行者而空转

---

## 5.6 Agent 6: Closer

### 角色定位

Closer 是全图范围的收尾与补位 agent。

### 主要职责

- 优先完成 hole closing
- 接管未完成或边界模糊的任务
- 吃掉各区域遗漏的高价值机会

### 任务内容

- 当携带 tile 时优先找最优 hole
- 当未携带 tile 时寻找全图最优可转化 tile
- 在其他 agent 的职责边界之外承担补位作用

### 边界与 Scope

- Scope 为全图
- 不是主探索者
- 不是 fuel 中枢
- 其职责是补空、收尾、避免漏分

### 对全队的贡献

- 缓冲纯分区策略的刚性缺陷
- 避免边界区域资源无人处理
- 提高系统总体 reward 的完整收割率

---

## 6. 协作机制

### 6.1 通信字段

每个 agent 每一步广播以下信息：

- `role`：当前角色身份
- `fuel`：已知 fuel station 坐标
- `seenTile`：当前看到的最有价值 tile
- `seenHole`：当前看到的最有价值 hole
- `intent`：当前正在追逐的目标

这些消息的目的不是“聊天”，而是直接服务于当步规划与冲突规避。

### 6.2 共享知识

每个 agent 的决策都基于两类信息：

- 本地记忆中的已知 tile / hole
- 队友通过广播共享的最新 sighting

系统会将两类信息合并为当前候选知识库，并在规划时去重、过滤、评分。

### 6.3 目标认领机制

为避免多个 agent 抢同一目标，系统使用 claim 机制：

- 若两个 agent 指向同一目标
- 优先让 Manhattan distance 更小的 agent 保留该目标
- 若距离相同，则 agent id 更小者获胜

这样能够有效降低重复追逐和路径浪费。

### 6.4 分区协作

系统采用“局部分工 + 全局补位”的组织方式：

- Fuel Scout 控制中心
- West / East Scout 负责上半区信息流
- West / East Collector 负责下半区 reward 转化
- Closer 负责全图补位

这种结构保证：

- 既有覆盖
- 又有执行
- 还有收尾

### 6.5 探索与执行的切换

系统不是永远只探索，也不是永远只执行，而是动态切换：

- 当 fuel 未知或较危险时，更偏探索与侦察
- 当知识库中出现高价值 tile-hole 配对时，转向执行
- 当区域内没有合适任务时，进入 sweep 保持覆盖

### 6.6 Fuel 协作

fuel 机制是多智能体系统稳定性的关键：

- Fuel Scout 负责最早发现 fuel
- 一旦 fuel 被发现，位置立即广播给全队
- 所有 agent 在 fuel 不安全时优先回补
- 这样避免局部高分、整体崩盘的问题

---

## 7. 为什么这套系统适合当前 Assignment

这套系统满足 assignment 的核心要求：

- 有明确的多 agent 分工
- 有可解释的通信与协作机制
- 有基于记忆、通信、规划的完整 agent 设计
- 不依赖修改 environment 包
- 可以清楚说明每个 agent 的职责和 scope

此外，这套设计同时兼顾了：

- `Config One` 下的长寿命、稀疏对象
- `Config Two` 下的高密度、短寿命对象
- `Config Three` 下的未知环境鲁棒性需求

---

## 8. 总结

当前系统并不是六个完全相同的 agent，而是一个共享底层架构、但职责明确分化的六智能体系统。

它的核心思想可以概括为：

- 用 Fuel Scout 保证系统生存能力
- 用两个 Scout 保证信息覆盖
- 用两个 Collector 保证 reward 转化
- 用一个 Closer 保证全图收尾

最终形成的是一个：

**“中心保障 + 双侧侦察 + 双侧生产 + 全图收尾”**

的 Multi-agent System。
