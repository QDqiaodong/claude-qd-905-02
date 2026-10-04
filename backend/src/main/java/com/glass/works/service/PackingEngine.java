package com.glass.works.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 双策略真实几何排样引擎（刀缝感知、旋转感知、断头台可切）。
 *
 * <p>所有坐标单位 mm，原片坐标系原点在左上角，x 向右、y 向下。</p>
 *
 * <p>两个策略共用同一原片幅面、同一份出片单元清单与同一刀缝：</p>
 * <ul>
 *   <li>{@code UTILIZATION}：MaxRects 自由矩形 + BSSF（最短边剩余优先）多排序搜索，
 *       目标是多放片、少碎料，布局只保证几何不重叠与刀缝，不保证一刀切到底；</li>
 *   <li>{@code REMNANT}：断头台条带（行/列 × 四锚点）搜索，
 *       目标是把出片压在一侧、另一侧留下一整块矩形余料，现场一刀分离即可再用。</li>
 * </ul>
 *
 * <p>面积闭合由网格分解统一计算：原片面积 = 出片面积 + 可复用/碎余料面积 + 刀缝损耗面积。
 * 「总面积放得下」不等于「几何放得下」，是否成功只以真实放入片数为准。</p>
 */
final class PackingEngine {

    static final double EPS = 1e-6;

    /** 可复用整块余料门槛：最短边不小于 300mm 且面积不小于 0.25㎡ */
    static final double REUSE_MIN_SIDE_MM = 300.0;
    static final double REUSE_MIN_AREA_M2 = 0.25;

    private PackingEngine() {
    }

    // ============================== 数据结构 ==============================

    /** 一片具体出片单元（同规格按数量展开后的每一片） */
    static final class Unit {
        final int id;
        /** 名义规格长/宽（清单上的填写值，不随旋转改变） */
        final int specL;
        final int specW;
        final int specIndex;
        final String remark;
        /** 该片是否允许 90° 旋转（全局开关与该规格开关的交集） */
        final boolean allowRotation;

        Unit(int id, int specL, int specW, int specIndex, String remark, boolean allowRotation) {
            this.id = id;
            this.specL = specL;
            this.specW = specW;
            this.specIndex = specIndex;
            this.remark = remark;
            this.allowRotation = allowRotation;
        }
    }

    /** 自由矩形：当前还能放片的矩形空位 */
    static final class FreeRect {
        double x;
        double y;
        double w;
        double h;

        FreeRect(double x, double y, double w, double h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        double area() {
            return w * h;
        }
    }

    /** 一种朝向选择 */
    static final class Orient {
        final double w;
        final double h;
        final boolean rotated;

        Orient(double w, double h, boolean rotated) {
            this.w = w;
            this.h = h;
            this.rotated = rotated;
        }
    }

    /** 一片已落位的出片 */
    static final class Placed {
        final double x;
        final double y;
        final double w;
        final double h;
        final Unit unit;
        final boolean rotated;

        Placed(double x, double y, double w, double h, Unit unit, boolean rotated) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.unit = unit;
            this.rotated = rotated;
        }
    }

    static final class Unplaced {
        final Unit unit;
        /** NO_FIT 几何放不下；LIMITED 搜索超时未确认 */
        final String reason;

        Unplaced(Unit unit, String reason) {
            this.unit = unit;
            this.reason = reason;
        }
    }

    /** 单次搜索结果（某个排序/锚点下） */
    static final class RawRun {
        final List<Placed> placed = new ArrayList<>();
        final List<Unplaced> unplaced = new ArrayList<>();
        final List<FreeRect> frees = new ArrayList<>();
        boolean timedOut;
    }

    // ============================== 入口 ==============================

    /** 策略标识，与 Dict 对齐 */
    static final String STRATEGY_UTILIZATION = "UTILIZATION";
    static final String STRATEGY_REMNANT = "REMNANT";

    /**
     * 跑指定策略（内部做多组排序/锚点搜索后择优）。每片是否允许旋转由 {@link Unit#allowRotation} 携带。
     *
     * @param deadlineNanos 截止时刻（System.nanoTime），0 表示不限时；仅 MaxRects 搜索会受其约束
     * @param maxOperations 搜索操作上限（双保险，防止时钟不敏感的环境里死算）
     */
    static RawRun search(String strategy, List<Unit> units, int sheetL, int sheetW,
                         int kerfMm, long deadlineNanos, long maxOperations) {
        if (STRATEGY_REMNANT.equals(strategy)) {
            return searchRemnant(units, sheetL, sheetW, kerfMm);
        }
        return searchUtilization(units, sheetL, sheetW, kerfMm, deadlineNanos, maxOperations);
    }

    // ============================== 策略 A：MaxRects ==============================

    private static RawRun searchUtilization(List<Unit> units, int sheetL, int sheetW,
                                            int kerf, long deadline, long maxOps) {
        List<Comparator<Unit>> orders = List.of(
                Comparator.comparingDouble((Unit u) -> -(double) u.specL * u.specW),
                Comparator.comparingInt((Unit u) -> -u.specW).thenComparingInt(u -> -u.specL),
                Comparator.comparingInt((Unit u) -> -Math.min(u.specL, u.specW)));

        long[] ops = new long[]{0};
        RawRun best = null;
        for (Comparator<Unit> order : orders) {
            List<Unit> arr = new ArrayList<>(units);
            arr.sort(order);
            RawRun run = runMaxRects(arr, sheetL, sheetW, kerf, deadline, maxOps, ops);
            if (betterUtil(run, best)) {
                best = run;
            }
            if (!run.timedOut && run.unplaced.isEmpty()) {
                break; // 已放全且无超时，不必再换排序
            }
        }
        return best;
    }

    private static boolean betterUtil(RawRun c, RawRun b) {
        if (b == null) {
            return true;
        }
        if (c.placed.size() != b.placed.size()) {
            return c.placed.size() > b.placed.size();
        }
        double ca = c.placed.stream().mapToDouble(p -> p.w * p.h).sum();
        double ba = b.placed.stream().mapToDouble(p -> p.w * p.h).sum();
        if (Math.abs(ca - ba) > EPS) {
            return ca > ba;
        }
        // 放入相同时，未超时的结果更可信
        if (c.timedOut != b.timedOut) {
            return !c.timedOut;
        }
        return false;
    }

    private static RawRun runMaxRects(List<Unit> arr, int sheetL, int sheetW, int kerf,
                                      long deadline, long maxOps, long[] ops) {
        RawRun out = new RawRun();
        List<FreeRect> frees = new ArrayList<>();
        frees.add(new FreeRect(0, 0, sheetL, sheetW));
        out.frees.addAll(frees);

        int idx = 0;
        for (; idx < arr.size(); idx++) {
            Unit u = arr.get(idx);
            ops[0] += arr.size() + frees.size() * 2L;
            boolean timed = ops[0] > maxOps
                    || (deadline > 0 && System.nanoTime() > deadline);
            if (timed) {
                out.timedOut = true;
                break;
            }

            List<Orient> options = orientations(u, sheetL, sheetW);
            FreeRect bestRect = null;
            Orient bestOrient = null;
            double bestShort = Double.POSITIVE_INFINITY;
            double bestLong = Double.POSITIVE_INFINITY;
            for (Orient o : options) {
                for (FreeRect f : frees) {
                    if (o.w <= f.w + EPS && o.h <= f.h + EPS) {
                        double gapShort = Math.min(f.w - o.w, f.h - o.h);
                        double gapLong = Math.max(f.w - o.w, f.h - o.h);
                        if (gapShort < bestShort - EPS
                                || (Math.abs(gapShort - bestShort) <= EPS && gapLong < bestLong)) {
                            bestShort = gapShort;
                            bestLong = gapLong;
                            bestRect = f;
                            bestOrient = o;
                        }
                    }
                }
            }

            if (bestRect == null) {
                out.unplaced.add(new Unplaced(u, "NO_FIT"));
                continue;
            }
            out.placed.add(new Placed(bestRect.x, bestRect.y, bestOrient.w, bestOrient.h, u, bestOrient.rotated));
            frees = occupy(frees, bestRect.x, bestRect.y,
                    bestOrient.w + kerf, bestOrient.h + kerf, kerf);
        }
        if (out.timedOut) {
            // 当前片及其后所有片：放入性未确认，禁止伪装成功
            for (; idx < arr.size(); idx++) {
                out.unplaced.add(new Unplaced(arr.get(idx), "LIMITED"));
            }
        }
        out.frees.clear();
        out.frees.addAll(frees);
        return out;
    }

    // ============================== 策略 B：断头台条带 ==============================

    private static final List<String> ANCHORS = List.of("TOP", "BOTTOM", "LEFT", "RIGHT");

    private static RawRun searchRemnant(List<Unit> units, int sheetL, int sheetW, int kerf) {
        List<Comparator<Unit>> orders = List.of(
                Comparator.comparingInt((Unit u) -> -u.specW).thenComparingInt(u -> -u.specL),
                Comparator.comparingInt((Unit u) -> -u.specL).thenComparingInt(u -> -u.specW),
                Comparator.comparingDouble((Unit u) -> -(double) u.specL * u.specW));

        RawRun best = null;
        for (String anchor : ANCHORS) {
            for (Comparator<Unit> order : orders) {
                List<Unit> arr = new ArrayList<>(units);
                arr.sort(order);
                RawRun run = runShelf(arr, sheetL, sheetW, kerf, anchor);
                if (betterRemnant(run, best, sheetL, sheetW)) {
                    best = run;
                }
            }
        }
        return best;
    }

    private static boolean betterRemnant(RawRun c, RawRun b, int sheetL, int sheetW) {
        if (b == null) {
            return true;
        }
        if (c.placed.size() != b.placed.size()) {
            return c.placed.size() > b.placed.size();
        }
        // 放同样多：比最大整块余料短边 → 可复用余料合计 → 刀缝损耗更少
        Grid gc = Grid.build(sheetL, sheetW, c.placed, c.frees);
        Grid gb = Grid.build(sheetL, sheetW, b.placed, b.frees);
        if (Math.abs(gc.biggestReuseMinSide() - gb.biggestReuseMinSide()) > EPS) {
            return gc.biggestReuseMinSide() > gb.biggestReuseMinSide();
        }
        if (Math.abs(gc.reuseArea - gb.reuseArea) > EPS) {
            return gc.reuseArea > gb.reuseArea;
        }
        return gc.kerfArea < gb.kerfArea - EPS;
    }

    /** 锚点：TOP/BOTTOM 为水平行（向 y 方向堆），LEFT/RIGHT 为竖直列（向 x 方向堆） */
    private static RawRun runShelf(List<Unit> arr, int sheetL, int sheetW, int kerf, String anchor) {
        boolean isRow = anchor.equals("TOP") || anchor.equals("BOTTOM");
        RawRun out = new RawRun();

        // 每条带：cross = 带宽（垂直于延伸方向），alongUsed = 沿延伸方向已用长度
        List<double[]> bands = new ArrayList<>();      // [cross, alongUsed]
        List<List<PlacedItem>> bandUnits = new ArrayList<>();
        double bandUsed = 0;

        for (Unit u : arr) {
            List<Orient> options = orientations(u, sheetL, sheetW);
            // 条带里优先让垂直于延伸方向的尺寸更小（少占带宽），其次不旋转
            options.sort((x, y) -> {
                double cx = isRow ? x.h : x.w;
                double cy = isRow ? y.h : y.w;
                if (Math.abs(cx - cy) > EPS) {
                    return Double.compare(cx, cy);
                }
                return Boolean.compare(x.rotated, y.rotated);
            });

            boolean done = false;
            for (Orient o : options) {
                double along = isRow ? o.w : o.h;
                double cross = isRow ? o.h : o.w;
                double alongCap = isRow ? sheetL : sheetW;
                double crossCap = isRow ? sheetW : sheetL;

                for (int bi = 0; bi < bands.size(); bi++) {
                    double[] band = bands.get(bi);
                    boolean empty = band[1] <= EPS;
                    double need = (empty ? 0 : kerf) + along;
                    if (cross <= band[0] + EPS && band[1] + need <= alongCap + EPS) {
                        band[1] += need;
                        bandUnits.get(bi).add(new PlacedItem(u, o));
                        done = true;
                        break;
                    }
                }
                if (done) {
                    break;
                }
                boolean noBand = bands.isEmpty();
                double needBand = (noBand ? 0 : kerf) + cross;
                if (bandUsed + needBand <= crossCap + EPS) {
                    bandUsed += needBand;
                    bands.add(new double[]{cross, along});
                    List<PlacedItem> items = new ArrayList<>();
                    items.add(new PlacedItem(u, o));
                    bandUnits.add(items);
                    done = true;
                    break;
                }
            }
            if (!done) {
                out.unplaced.add(new Unplaced(u, "NO_FIT"));
            }
        }

        // 落坐标，并构造自由矩形（行尾 + 对侧整块余料）
        List<FreeRect> frees = new ArrayList<>();
        if (isRow) {
            double y = anchor.equals("TOP") ? 0 : sheetW - bandUsed;
            for (int bi = 0; bi < bands.size(); bi++) {
                double[] band = bands.get(bi);
                double x = 0;
                boolean first = true;
                for (PlacedItem it : bandUnits.get(bi)) {
                    if (!first) {
                        x += kerf;
                    }
                    out.placed.add(new Placed(x, y, it.o.w, it.o.h, it.u, it.o.rotated));
                    x += it.o.w;
                    first = false;
                }
                if (sheetL - band[1] > EPS) {
                    frees.add(new FreeRect(band[1], y, sheetL - band[1], band[0]));
                }
                y += band[0] + kerf;
            }
            // 刀缝只在带与带之间；最后一条带之外是整块余料（与出片之间一刀分离，不另计刀缝）
            if (bandUsed < sheetW - EPS) {
                if (anchor.equals("TOP")) {
                    frees.add(new FreeRect(0, bandUsed, sheetL, sheetW - bandUsed));
                } else {
                    frees.add(new FreeRect(0, 0, sheetL, sheetW - bandUsed));
                }
            }
        } else {
            double x = anchor.equals("LEFT") ? 0 : sheetL - bandUsed;
            for (int bi = 0; bi < bands.size(); bi++) {
                double[] band = bands.get(bi);
                double y = 0;
                boolean first = true;
                for (PlacedItem it : bandUnits.get(bi)) {
                    if (!first) {
                        y += kerf;
                    }
                    out.placed.add(new Placed(x, y, it.o.w, it.o.h, it.u, it.o.rotated));
                    y += it.o.h;
                    first = false;
                }
                if (sheetW - band[1] > EPS) {
                    frees.add(new FreeRect(x, band[1], band[0], sheetW - band[1]));
                }
                x += band[0] + kerf;
            }
            if (bandUsed < sheetL - EPS) {
                if (anchor.equals("LEFT")) {
                    frees.add(new FreeRect(bandUsed, 0, sheetL - bandUsed, sheetW));
                } else {
                    frees.add(new FreeRect(0, 0, sheetL - bandUsed, sheetW));
                }
            }
        }
        out.frees.addAll(frees);
        return out;
    }

    private record PlacedItem(Unit u, Orient o) {
    }

    // ============================== 朝向与自由矩形切分 ==============================

    private static List<Orient> orientations(Unit u, int sheetL, int sheetW) {
        List<Orient> list = new ArrayList<>(2);
        if (u.specL <= sheetL + EPS && u.specW <= sheetW + EPS) {
            list.add(new Orient(u.specL, u.specW, false));
        }
        if (u.allowRotation && u.specL != u.specW
                && u.specW <= sheetL + EPS && u.specL <= sheetW + EPS) {
            list.add(new Orient(u.specW, u.specL, true));
        }
        return list;
    }

    /** 名义规格在允许旋转后是否可能进入原片（单片可行性校验用） */
    static boolean canFitSingle(int specL, int specW, int sheetL, int sheetW, boolean allowRotation) {
        boolean direct = specL <= sheetL && specW <= sheetW;
        boolean rotated = allowRotation && specW <= sheetL && specL <= sheetW;
        return direct || rotated;
    }

    /** 用扩张后的占用矩形 (px,py,pw,ph) 切分一个自由矩形，返回至多 4 个新自由矩形 */
    private static List<FreeRect> splitFree(FreeRect f, double px, double py, double pw, double ph, int kerf) {
        boolean disjoint = px >= f.x + f.w - EPS || px + pw <= f.x + EPS
                || py >= f.y + f.h - EPS || py + ph <= f.y + EPS;
        if (disjoint) {
            return List.of(f);
        }
        List<FreeRect> out = new ArrayList<>(4);
        double leftW = px - kerf - f.x;
        if (leftW > EPS) {
            out.add(new FreeRect(f.x, f.y, leftW, f.h));
        }
        double rightX = px + pw + kerf;
        if (rightX < f.x + f.w - EPS) {
            out.add(new FreeRect(rightX, f.y, f.x + f.w - rightX, f.h));
        }
        double topH = py - kerf - f.y;
        if (topH > EPS) {
            out.add(new FreeRect(f.x, f.y, f.w, topH));
        }
        double bottomY = py + ph + kerf;
        if (bottomY < f.y + f.h - EPS) {
            out.add(new FreeRect(f.x, bottomY, f.w, f.y + f.h - bottomY));
        }
        return out;
    }

    private static boolean contained(FreeRect a, FreeRect b) {
        return a.x >= b.x - EPS && a.y >= b.y - EPS
                && a.x + a.w <= b.x + b.w + EPS
                && a.y + a.h <= b.y + b.h + EPS;
    }

    /** 删除被其它自由矩形包含的冗余项 */
    private static List<FreeRect> pruneContained(List<FreeRect> list) {
        List<FreeRect> keep = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            boolean covered = false;
            for (int j = 0; j < list.size(); j++) {
                if (i == j) {
                    continue;
                }
                FreeRect a = list.get(i);
                FreeRect b = list.get(j);
                if (contained(a, b) && (b.area() > a.area() + EPS || j < i)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                keep.add(list.get(i));
            }
        }
        return keep;
    }

    private static List<FreeRect> occupy(List<FreeRect> frees, double px, double py,
                                         double pw, double ph, int kerf) {
        List<FreeRect> next = new ArrayList<>();
        for (FreeRect f : frees) {
            next.addAll(splitFree(f, px, py, pw, ph, kerf));
        }
        return pruneContained(next);
    }

    // ============================== 网格面积分解 ==============================

    /**
     * 以所有出片与自由矩形边界为网格线，把原片切成不重叠小格，
     * 逐格归类为「出片 / 自由（余料）/ 刀缝损耗」，再合并同类连续格为矩形。
     * 这样余料与刀缝面积精确闭合，且返回给前端的余料矩形彼此不重叠。
     */
    static final class Grid {
        double pieceArea;
        double freeArea;
        double kerfArea;
        double reuseArea;
        final List<double[]> freeRects = new ArrayList<>();
        final List<double[]> kerfRects = new ArrayList<>();

        static Grid build(int sheetL, int sheetW, List<Placed> placed, List<FreeRect> frees) {
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            xs.add(0.0);
            xs.add((double) sheetL);
            ys.add(0.0);
            ys.add((double) sheetW);
            for (Placed p : placed) {
                xs.add(p.x);
                xs.add(p.x + p.w);
                ys.add(p.y);
                ys.add(p.y + p.h);
            }
            for (FreeRect f : frees) {
                xs.add(f.x);
                xs.add(f.x + f.w);
                ys.add(f.y);
                ys.add(f.y + f.h);
            }
            xs.sort(Double::compare);
            ys.sort(Double::compare);
            double[] ux = uniqueSorted(xs);
            double[] uy = uniqueSorted(ys);
            int nx = ux.length - 1;
            int ny = uy.length - 1;

            byte[] pieceG = new byte[nx * ny];
            byte[] freeG = new byte[nx * ny];
            mark(ux, uy, pieceG, placed, frees, true);
            mark(ux, uy, freeG, placed, frees, false);

            Grid g = new Grid();
            g.pieceArea = sumArea(ux, uy, pieceG);
            g.freeArea = sumArea(ux, uy, freeG);
            byte[] kerfG = new byte[nx * ny];
            for (int i = 0; i < kerfG.length; i++) {
                kerfG[i] = (pieceG[i] == 0 && freeG[i] == 0) ? (byte) 1 : 0;
            }
            g.kerfArea = sumArea(ux, uy, kerfG);

            mergeRuns(ux, uy, freeG, nx, ny, g.freeRects);
            mergeRuns(ux, uy, kerfG, nx, ny, g.kerfRects);
            for (double[] r : g.freeRects) {
                double minSide = Math.min(r[2], r[3]);
                double areaM2 = r[2] * r[3] / 1_000_000.0;
                if (minSide >= REUSE_MIN_SIDE_MM - EPS && areaM2 >= REUSE_MIN_AREA_M2 - EPS) {
                    g.reuseArea += r[2] * r[3];
                }
            }
            return g;
        }

        double biggestReuseMinSide() {
            double best = 0;
            double bestArea = 0;
            for (double[] r : freeRects) {
                double minSide = Math.min(r[2], r[3]);
                double area = r[2] * r[3];
                double areaM2 = area / 1_000_000.0;
                boolean reusable = minSide >= REUSE_MIN_SIDE_MM - EPS
                        && areaM2 >= REUSE_MIN_AREA_M2 - EPS;
                if (reusable && (minSide > best + EPS || (Math.abs(minSide - best) <= EPS && area > bestArea))) {
                    best = minSide;
                    bestArea = area;
                }
            }
            return best;
        }

        /** 最大可复用整块余料（短边优先，再比面积）；无则 null */
        double[] biggestReuseRect() {
            double[] best = null;
            double bestSide = 0;
            double bestArea = 0;
            for (double[] r : freeRects) {
                double minSide = Math.min(r[2], r[3]);
                double area = r[2] * r[3];
                double areaM2 = area / 1_000_000.0;
                boolean reusable = minSide >= REUSE_MIN_SIDE_MM - EPS
                        && areaM2 >= REUSE_MIN_AREA_M2 - EPS;
                if (reusable && (minSide > bestSide + EPS
                        || (Math.abs(minSide - bestSide) <= EPS && area > bestArea))) {
                    best = r;
                    bestSide = minSide;
                    bestArea = area;
                }
            }
            return best;
        }

        private static void mark(double[] ux, double[] uy, byte[] grid,
                                 List<Placed> placed, List<FreeRect> frees, boolean pieces) {
            int nx = ux.length - 1;
            if (pieces) {
                for (Placed p : placed) {
                    markRect(ux, uy, grid, nx, p.x, p.y, p.x + p.w, p.y + p.h);
                }
            } else {
                for (FreeRect f : frees) {
                    markRect(ux, uy, grid, nx, f.x, f.y, f.x + f.w, f.y + f.h);
                }
            }
        }

        private static void markRect(double[] ux, double[] uy, byte[] grid, int nx,
                                     double x0, double y0, double x1, double y1) {
            int ix0 = lowerIndex(ux, x0);
            int ix1 = lowerIndex(ux, x1);
            int iy0 = lowerIndex(uy, y0);
            int iy1 = lowerIndex(uy, y1);
            for (int j = iy0; j < iy1; j++) {
                for (int i = ix0; i < ix1; i++) {
                    grid[j * nx + i] = 1;
                }
            }
        }

        private static int lowerIndex(double[] unique, double value) {
            for (int i = 0; i < unique.length; i++) {
                if (Math.abs(unique[i] - value) <= 1e-4) {
                    return i;
                }
            }
            throw new IllegalStateException("网格边界丢失: " + value);
        }

        private static double[] uniqueSorted(List<Double> sorted) {
            List<Double> out = new ArrayList<>();
            for (double v : sorted) {
                if (out.isEmpty() || Math.abs(v - out.get(out.size() - 1)) > 1e-4) {
                    out.add(v);
                }
            }
            double[] arr = new double[out.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = out.get(i);
            }
            return arr;
        }

        private static double sumArea(double[] ux, double[] uy, byte[] grid) {
            int nx = ux.length - 1;
            int ny = uy.length - 1;
            double area = 0;
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    if (grid[j * nx + i] == 1) {
                        area += (ux[i + 1] - ux[i]) * (uy[j + 1] - uy[j]);
                    }
                }
            }
            return area;
        }

        /**
         * 把标记格合并为不重叠铺盖矩形：先取每行连续段，
         * 再把「列起止完全一致」的相邻行段纵向合并。
         */
        private static void mergeRuns(double[] ux, double[] uy, byte[] grid, int nx, int ny,
                                      List<double[]> out) {
            List<int[]> runs = new ArrayList<>(); // [j, i0, i1, used]
            for (int j = 0; j < ny; j++) {
                int i = 0;
                while (i < nx) {
                    if (grid[j * nx + i] == 1) {
                        int k = i;
                        while (k < nx && grid[j * nx + k] == 1) {
                            k++;
                        }
                        runs.add(new int[]{j, i, k, 0});
                        i = k;
                    } else {
                        i++;
                    }
                }
            }
            for (int[] r : runs) {
                if (r[3] == 1) {
                    continue;
                }
                int jEnd = r[0] + 1;
                while (jEnd < ny) {
                    boolean found = false;
                    for (int[] q : runs) {
                        if (q[3] == 0 && q[0] == jEnd && q[1] == r[1] && q[2] == r[2]) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        break;
                    }
                    jEnd++;
                }
                for (int[] q : runs) {
                    if (q[0] >= r[0] && q[0] < jEnd && q[1] == r[1] && q[2] == r[2]) {
                        q[3] = 1;
                    }
                }
                out.add(new double[]{ux[r[1]], uy[r[0]], ux[r[2]] - ux[r[1]], uy[jEnd] - uy[r[0]]});
            }
        }
    }
}
