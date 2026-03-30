# Tileworld

基于 MASON 的 Tileworld 仿真：阶段 1/2 单双智能体策略、阶段 4 六智能体通信与协作、无头基准测试与验收清单。

## 环境依赖

| 依赖 | 说明 |
|------|------|
| **JDK** | 建议 11+（与 Eclipse/VS Code Java 扩展兼容即可） |
| **MASON** | 需将 `MASON_14.jar`（或你使用的版本）加入工程类路径 |
| **Java3D** | GUI 需要：`j3dcore.jar`、`j3dutils.jar`、`vecmath.jar`；运行时常用 `-Djava.library.path=...` 指向 Java3D 的 **native** `bin` 目录（见 `.vscode/launch.json` 示例） |

本项目 `.classpath`（Eclipse）中为**本机绝对路径**，克隆到其他机器后请在 IDE 中改为你的 `MASON` 与 Java3D 路径。

## 导入工程

- **Eclipse**：`File → Import → Existing Projects into Workspace`，选择本目录（含 `.project`）。
- **VS Code**：安装 **Extension Pack for Java**，打开本文件夹；在 **Java Projects** 中把 `MASON_14.jar` 等加入 **Referenced Libraries**。

输出目录默认 `bin/`（Eclipse）或 IDE 自动生成；已加入 `.gitignore`。

---

## 运行（可视化 GUI）

在 IDE 中运行对应类的 **`main`**（包名 `tileworld.*`）。

| 入口类 | 作用 |
|--------|------|
| `tileworld.TWGUI` | 默认 **Config One**（50×50）、**2× Phase1Agent**。直接运行即可打开 MASON 控制台与地图 |
| `tileworld.ConfigTwoLauncher` | 先 `applyConfigTwo()`，再 **2× Phase2Agent**，然后启动 `TWGUI` |
| `tileworld.ConfigPhase4Launcher` | **6× Phase4Agent** + GUI。默认 **Config Two** + 通信开启 |

**`ConfigPhase4Launcher` 程序参数（可选）**

- `config1`：使用 Config One 地图（50×50，稀疏，长寿命）
- `nocomm`：关闭通信（用于对照）

示例：`config1`、`config1 nocomm`。

---

## 通过 `Parameters` 切换行为（高级）

在**创建** `TWEnvironment` **之前**设置（仅 GUI 时需在 `TWGUI` 构造环境前改代码，或使用上述 Launcher）。

| 字段 / 方法 | 含义 |
|-------------|------|
| `Parameters.applyConfigOne()` / `applyConfigTwo()` | 地图尺寸、生成率、`lifeTime` |
| `usePhase2Agent` | `true` → `Phase2Agent`（与 `usePhase4Agent` 互斥） |
| `usePhase4Agent` | `true` → `Phase4Agent`，数量见 `phase4AgentCount`（默认 6） |
| `useRandomWalkBaseline` | `true` → 随机游走基线 |
| `phase4EnableCommunication` | Phase4 是否广播意图/加油站（基准里会切换） |

仿真步数：`Parameters.endTime`（默认 5000）。

---

## 测试与基准（无头，无 GUI）

在 IDE 中运行下列类的 **`main`**，或在命令行指定 classpath 后执行 `java tileworld.<类名>`。

| 类 | 说明 |
|----|------|
| `tileworld.ConfigOneBenchmark` | Config One：10 个固定种子，对比 **Phase1** 与 **随机游走** 的均值/方差 |
| `tileworld.ConfigTwoBenchmark` | Config Two：对比 **Phase2**、**Phase1**、**随机游走** |
| `tileworld.Phase4CommunicationBenchmark` | Config One 与 Config Two 各跑一轮：**Phase4 无通信 vs 有通信**（6 智能体） |
| `tileworld.StrategyAcceptanceChecklist` | 策略验收相关输出（若存在） |
| `tileworld.TileworldMain` | 无头多轮仿真示例（可按需改种子与步数） |

基准类通常会设置 `Parameters.quietSimulation = true` 以减少控制台噪音。

---

## 命令行编译与运行（示例）

在仓库根目录（本 `Tileworld` 文件夹）下，将 `lib/MASON_14.jar` 等替换为你本机路径：

```bash
# 编译（示例 classpath，请按实际 jar 调整）
javac -encoding UTF-8 -d out -cp "path/to/MASON_14.jar;path/to/j3dcore.jar;..." -sourcepath src src/tileworld/**/*.java

# 无头基准示例
java -cp "out;path/to/MASON_14.jar;..." tileworld.Phase4CommunicationBenchmark
```

Windows 下 classpath 分隔符为 `;`，Linux/macOS 为 `:`。

---

## 其他文档

- 仓库内 **`ACCEPTANCE_CHECKLIST.md`**：验收清单说明（若与课程要求对应，请一并阅读）。

---
