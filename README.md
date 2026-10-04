# 玻璃深加工厂 · 原片与工序流水管理

以「**原片 → 切割 → 磨边 → 钢化 → 镀膜/中空/夹胶 → 检验 → 成品架**」为主线、
并以「**判返修 ↺ 隔离 → 处置 → 复检 → 放行**」为质量闭环的玻璃深加工生产管理系统。

## 技术栈

- 后端：Spring Boot 3.3.0 + Java 17 + Spring Data JPA(Hibernate 6.5) + MySQL 8 + Redis
- 前端：Vue 3 + Element Plus + Vite（主色 `#1a237e`，玻璃拟态）
- 端口：前端 `8295`、后端 `8395`、MySQL `3595`、Redis `6595`

## 启动

```bash
./start.sh            # 镜像已存在时直接起
./start.sh --build    # 首次或改过代码后必须带 --build
./stop.sh             # 停止
./clean.sh            # 收摊（down -v）
```

## 六个模块

| # | 模块 | 主要页面 / 交互 |
|---|------|------------------|
| 1 | 原片与库位 | 立体货架图（区 → 架 → 层），玻璃批次块按种类配色，点块右侧滑出详情；规格串反解 |
| 2 | 切割与优化 | 同一原片批次/出片清单/刀缝规则下的**双策略排样**（利用率优先 / 整块余料优先），两份 SVG 布局图 + 利用率 + 余料矩形清单 + 未排入项及原因；输入变化旧结果立即标过期，乱序响应自动丢弃 |
| 3 | 磨边与钢化 | 钢化炉温控隧道（加热 → 淬冷 → 冷却），玻璃片逐段前进，参数滑块越界变红，破碎率环形图 |
| 4 | 深加工工序 | 玻璃结构剖面（镀膜膜层 / 中空气体腔 / 夹胶 PVB），分类参数记录与投产登记 |
| 5 | 检验与成品架 | 玻璃板缺陷分布打点（点大小按尺寸、颜色按类型）+ 类型占比条 + 成品架发货（含质量状态列与拦截原因） |
| 6 | 返修闭环 | 返修批次工作区：状态待办（隔离/处理中/待复检/已放行）、批次时间线、登记处置、提交复检、复检判定 |

## 返修质量闭环（检验 / 深加工 / 成品架共享同一份批次质量状态）

- **判返修即隔离**：任一工序检验判 `REWORK`，同事务形成 / 并入 `rework_batch` 隔离批次，
  保留来源工序、涉及片数、缺陷汇总与处置进度；检验记录回写 `rework_id` 可追溯。
- **历史归并（可解释取舍）**：启动时把历史判返修记录归并建批（幂等，仅空表执行）。
  同一张检验单上同时出现的批次号与订单号视为同一实物的两个标识（并查集桥接）；
  同工序下任一标识落进同一连通分量才归并 —— 只有订单号的记录不会被并进只有批次号的批次，
  无标识记录各自单独成批：既不误并不同实物，也不让旧记录整体失联。归并说明写入批次与时间线。
- **受控流转**：`ISOLATED 隔离 → PROCESSING 处理中 → PENDING_RECHECK 待复检 → RELEASED 已放行`；
  复检再判返修回到隔离；处理中 / 待复检期间新增返修判定也回到隔离；已放行批次再判返修另起新闭环。
- **并发只生效一个**：所有流转走行级悲观锁（`SELECT ... FOR UPDATE`）+ 前置状态校验 + 乐观锁版本列。
  两名检验员同时提交相反复检结论时，先提交者生效，后到者收到 `409` 冲突说明
  （指明已生效结论与当前状态），冲突尝试本身也写入时间线，全过程可审计。
- **成品架闸门**：上架与发运都读取同一份状态 —— 架上批次号 / 订单号命中未闭环返修批次
  （隔离 / 处理中 / 待复检）即拦截并给出原因，拦截事件写入批次时间线；
  只有已放行（或无返修）批次可继续。已发运数据只读，不回填、不倒改。

## 切割：双策略排样

一次试算在**同一原片批次、同一份出片清单、同一刀缝（锯路）与旋转规则**下同时给出两份布局：

- **利用率优先（USAGE）**：多组确定性启发式（最大空闲矩形 Guillotine 切分 + 多排序/选位/随机种子竞争）以排齐全部出片、抬升本片利用率为目标。
- **整块余料优先（REMNANT）**：枚举右/顶条带宽度，在能排齐的最窄条带内完成排样，显式保住一整块可二次套裁余料（达门槛：短边 ≥300mm 且面积 ≥0.30㎡）。

每份结果都交代：布局矩形、利用率、出片/余料/刀缝碎料面积、余料矩形（位置、规格、是否整块、不可复用原因）、未排入项（规格、片数、具体原因码）。
判定基于**真实几何**（出片占位按刀缝在右/下膨胀、原片不贴边吃刀缝），不是总面积：旋转后仍放不下（`NO_ROTATION_FIT`）、禁旋但旋转可放（`ROTATION_DISABLED`）、面积够但几何放不下（`GEOMETRY_FULL`）、搜索时限内未找到落位（`SEARCH_TIMEOUT`）都计入未排入并给可调整约束，绝不为了凑利用率做伪成功。

**推荐口径（固定阈值带 + 综合适配，边界在界面可展开查看）**：

1. 任一策略排不齐必需出片时，只推荐能排齐的那份；两份都排不齐 → 中立不出推荐；
2. 利用率差 ≤3 个百分点且余料方案保住 ≥0.50㎡ 整块 → 推荐余料方案（窄差带，现场可理解）；
3. 利用率差 ≥8 个百分点 → 推荐利用率方案（余料补不回本片损耗）；
4. 3~8 个百分点的中间带看适配性：余料方案需多保 ≥0.80㎡ 整块且短边 ≥400mm 才选它；
5. 利用率差 <0.5 个百分点且整块余料差 <0.10㎡（等价带）→ 中立，现场任选。

**版本归属**：请求带 `clientSeq`、响应带服务端 `calculationId`；原片规格、出片数量或旋转选项变化时页面先把旧结果标为过期（置灰 + 提示条），较早发起但较晚返回的结果按序号丢弃，不占据当前版本。尺寸非法 / 必需出片放不下 / 搜索超时时输入保留、错误可就地修正，不给出伪推荐。

旧的单策略接口 `POST /api/cutting/preview` 与切割单台账继续可用；新接口为 `POST /api/cutting/preview/dual`。
既有库升级执行 `mysql/migration_dual_strategy.sql`。

## 后端数据访问标识：JPA `@Access` 混合访问

实体整体为字段访问（`@Access(AccessType.FIELD)`），下列派生 / 受控列改为属性访问（`@Access(AccessType.PROPERTY)` 标在 getter 上）：

| 实体 | 派生列 | 规则 |
|------|--------|------|
| `GlassStock` | `spec_text` | 「6mm 2440×1830」拼接串；getter 拼出、setter 反解析回厚度 / 长 / 宽 |
| `CuttingOrder` | `utilization_rate` | 出片面积合计 / 原片面积，四舍五入两位；setter 校验 0~100%，越界抛 `BizException` |
| `CuttingPiece` | `area_sqm` | 长 × 宽 × 数量 / 1,000,000 |
| `TemperingBatch` | `temp_range` | 「660-700」温控区间串；getter 拼合、setter 拆成 tempLow / tempHigh |
| `FinishedRack` | `area_sqm` | 长 × 宽 × 片数 / 1,000,000；setter 反校验与规格片数是否吻合 |

接口返回里可以直接看到这些派生 / 解析后的值，例如 `GET /api/stock/list` 的 `specText`、
`GET /api/cutting/list` 的 `utilizationRate`、`GET /api/inspection/racks` 的 `areaSqm`。

## 手写 JPQL（`@Query`）

- `GlassStockRepository#statByGlassType` —— 按玻璃种类统计批次、片数与面积
- `InspectionRecordRepository#statVerdictByProcess` —— 按工序统计合格 / 返修 / 报废
- `CuttingOrderRepository#remnantRank` —— 原片余料排行
- `EdgeGrindingRepository#statByEdgeType`、`TemperingBatchRepository#statByFurnace`、
  `DeepProcessRepository#statByProcessType`、`InspectionRecordRepository#statByDefectType`、
  `FinishedRackRepository#statByShipStatus`
- `ReworkBatchRepository#findLockedById` —— 行级悲观锁读取返修批次（并发流转串行化）

## 业务规则（service 抛 `BizException`）

1. 切割出片面积合计不得超过原片面积 —— 超出报错并给出超出面积；双策略排样按含刀缝的真实几何判定，放不下的必需出片列入未排入并说明原因，不得因总面积足够判成功
2. 钢化炉加热温度必须在 `620~720℃`
3. 中空玻璃露点高于 `-40℃` 判不合格
4. 夹胶 PVB 厚度与玻璃厚度不匹配不允许投产（给出该厚度可用的 PVB 规格）
5. 报废 / 破碎片数不得超过出炉片数
6. 原片库位、成品架承重超过上限不允许再上架 —— 报错并给出剩余承重
7. 磨边进给速度必须在边型允许区间内
8. 返修批次只能按 隔离 → 处理中 → 待复检 → 已放行 流转；状态不符抛 `ConflictException`（409）
9. 未闭环返修批次（隔离 / 处理中 / 待复检）关联的成品架不允许上架、发运 —— 报错并给出返修单号与状态
10. 钢化批次只能按 加热 → 淬冷 → 冷却 → 完成 单向推进；已完成批次再推进 / 调参 / 重复出炉一律拒绝（409），
    刷新页面不会回退炉段。出炉结果（出炉片数 / 破碎片数）只允许首次确认一次：重复提交相同片数原结果保留并说明，
    提交不同片数抛 409，合格片数与破碎率绝不被改写。阶段已完成但片数缺失的历史记录按「真实完成批次」对待，
    只允许补录一次出炉结果、补录后即锁定，不能重新推进生产。推进 / 出炉走行级悲观锁，并发点击只有一次生效。

## 主要接口

```
GET    /api/stock/list | /filter | /warehouse | /locations
POST   /api/stock/{id}/move   POST /api/stock/{id}/spec
GET    /api/cutting/list | /{id} | /remnant-rank    POST /api/cutting | /preview | /preview/dual
GET    /api/process/grinding | /grinding/stat | /tempering | /tempering/{id} | /furnace-stat
POST   /api/process/tempering/{id}/advance | /discharge
GET    /api/deep/list | /list/{type} | /stat        POST /api/deep
GET    /api/inspection/list | /defect-map | /defect-stat | /racks | /rack-stat
POST   /api/inspection/racks/{id}/load | /ship
GET    /api/rework/list?status= | /summary | /{id}
POST   /api/rework/{id}/dispose | /submit-recheck | /recheck
GET    /api/stats/overview | /glass-type | /verdict | /remnant | /edge | /furnace | /deep | /defect | /rack
```

## 目录

```
backend/src/main/java/com/glass/works/   domain / dto / repository / service / controller
backend/src/main/resources/schema.sql    mysql/schema.sql（两份相同，建表 + 种子数据）
frontend/src/                            App.vue（玻璃幕墙导航）、views/、api/、router/、styles/
```
