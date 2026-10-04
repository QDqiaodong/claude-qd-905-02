package com.glass.works.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

/**
 * 二维矩形排样引擎（玻璃切割场景）。
 *
 * <p>关键点：</p>
 * <ul>
 *   <li><b>刀缝（锯路）</b>：每块出片的占位尺寸在右 / 下方向膨胀 kerf，原片幅面同样膨胀 kerf，
 *       于是相邻两片之间的真实几何间距 ≥ kerf，贴边放置又不会在原片外沿凭空吃掉刀缝。</li>
 *   <li><b>真实几何可放置性</b>：最大空闲矩形（maximal free rectangle）+ 十字切分，
 *       面积够但几何放不下时，剩余出片进入 unplaced，绝不按总面积判成功。</li>
 *   <li><b>双目标</b>：{@link Strategy#USAGE} 以全部排入、利用率优先；
 *       {@link Strategy#REMNANT} 以条带排样保出最大整块可复用余料。</li>
 *   <li><b>超时预算</b>：多组确定性启发式配置竞争，deadline 截断；
 *       预算耗尽且当前最优不完整时如实标记 timedOut。</li>
 * </ul>
 */
public final class PackingEngine {

    /** 可复用整块门槛：短边 ≥ 300mm 且面积 ≥ 0.30㎡（现场口径，固定值，不随结果浮动） */
    public static final double REUSABLE_MIN_SHORT_MM = 300.0;
    public static final double REUSABLE_MIN_AREA_MM2 = 300_000.0;
    /** 余料清单项：小于 0.005㎡（5000mm²）的窄缝碎料并入刀缝/碎料损耗，不单独列条 */
    private static final double LIST_MIN_AREA_MM2 = 5_000.0;
    private static final double EPS = 1e-6;

    public enum Strategy { USAGE, REMNANT }

    /** 一行出片规格（展开前） */
    public static final class PieceDef {
        public final int rowIndex;
        public final int lengthMm;
        public final int widthMm;
        public final int quantity;

        public PieceDef(int rowIndex, int lengthMm, int widthMm, int quantity) {
            this.rowIndex = rowIndex;
            this.lengthMm = lengthMm;
            this.widthMm = widthMm;
            this.quantity = quantity;
        }
    }

    /** 一块已排入的出片（真实坐标，未含刀缝膨胀） */
    public static final class Placement {
        public final double x, y, w, h;
        public final int rowIndex;
        public final int pieceLengthMm, pieceWidthMm;
        public final boolean rotated;

        Placement(double x, double y, double w, double h, int rowIndex,
                  int pieceLengthMm, int pieceWidthMm, boolean rotated) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.rowIndex = rowIndex;
            this.pieceLengthMm = pieceLengthMm;
            this.pieceWidthMm = pieceWidthMm;
            this.rotated = rotated;
        }
    }

    /** 一块物理空闲矩形（真实坐标） */
    public static final class FreeRect {
        public final double x, y, w, h;
        FreeRect(double x, double y, double w, double h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
        public double area() {
            return w * h;
        }
        public double shortSide() {
            return Math.min(w, h);
        }
        public double getX() {
            return x;
        }
        public double getY() {
            return y;
        }
        public double getW() {
            return w;
        }
        public double getH() {
            return h;
        }
    }

    /** 一行规格的未排入情况 */
    public static final class Unplaced {
        public final int rowIndex;
        public final int missing;
        /** NO_ROTATION_FIT / GEOMETRY_FULL / SEARCH_TIMEOUT */
        public final String reasonCode;

        Unplaced(int rowIndex, int missing, String reasonCode) {
            this.rowIndex = rowIndex;
            this.missing = missing;
            this.reasonCode = reasonCode;
        }
    }

    /** 单次排样输出（坐标均为不含膨胀的真实 mm） */
    public static final class Output {
        public final List<Placement> placements = new ArrayList<>();
        public final List<Unplaced> unplaced = new ArrayList<>();
        /** 覆盖全部空闲区域的互不重叠余料矩形（已过滤窄缝碎料） */
        public final List<FreeRect> remnantRects = new ArrayList<>();
        /** 各余料矩形是否达到可复用整块门槛（与 remnantRects 同序） */
        public final List<Boolean> remnantReusable = new ArrayList<>();
        /** 最大一块可复用整块（按最大空闲矩形口径），无则 null */
        public FreeRect largestReusable;
        /** 刀缝 + 窄缝碎料损耗面积 mm²（总空闲 − 已列余料） */
        public double kerfAndScrapMm2;
        public boolean timedOut;
        public boolean complete;
    }

    private final int sheetL;
    private final int sheetW;
    private final int kerf;
    private final boolean allowRotation;
    private final long deadlineNanos;

    private List<PieceDef> rows;
    /** unit = {rowIndex, 原始长, 原始宽} */
    private List<int[]> units;
    private int[] neverFit;
    /** 该行规格仅旋转后能入版（禁止旋转时未排入要提示开启旋转） */
    private boolean[] onlyRotatedFits;

    public PackingEngine(int sheetLengthMm, int sheetWidthMm, int kerfMm, boolean allowRotation,
                         long deadlineNanos) {
        this.sheetL = sheetLengthMm;
        this.sheetW = sheetWidthMm;
        this.kerf = kerfMm;
        this.allowRotation = allowRotation;
        this.deadlineNanos = deadlineNanos;
    }

    // ---------------- 对外入口 ----------------

    public Output run(Strategy strategy, List<PieceDef> pieceRows) {
        this.rows = pieceRows;
        expandUnits();
        return strategy == Strategy.REMNANT ? runRemnant() : runUsage();
    }

    private void expandUnits() {
        units = new ArrayList<>();
        neverFit = new int[rows.size()];
        onlyRotatedFits = new boolean[rows.size()];
        for (PieceDef def : rows) {
            // 单片“物理上是否可能入版”按两个方向都试判定，与本次排样是否允许旋转无关：
            // 允许旋转时排样器会去放；禁止旋转时由排样器方向约束把它留在未排入清单。
            boolean plain = def.lengthMm <= sheetL + EPS && def.widthMm <= sheetW + EPS;
            boolean rotated = def.widthMm <= sheetL + EPS && def.lengthMm <= sheetW + EPS;
            if (!plain && !rotated) {
                neverFit[def.rowIndex] += def.quantity;
                continue;
            }
            onlyRotatedFits[def.rowIndex] = !plain;
            for (int i = 0; i < def.quantity; i++) {
                units.add(new int[]{def.rowIndex, def.lengthMm, def.widthMm});
            }
        }
    }

    // ---------------- 利用率优先 ----------------

    private Output runUsage() {
        Pack best = null;
        boolean budgetExhausted = false;
        for (PackCfg cfg : usageConfigs()) {
            if (System.nanoTime() > deadlineNanos) {
                budgetExhausted = true;
                break;
            }
            Pack p = greedy(cfg, sheetL, sheetW, true);
            if (betterForUsage(p, best)) {
                best = p;
            }
            // 全部排入时利用率已到顶（出片面积固定），不必再耗预算
            if (best.placedUnits.size() == units.size()) {
                return finalize(best, false);
            }
        }
        return finalize(best, budgetExhausted);
    }

    // ---------------- 余料保留优先 ----------------

    private Output runRemnant() {
        Pack best = null;
        boolean budgetExhausted = false;

        TreeSet<Integer> rightWidths = new TreeSet<>(); // 右条带候选宽度
        TreeSet<Integer> topHeights = new TreeSet<>();  // 顶条带候选高度
        for (int[] u : units) {
            if (u[1] <= sheetL) {
                rightWidths.add(u[1]);
            }
            if (u[2] <= sheetW) {
                topHeights.add(u[2]);
            }
            if (allowRotation) {
                if (u[2] <= sheetL) {
                    rightWidths.add(u[2]);
                }
                if (u[1] <= sheetW) {
                    topHeights.add(u[1]);
                }
            }
        }

        // 两个方向各找到“能排满的最窄条带”即该方向最优，再横向比较取整块更大者
        for (int pass = 0; pass < 2; pass++) {
            boolean horizontal = pass == 0;
            TreeSet<Integer> candidates = horizontal ? rightWidths : topHeights;
            boolean passFull = false;
            for (Integer s : candidates) {
                for (PackCfg cfg : stripConfigs()) {
                    if (System.nanoTime() > deadlineNanos) {
                        budgetExhausted = true;
                        break;
                    }
                    Pack p = horizontal ? greedy(cfg, s, sheetW, true)
                            : greedy(cfg, sheetL, s, false);
                    if (p.placedUnits.size() == units.size() && betterForRemnant(p, best)) {
                        best = p;
                    }
                }
                if (best != null && best.placedUnits.size() == units.size()) {
                    passFull = true;
                    break; // 候选升序，首个排满宽度即本方向最优
                }
            }
            if (budgetExhausted && !passFull) {
                break;
            }
        }

        // 条带没排满（或没有可行条带），用通用配置兜底，尽量多排
        if (best == null || best.placedUnits.size() < units.size()) {
            for (PackCfg cfg : usageConfigs()) {
                if (System.nanoTime() > deadlineNanos) {
                    budgetExhausted = true;
                    break;
                }
                Pack p = greedy(cfg, sheetL, sheetW, true);
                if (betterForRemnant(p, best)) {
                    best = p;
                }
                if (best.placedUnits.size() == units.size()) {
                    break;
                }
            }
        }

        boolean complete = best != null && best.placedUnits.size() == units.size();
        // 预算耗尽且结果不完整才算超时；条带宽度枚举完毕仍排不满属于几何放不下
        return finalize(best, !complete && budgetExhausted);
    }

    // ---------------- 贪心排样核心 ----------------

    private static final int OR_NATIVE = 0; // 原方向：长边沿 x
    private static final int OR_WIDE = 1;   // 宽边沿 x
    private static final int OR_FREE = 2;   // 每个空位两个方向都试

    private static final class PackCfg {
        int order;       // 0 面积降序 1 长边降序 2 沿 x 边降序 3 周长降序
        int selector;    // 0 最贴合面积 1 最短边贴合 2 左下优先
        int orient;      // OR_*
        long seed;       // 随机次序种子，0 表示不随机
    }

    private List<PackCfg> usageConfigs() {
        List<PackCfg> all = new ArrayList<>();
        int[] orients = allowRotation ? new int[]{OR_FREE, OR_WIDE, OR_NATIVE} : new int[]{OR_NATIVE};
        // 最可能命中的配置排前面：面积降序 + 最贴合 + 自由旋转
        for (int orient : orients) {
            for (int order = 0; order < 4; order++) {
                for (int selector = 0; selector < 3; selector++) {
                    PackCfg cfg = new PackCfg();
                    cfg.order = order;
                    cfg.selector = selector;
                    cfg.orient = orient;
                    all.add(cfg);
                }
            }
        }
        long[] seeds = {11L, 23L, 47L, 83L, 131L, 211L};
        for (long seed : seeds) {
            PackCfg cfg = new PackCfg();
            cfg.order = 0;
            cfg.selector = 0;
            cfg.orient = allowRotation ? OR_FREE : OR_NATIVE;
            cfg.seed = seed;
            all.add(cfg);
        }
        return all;
    }

    private List<PackCfg> stripConfigs() {
        List<PackCfg> all = new ArrayList<>();
        int[] orients = allowRotation ? new int[]{OR_FREE, OR_WIDE} : new int[]{OR_NATIVE};
        for (int orient : orients) {
            for (int order = 0; order < 3; order++) {
                PackCfg cfg = new PackCfg();
                cfg.order = order;
                cfg.selector = 0;
                cfg.orient = orient;
                all.add(cfg);
            }
        }
        return all;
    }

    /** 工作态：一块已放置单元（膨胀坐标） */
    private static final class Cell {
        double x, y, cw, ch; // 膨胀坐标
        int w, h;            // 真实尺寸
        int rowIndex, pl, pw;
        boolean rotated;
    }

    private static final class Pack {
        final List<Cell> placedCells = new ArrayList<>();
        final List<int[]> placedUnits = new ArrayList<>();
        /** 膨胀坐标系下的最大空闲矩形 */
        final List<FreeRect> free = new ArrayList<>();
        int zoneL, zoneW;
        boolean horizontalStrip;
    }

    private Pack greedy(PackCfg cfg, int zoneL, int zoneW, boolean horizontalStrip) {
        Pack pack = new Pack();
        pack.zoneL = zoneL;
        pack.zoneW = zoneW;
        pack.horizontalStrip = horizontalStrip;
        // 膨胀模型：出片 +kerf，区域 +kerf
        pack.free.add(new FreeRect(0, 0, zoneL + (double) kerf, zoneW + (double) kerf));

        List<int[]> working = new ArrayList<>(units);
        orderUnits(working, cfg);

        for (int[] u : working) {
            Cell chosen = choosePlacement(pack, cfg, u);
            if (chosen == null) {
                continue;
            }
            placeCell(pack, chosen);
            pack.placedUnits.add(u);
        }
        return pack;
    }

    private void orderUnits(List<int[]> working, PackCfg cfg) {
        if (cfg.seed != 0) {
            java.util.Collections.shuffle(working, new Random(cfg.seed));
            return;
        }
        Comparator<int[]> cmp;
        switch (cfg.order) {
            case 1:
                cmp = Comparator.<int[]>comparingInt(u -> -Math.max(u[1], u[2]))
                        .thenComparingInt(u -> -Math.min(u[1], u[2]));
                break;
            case 2:
                cmp = Comparator.<int[]>comparingInt(u -> -orientedX(u, cfg.orient))
                        .thenComparingInt(u -> -orientedY(u, cfg.orient));
                break;
            case 3:
                cmp = Comparator.<int[]>comparingInt(u -> -(u[1] + u[2]));
                break;
            default:
                cmp = Comparator.<int[]>comparingInt(u -> -u[1] * u[2])
                        .thenComparingInt(u -> -Math.max(u[1], u[2]));
        }
        working.sort(cmp);
    }

    private int orientedX(int[] u, int orient) {
        return orient == OR_WIDE ? Math.max(u[1], u[2]) : u[1];
    }

    private int orientedY(int[] u, int orient) {
        return orient == OR_WIDE ? Math.min(u[1], u[2]) : u[2];
    }

    /** 在所有空闲矩形里为一块出片选位（含方向选择） */
    private Cell choosePlacement(Pack pack, PackCfg cfg, int[] u) {
        int rowIndex = u[0];
        int pl = u[1];
        int pw = u[2];
        Cell best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean tryBoth = allowRotation && cfg.orient == OR_FREE;
        boolean wideFirst = allowRotation && cfg.orient == OR_WIDE;

        for (FreeRect fr : pack.free) {
            int[][] options;
            if (tryBoth) {
                options = new int[][]{{pl, pw, 0}, {pw, pl, 1}};
            } else if (wideFirst && pw > pl) {
                options = new int[][]{{pw, pl, 1}, {pl, pw, 0}};
            } else {
                options = new int[][]{{pl, pw, 0}};
            }
            for (int[] op : options) {
                int w = op[0];
                int h = op[1];
                // 必须用膨胀后的占位尺寸（含刀缝）与空闲矩形比较，否则贴边排会越界
                double cw = w + (double) kerf;
                double ch = h + (double) kerf;
                if (cw > fr.w + EPS || ch > fr.h + EPS) {
                    continue;
                }
                double score = scoreGap(fr, w, h, cfg.selector);
                if (score > bestScore + 1e-9) {
                    bestScore = score;
                    Cell cell = new Cell();
                    cell.x = fr.x;
                    cell.y = fr.y;
                    cell.cw = w + (double) kerf;
                    cell.ch = h + (double) kerf;
                    cell.w = w;
                    cell.h = h;
                    cell.rowIndex = rowIndex;
                    cell.pl = pl;
                    cell.pw = pw;
                    cell.rotated = op[2] == 1;
                    best = cell;
                }
            }
        }
        return best;
    }

    /** 选位打分：越大越优先。不同 selector 给出贴合 / 左下等口径 */
    private double scoreGap(FreeRect fr, int w, int h, int selector) {
        double cw = w + (double) kerf;
        double ch = h + (double) kerf;
        switch (selector) {
            case 1: // 最短边贴合：优先塞进窄空位
                double leftW = fr.w - cw;
                double leftH = fr.h - ch;
                return -(Math.min(leftW, leftH) * 1_000_000.0 + Math.max(leftW, leftH));
            case 2: // 左下优先：沿切割线整行推进
                return -(fr.y * 10_000_000.0 + fr.x);
            default: // 最贴合面积，tie-break 短边
                double waste = fr.w * fr.h - cw * ch;
                double shortLeftover = Math.min(fr.w - cw, fr.h - ch);
                return -(waste * 100.0 + shortLeftover);
        }
    }

    /** 放入一块出片，并对所有相交空闲矩形做十字切分、去包含 */
    private void placeCell(Pack pack, Cell cell) {
        List<FreeRect> next = new ArrayList<>();
        for (FreeRect fr : pack.free) {
            boolean overlapX = cell.x < fr.x + fr.w - EPS && cell.x + cell.cw > fr.x + EPS;
            boolean overlapY = cell.y < fr.y + fr.h - EPS && cell.y + cell.ch > fr.y + EPS;
            if (!(overlapX && overlapY)) {
                next.add(fr);
                continue;
            }
            if (fr.x < cell.x - EPS) {
                next.add(new FreeRect(fr.x, fr.y, cell.x - fr.x, fr.h));
            }
            if (fr.x + fr.w > cell.x + cell.cw + EPS) {
                next.add(new FreeRect(cell.x + cell.cw, fr.y, fr.x + fr.w - (cell.x + cell.cw), fr.h));
            }
            if (fr.y < cell.y - EPS) {
                next.add(new FreeRect(fr.x, fr.y, fr.w, cell.y - fr.y));
            }
            if (fr.y + fr.h > cell.y + cell.ch + EPS) {
                next.add(new FreeRect(fr.x, cell.y + cell.ch, fr.w, fr.y + fr.h - (cell.y + cell.ch)));
            }
        }
        pruneContained(next);
        pack.free.clear();
        pack.free.addAll(next);
        pack.placedCells.add(cell);
    }

    private void pruneContained(List<FreeRect> rects) {
        for (int i = rects.size() - 1; i >= 0; i--) {
            FreeRect a = rects.get(i);
            for (int j = 0; j < rects.size(); j++) {
                if (i == j) {
                    continue;
                }
                FreeRect b = rects.get(j);
                boolean contained = b.x <= a.x + EPS && b.y <= a.y + EPS
                        && b.x + b.w >= a.x + a.w - EPS
                        && b.y + b.h >= a.y + a.h - EPS;
                if (!contained) {
                    continue;
                }
                boolean sameish = Math.abs(b.area() - a.area()) < 1e-6;
                if (!sameish || j < i) {
                    rects.remove(i);
                    break;
                }
            }
        }
    }

    // ---------------- 方案比较 ----------------

    private boolean betterForUsage(Pack p, Pack best) {
        if (best == null) {
            return true;
        }
        if (p.placedUnits.size() != best.placedUnits.size()) {
            return p.placedUnits.size() > best.placedUnits.size();
        }
        double pFree = biggestFreeArea(p);
        double bFree = biggestFreeArea(best);
        if (Math.abs(pFree - bFree) > 100.0) {
            return pFree > bFree; // 同样片数：保一块更大的空闲矩形（余料更整）
        }
        return biggestFreeShort(p) > biggestFreeShort(best) + EPS;
    }

    private boolean betterForRemnant(Pack p, Pack best) {
        if (best == null) {
            return true;
        }
        if (p.placedUnits.size() != best.placedUnits.size()) {
            return p.placedUnits.size() > best.placedUnits.size();
        }
        FreeRect pr = biggestPhysicalFree(p);
        FreeRect br = biggestPhysicalFree(best);
        if (pr == null) {
            return false;
        }
        if (br == null) {
            return true;
        }
        if (Math.abs(pr.shortSide() - br.shortSide()) > 1.0) {
            return pr.shortSide() > br.shortSide();
        }
        return pr.area() > br.area() + 100.0;
    }

    private FreeRect biggestPhysicalFree(Pack pack) {
        FreeRect biggest = null;
        for (FreeRect phys : physicalFreeRects(pack)) {
            if (biggest == null || phys.area() > biggest.area()) {
                biggest = phys;
            }
        }
        return biggest;
    }

    private double biggestFreeArea(Pack pack) {
        double max = 0;
        for (FreeRect fr : pack.free) {
            max = Math.max(max, fr.area());
        }
        return max;
    }

    private double biggestFreeShort(Pack pack) {
        double max = 0;
        for (FreeRect fr : pack.free) {
            max = Math.max(max, Math.min(fr.w, fr.h));
        }
        return max;
    }

    /**
     * 条带排样时显式给出整块预留余料；全幅排样时由最大空闲矩形转换。
     * 返回的都是真实坐标（mm），可直接用于余料清单。
     */
    private List<FreeRect> physicalFreeRects(Pack pack) {
        if (pack.zoneL == sheetL && pack.zoneW == sheetW) {
            List<FreeRect> out = new ArrayList<>();
            for (FreeRect fr : pack.free) {
                FreeRect phys = toPhysical(fr, sheetL, sheetW);
                if (phys != null) {
                    out.add(phys);
                }
            }
            pruneContained(out);
            return out;
        }
        List<FreeRect> out = new ArrayList<>();
        if (pack.horizontalStrip) {
            // 右条带：左侧条带内的空闲 + 右侧整块预留带
            int bandW = sheetL - pack.zoneL;
            if (bandW > 0) {
                out.add(new FreeRect(pack.zoneL, 0, bandW, sheetW));
            }
            for (FreeRect fr : pack.free) {
                FreeRect phys = toPhysical(fr, pack.zoneL, pack.zoneW);
                if (phys != null) {
                    out.add(phys);
                }
            }
        } else {
            // 顶条带：顶部条带内的空闲 + 底部整块预留带
            int bandH = sheetW - pack.zoneW;
            if (bandH > 0) {
                out.add(new FreeRect(0, pack.zoneW, sheetL, bandH));
            }
            for (FreeRect fr : pack.free) {
                FreeRect phys = toPhysical(fr, pack.zoneL, pack.zoneW);
                if (phys != null) {
                    out.add(phys);
                }
            }
        }
        pruneContained(out);
        return out;
    }

    /** 膨胀坐标空闲矩形 → 真实坐标空闲矩形；外沿不补刀缝，boundL/W 为可用区域真实边界 */
    private FreeRect toPhysical(FreeRect fr, int boundL, int boundW) {
        double x1 = fr.x;
        double y1 = fr.y;
        double x2 = fr.x + fr.w >= boundL + kerf - EPS ? boundL : fr.x + fr.w;
        double y2 = fr.y + fr.h >= boundW + kerf - EPS ? boundW : fr.y + fr.h;
        if (x2 - x1 < 0.5 || y2 - y1 < 0.5) {
            return null;
        }
        return new FreeRect(x1, y1, x2 - x1, y2 - y1);
    }

    // ---------------- 输出装配 ----------------

    private Output finalize(Pack best, boolean timedOut) {
        Output out = new Output();

        int[] missing = neverFit.clone();
        if (best != null) {
            for (Cell cell : best.placedCells) {
                out.placements.add(new Placement(cell.x, cell.y, cell.w, cell.h,
                        cell.rowIndex, cell.pl, cell.pw, cell.rotated));
            }
            int[] placedPerRow = new int[rows.size()];
            for (int[] u : best.placedUnits) {
                placedPerRow[u[0]]++;
            }
            for (PieceDef def : rows) {
                if (neverFit[def.rowIndex] > 0) {
                    continue; // 几何放不下已记账
                }
                int remain = def.quantity - placedPerRow[def.rowIndex];
                if (remain > 0) {
                    missing[def.rowIndex] += remain;
                }
            }
            buildRemnants(best, out);
        } else if (timedOut) {
            // 预算耗尽且一个配置都没跑完：全部片按搜索超时计未排入
            for (PieceDef def : rows) {
                if (neverFit[def.rowIndex] == 0) {
                    missing[def.rowIndex] += def.quantity;
                }
            }
        }

        for (int i = 0; i < rows.size(); i++) {
            if (missing[i] <= 0) {
                continue;
            }
            String code;
            if (neverFit[i] > 0) {
                // 两个方向都超幅面，旋转也救不了
                code = "NO_ROTATION_FIT";
            } else if (!allowRotation && onlyRotatedFits[i]) {
                // 原方向放不下、只有旋转后能放，但本次规则禁止旋转
                code = "ROTATION_DISABLED";
            } else if (timedOut) {
                code = "SEARCH_TIMEOUT";
            } else {
                code = "GEOMETRY_FULL";
            }
            out.unplaced.add(new Unplaced(i, missing[i], code));
        }

        out.timedOut = timedOut;
        // 不完整以“是否存在未排入项”为准（预算耗尽且一片未排时 unplaced 也要有值）
        out.complete = out.unplaced.isEmpty();
        return out;
    }

    private void buildRemnants(Pack pack, Output out) {
        List<FreeRect> maximals = new ArrayList<>(physicalFreeRects(pack));
        maximals.sort(Comparator.comparingDouble((FreeRect r) -> -r.area())
                .thenComparingDouble(r -> r.y).thenComparingDouble(r -> r.x));
        dedupe(maximals);

        // 最大可复用整块按“最大空闲矩形”口径判定
        for (FreeRect r : maximals) {
            if (r.shortSide() >= REUSABLE_MIN_SHORT_MM - EPS
                    && r.area() >= REUSABLE_MIN_AREA_MM2 - EPS) {
                out.largestReusable = r;
                break;
            }
        }

        // 互不重叠覆盖：大块优先，已覆盖区域从后续候选中扣掉
        List<FreeRect> selected = new ArrayList<>();
        for (FreeRect r : maximals) {
            for (FreeRect piece : subtractAll(r, selected)) {
                if (piece.area() >= 1.0 && piece.w >= 0.5 && piece.h >= 0.5) {
                    selected.add(piece);
                }
            }
        }
        selected.sort(Comparator.comparingDouble((FreeRect r) -> -r.area())
                .thenComparingDouble(r -> r.y).thenComparingDouble(r -> r.x));

        double listedArea = 0;
        for (FreeRect r : selected) {
            if (r.area() < LIST_MIN_AREA_MM2) {
                continue;
            }
            boolean reusable = r.shortSide() >= REUSABLE_MIN_SHORT_MM - EPS
                    && r.area() >= REUSABLE_MIN_AREA_MM2 - EPS;
            out.remnantRects.add(r);
            out.remnantReusable.add(reusable);
            listedArea += r.area();
        }
        double placedArea = 0;
        for (Cell cell : pack.placedCells) {
            placedArea += cell.w * (double) cell.h;
        }
        double freeArea = sheetL * (double) sheetW - placedArea;
        out.kerfAndScrapMm2 = Math.max(0, freeArea - listedArea);
    }

    private void dedupe(List<FreeRect> rects) {
        for (int i = rects.size() - 1; i >= 0; i--) {
            FreeRect a = rects.get(i);
            for (int j = 0; j < i; j++) {
                FreeRect b = rects.get(j);
                if (Math.abs(b.x - a.x) < EPS && Math.abs(b.y - a.y) < EPS
                        && Math.abs(b.w - a.w) < EPS && Math.abs(b.h - a.h) < EPS) {
                    rects.remove(i);
                    break;
                }
            }
        }
    }

    /** 从 rect 中扣掉 holes 集合，返回剩余矩形（每次扣减最多 4 块） */
    private List<FreeRect> subtractAll(FreeRect rect, List<FreeRect> holes) {
        List<FreeRect> alive = new ArrayList<>();
        alive.add(rect);
        for (FreeRect hole : holes) {
            List<FreeRect> next = new ArrayList<>();
            for (FreeRect r : alive) {
                next.addAll(subtractOne(r, hole));
            }
            alive = next;
            if (alive.isEmpty()) {
                break;
            }
        }
        return alive;
    }

    private List<FreeRect> subtractOne(FreeRect r, FreeRect hole) {
        double ix1 = Math.max(r.x, hole.x);
        double iy1 = Math.max(r.y, hole.y);
        double ix2 = Math.min(r.x + r.w, hole.x + hole.w);
        double iy2 = Math.min(r.y + r.h, hole.y + hole.h);
        if (ix1 >= ix2 - EPS || iy1 >= iy2 - EPS) {
            return List.of(r); // 不相交
        }
        List<FreeRect> parts = new ArrayList<>(4);
        if (r.x < ix1 - EPS) { // 左
            parts.add(new FreeRect(r.x, r.y, ix1 - r.x, r.h));
        }
        if (r.x + r.w > ix2 + EPS) { // 右
            parts.add(new FreeRect(ix2, r.y, r.x + r.w - ix2, r.h));
        }
        if (r.y < iy1 - EPS) { // 上
            parts.add(new FreeRect(ix1, r.y, ix2 - ix1, iy1 - r.y));
        }
        if (r.y + r.h > iy2 + EPS) { // 下
            parts.add(new FreeRect(ix1, iy2, ix2 - ix1, r.y + r.h - iy2));
        }
        return parts;
    }
}
