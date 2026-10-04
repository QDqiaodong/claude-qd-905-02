package com.glass.works.service;

import com.glass.works.common.BizException;
import com.glass.works.common.Dict;
import com.glass.works.domain.CuttingOrder;
import com.glass.works.domain.CuttingPiece;
import com.glass.works.domain.GlassStock;
import com.glass.works.dto.CuttingRequests.DualPreviewResult;
import com.glass.works.dto.CuttingRequests.OrderRequest;
import com.glass.works.dto.CuttingRequests.PieceRequest;
import com.glass.works.dto.CuttingRequests.PreviewRequest;
import com.glass.works.dto.CuttingRequests.PreviewResult;
import com.glass.works.dto.CuttingRequests.Rect;
import com.glass.works.dto.CuttingRequests.Recommendation;
import com.glass.works.dto.CuttingRequests.RemnantRect;
import com.glass.works.dto.CuttingRequests.StrategyResult;
import com.glass.works.dto.CuttingRequests.UnplacedItem;
import com.glass.works.dto.RemnantRankDTO;
import com.glass.works.repository.CuttingOrderRepository;
import com.glass.works.repository.CuttingPieceRepository;
import com.glass.works.repository.GlassStockRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 切割与优化：双策略布局试算、利用率派生、余料计算与切割单生成。 */
@Service
public class CuttingService {

    // ---------------- 双策略推荐口径（固定阈值，现场可理解） ----------------
    /** 利用率差 ≤ 该百分点，且余料方案保住达标整块 → 推荐余料方案 */
    public static final double REC_GAP_NARROW_PCT = 3.0;
    /** 利用率差 ≥ 该百分点 → 直接推荐利用率方案，余料不划算 */
    public static final double REC_GAP_WIDE_PCT = 8.0;
    /** 等价带：利用率差与整块余料面积差都小于该值 → 中立 */
    public static final double REC_EQUIV_UTIL_PCT = 0.5;
    public static final double REC_EQUIV_REUSE_M2 = 0.10;
    /** 中间带（3~8pp）适配性裁决：余料方案需多保 ≥0.80㎡ 整块且短边 ≥400mm */
    public static final double REC_MID_EXTRA_REUSE_M2 = 0.80;
    public static final double REC_MID_SHORT_MM = 400.0;
    /** 推荐余料方案要求整块余料至少 0.50㎡ */
    public static final double REC_REUSE_WORTH_M2 = 0.50;

    /** 单策略默认搜索预算 ms */
    private static final int DEFAULT_TIME_LIMIT_MS = 800;
    private static final int MIN_TIME_LIMIT_MS = 50;
    private static final int MAX_TIME_LIMIT_MS = 5000;
    /** 刀缝合法上限 mm（超过基本意味着录错） */
    private static final int MAX_KERF_MM = 100;

    private static final String STRATEGY_USAGE = "USAGE";
    private static final String STRATEGY_REMNANT = "REMNANT";

    private final CuttingOrderRepository orderRepository;
    private final CuttingPieceRepository pieceRepository;
    private final GlassStockRepository stockRepository;
    private final AtomicLong calculationSeq = new AtomicLong(0);

    public CuttingService(CuttingOrderRepository orderRepository,
                          CuttingPieceRepository pieceRepository,
                          GlassStockRepository stockRepository) {
        this.orderRepository = orderRepository;
        this.pieceRepository = pieceRepository;
        this.stockRepository = stockRepository;
    }

    public List<CuttingOrder> list() {
        return orderRepository.findAll();
    }

    public Map<String, Object> detail(Long id) {
        CuttingOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BizException("切割单不存在：" + id));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("pieces", pieceRepository.findByCuttingOrderIdOrderBySortNoAscIdAsc(id));
        return result;
    }

    /** 旧版试算：不落库（刀缝 0、沿用自动旋转），保持可用 */
    public PreviewResult preview(PreviewRequest request) {
        boolean rotationLegacy = request.getAllowRotation() == null || request.getAllowRotation();
        return buildPreview(request.getStockId(), request.getSourceLengthMm(),
                request.getSourceWidthMm(), request.getPieces(), false,
                request.getKerfMm() == null ? 0 : request.getKerfMm(), rotationLegacy,
                PackingEngine.Strategy.USAGE);
    }

    /** 双策略试算：同一原片批次、同一份出片清单、同一刀缝规则下产出两份排样 */
    public DualPreviewResult dualPreview(PreviewRequest request) {
        long calculationId = calculationSeq.incrementAndGet();

        GlassStock stock = request.getStockId() == null ? null
                : stockRepository.findById(request.getStockId()).orElse(null);
        Integer lengthMm = stock != null ? stock.getLengthMm() : request.getSourceLengthMm();
        Integer widthMm = stock != null ? stock.getWidthMm() : request.getSourceWidthMm();
        if (lengthMm == null || widthMm == null || lengthMm <= 0 || widthMm <= 0) {
            throw new BizException("请先选择原片批次或填写有效的原片幅面尺寸");
        }
        int kerf = normalizeKerf(request.getKerfMm());
        boolean allowRotation = request.getAllowRotation() == null || request.getAllowRotation();
        int timeLimitMs = normalizeTimeLimit(request.getTimeLimitMs());
        List<PieceRequest> pieces = request.getPieces() == null ? List.of() : request.getPieces();

        DualPreviewResult result = new DualPreviewResult();
        result.setCalculationId(calculationId);
        result.setClientSeq(request.getClientSeq());
        result.setBatchNo(stock == null ? null : stock.getBatchNo());
        result.setGlassType(stock == null ? null : stock.getGlassType());
        result.setSourceLengthMm(lengthMm);
        result.setSourceWidthMm(widthMm);
        result.setSourceAreaSqm(round(lengthMm * widthMm / 1_000_000.0, 3));
        result.setKerfMm(kerf);
        result.setAllowRotation(allowRotation);
        result.setTimeLimitMs(timeLimitMs);
        result.setPieces(pieces);

        // 尺寸类硬校验：数字本身非法时直接 400，前端保留输入并提示可调整约束
        List<PieceRequest> validPieces = validatePieces(pieces);

        int requestedCount = 0;
        double requestedArea = 0;
        for (PieceRequest piece : validPieces) {
            requestedCount += piece.getQuantity();
            requestedArea += piece.getLengthMm() * piece.getWidthMm()
                    * piece.getQuantity() / 1_000_000.0;
        }
        result.setRequestedCount(requestedCount);
        result.setRequestedAreaSqm(round(requestedArea, 3));

        if (validPieces.isEmpty()) {
            result.setUsage(buildEmptyStrategy(STRATEGY_USAGE, lengthMm, widthMm, kerf));
            result.setRemnant(buildEmptyStrategy(STRATEGY_REMNANT, lengthMm, widthMm, kerf));
            result.setRecommendation(neutralRec("暂无出片清单，放入规格后同时给出两份排样"));
            return result;
        }

        List<PackingEngine.PieceDef> rows = new ArrayList<>();
        for (int i = 0; i < validPieces.size(); i++) {
            PieceRequest p = validPieces.get(i);
            rows.add(new PackingEngine.PieceDef(i, p.getLengthMm(), p.getWidthMm(), p.getQuantity()));
        }

        double sourceArea = lengthMm * widthMm / 1_000_000.0;
        boolean areaOverflow = requestedArea > sourceArea + 1e-9;

        // 两个策略各享一份独立搜索预算，避免后算的策略被前一个挤占时间
        PackingEngine usageEngine = new PackingEngine(lengthMm, widthMm, kerf, allowRotation,
                System.nanoTime() + timeLimitMs * 1_000_000L);
        PackingEngine.Output usageOut = usageEngine.run(PackingEngine.Strategy.USAGE, rows);
        PackingEngine remnantEngine = new PackingEngine(lengthMm, widthMm, kerf, allowRotation,
                System.nanoTime() + timeLimitMs * 1_000_000L);
        PackingEngine.Output remnantOut = remnantEngine.run(PackingEngine.Strategy.REMNANT, rows);

        StrategyResult usage = toStrategy(STRATEGY_USAGE, lengthMm, widthMm, kerf,
                validPieces, requestedCount, requestedArea, usageOut);
        StrategyResult remnant = toStrategy(STRATEGY_REMNANT, lengthMm, widthMm, kerf,
                validPieces, requestedCount, requestedArea, remnantOut);
        decorateReasons(usage, validPieces, lengthMm, widthMm, kerf, allowRotation, areaOverflow);
        decorateReasons(remnant, validPieces, lengthMm, widthMm, kerf, allowRotation, areaOverflow);
        result.setUsage(usage);
        result.setRemnant(remnant);

        if (areaOverflow) {
            double over = requestedArea - sourceArea;
            result.setRecommendation(neutralRec("出片面积合计 " + round(requestedArea, 3) + "㎡ 已超原片 "
                    + round(sourceArea, 3) + "㎡（超 " + round(over, 3) + "㎡），两份排样都不可能排齐："
                    + "请减少出片数量或换用更大幅面原片，本结果不出推荐"));
        } else {
            result.setRecommendation(buildRecommendation(usage, remnant));
        }
        return result;
    }

    @Transactional
    public CuttingOrder create(OrderRequest request) {
        GlassStock stock = stockRepository.findById(request.getStockId())
                .orElseThrow(() -> new BizException("原片批次不存在：" + request.getStockId()));
        if (request.getPieces() == null || request.getPieces().isEmpty()) {
            throw new BizException("出片规格清单不能为空");
        }
        int kerf = normalizeKerf(request.getKerfMm());
        boolean allowRotation = request.getAllowRotation() == null || request.getAllowRotation();
        PackingEngine.Strategy strategy = STRATEGY_REMNANT.equals(request.getStrategy())
                ? PackingEngine.Strategy.REMNANT : PackingEngine.Strategy.USAGE;
        PreviewResult preview = buildPreview(stock.getId(), stock.getLengthMm(), stock.getWidthMm(),
                request.getPieces(), true, kerf, allowRotation, strategy);
        if (preview.isOverflow()) {
            throw new BizException(preview.getMessage());
        }

        String orderNo = nextOrderNo();
        CuttingOrder order = new CuttingOrder();
        order.setOrderNo(orderNo);
        order.setStockId(stock.getId());
        order.setBatchNo(stock.getBatchNo());
        order.setGlassType(stock.getGlassType());
        order.setLayoutNo(StringUtils.hasText(request.getLayoutNo())
                ? request.getLayoutNo() : "LAY-" + orderNo.substring(orderNo.length() - 4));
        order.setOutputCount(preview.getOutputCount());
        order.setSourceAreaSqm(bd(preview.getSourceAreaSqm(), 3));
        order.setOutputAreaSqm(bd(preview.getOutputAreaSqm(), 3));
        order.setRemnantAreaSqm(bd(preview.getRemnantAreaSqm(), 3));
        order.setRemnantSpec(preview.getRemnantSpec());
        order.setCutter(request.getCutter());
        order.setOperator(request.getOperator());
        order.setStrategy(strategy == PackingEngine.Strategy.REMNANT ? STRATEGY_REMNANT : STRATEGY_USAGE);
        order.setKerfMm(kerf);
        order.setAllowRotation(allowRotation);
        order.setStatus(Dict.CUT_OPTIMIZED);
        order.setCreatedAt(LocalDateTime.now());
        // 利用率是派生列：setter 会做 0~100% 边界校验
        order.setUtilizationRate(bd(preview.getUtilizationRate(), 2));
        CuttingOrder saved = orderRepository.save(order);

        int sortNo = 1;
        for (PieceRequest piece : request.getPieces()) {
            CuttingPiece entity = new CuttingPiece();
            entity.setCuttingOrderId(saved.getId());
            entity.setSortNo(sortNo++);
            entity.setLengthMm(piece.getLengthMm());
            entity.setWidthMm(piece.getWidthMm());
            entity.setQuantity(piece.getQuantity());
            entity.setRemark(piece.getRemark());
            entity.setAreaSqm(BigDecimal.valueOf(
                    piece.getLengthMm() * piece.getWidthMm() * piece.getQuantity() / 1_000_000.0)
                    .setScale(4, RoundingMode.HALF_UP));
            pieceRepository.save(entity);
        }
        return saved;
    }

    @Transactional
    public CuttingOrder updateStatus(Long id, String status) {
        if (!Dict.isValidCutStatus(status)) {
            throw new BizException("切割单状态非法：" + status);
        }
        CuttingOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BizException("切割单不存在：" + id));
        order.setStatus(status);
        return orderRepository.save(order);
    }

    /** 原片余料排行（手写 JPQL） */
    public List<RemnantRankDTO> remnantRank() {
        return orderRepository.remnantRank();
    }

    @Transactional
    public void delete(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new BizException("切割单不存在：" + id);
        }
        pieceRepository.deleteByCuttingOrderId(id);
        orderRepository.deleteById(id);
    }

    // ---------------- 双策略装配 ----------------

    private StrategyResult buildEmptyStrategy(String strategy, int lengthMm, int widthMm, int kerf) {
        StrategyResult sr = baseStrategy(strategy);
        sr.setComplete(true);
        sr.setRequestedCount(0);
        sr.setPlacedCount(0);
        RemnantRect whole = remnant(new PackingEngine.FreeRect(0, 0, lengthMm, widthMm), lengthMm, widthMm);
        sr.getRemnants().add(whole);
        sr.getLayout().add(new Rect(0, 0, lengthMm, widthMm, "REMNANT",
                "整版余料 " + whole.getSpec(), null));
        sr.setRemnantAreaSqm(round(lengthMm * widthMm / 1_000_000.0, 3));
        sr.setReusableAreaSqm(whole.isReusable() ? whole.getAreaSqm() : 0.0);
        sr.setReusableMinSideMm(whole.isReusable() ? Math.min(lengthMm, widthMm) : 0.0);
        sr.setRemnantSpec(whole.isReusable() ? whole.getSpec() : "0×0");
        sr.setUtilizationRate(0.0);
        sr.setUtilizationLevel("LOW");
        sr.setMessage("暂无出片，整版均可复用");
        return sr;
    }

    private StrategyResult toStrategy(String strategy, int lengthMm, int widthMm, int kerf,
                                      List<PieceRequest> pieces, int requestedCount,
                                      double requestedArea, PackingEngine.Output out) {
        StrategyResult sr = baseStrategy(strategy);
        sr.setRequestedCount(requestedCount);
        sr.setRequestedAreaSqm(round(requestedArea, 3));
        sr.setPlacedCount(out.placements.size());

        double placedArea = 0;
        for (PackingEngine.Placement p : out.placements) {
            placedArea += p.w * p.h / 1_000_000.0;
            sr.getLayout().add(new Rect(round3(p.x), round3(p.y), round3(p.w), round3(p.h),
                    "PIECE", fmt(p.w) + "×" + fmt(p.h) + (p.rotated ? "（旋）" : ""), 1,
                    (double) p.pieceLengthMm, (double) p.pieceWidthMm, p.rotated));
        }
        for (int i = 0; i < out.remnantRects.size(); i++) {
            PackingEngine.FreeRect r = out.remnantRects.get(i);
            boolean reusable = out.remnantReusable.get(i);
            RemnantRect rr = remnant(r, lengthMm, widthMm);
            sr.getRemnants().add(rr);
            Rect rect = new Rect(round3(r.x), round3(r.y), round3(r.w), round3(r.h),
                    "REMNANT", "余料 " + rr.getSpec() + (reusable ? "（整块可复用）" : ""), null);
            rect.setReusable(reusable);
            sr.getLayout().add(rect);
        }

        double sourceArea = lengthMm * widthMm / 1_000_000.0;
        double rate = Math.min(100.0, placedArea / sourceArea * 100.0);
        sr.setPlacedAreaSqm(round(placedArea, 3));
        sr.setUtilizationRate(round(rate, 2));
        sr.setUtilizationLevel(rate < Dict.UTIL_WARN ? "LOW" : rate > Dict.UTIL_GOOD ? "HIGH" : "MID");
        sr.setRemnantAreaSqm(round(Math.max(0, sourceArea - placedArea), 3));
        sr.setKerfAreaSqm(round(out.kerfAndScrapMm2 / 1_000_000.0, 3));
        sr.setTimedOut(out.timedOut);
        sr.setComplete(out.complete);

        for (PackingEngine.Unplaced u : out.unplaced) {
            PieceRequest p = pieces.get(u.rowIndex);
            UnplacedItem item = new UnplacedItem();
            item.setLengthMm(p.getLengthMm());
            item.setWidthMm(p.getWidthMm());
            item.setMissingQuantity(u.missing);
            item.setReasonCode(u.reasonCode);
            sr.getUnplaced().add(item);
        }

        if (out.largestReusable != null) {
            PackingEngine.FreeRect r = out.largestReusable;
            sr.setReusableAreaSqm(round(r.area() / 1_000_000.0, 3));
            sr.setReusableMinSideMm(round3(r.shortSide()));
            sr.setRemnantSpec(fmt(r.w) + "×" + fmt(r.h));
        } else {
            sr.setReusableAreaSqm(0.0);
            sr.setReusableMinSideMm(0.0);
            sr.setRemnantSpec("0×0");
        }
        return sr;
    }

    private RemnantRect remnant(PackingEngine.FreeRect r, int lengthMm, int widthMm) {
        RemnantRect rr = new RemnantRect();
        rr.setX(round3(r.x));
        rr.setY(round3(r.y));
        rr.setW(round3(r.w));
        rr.setH(round3(r.h));
        rr.setSpec(fmt(r.w) + "×" + fmt(r.h));
        rr.setAreaSqm(round(r.w * r.h / 1_000_000.0, 3));
        boolean reusable = r.shortSide() >= PackingEngine.REUSABLE_MIN_SHORT_MM - 1e-6
                && r.area() >= PackingEngine.REUSABLE_MIN_AREA_MM2 - 1e-6;
        rr.setReusable(reusable);
        if (!reusable) {
            if (r.shortSide() < PackingEngine.REUSABLE_MIN_SHORT_MM - 1e-6) {
                rr.setNote("短边 " + fmt(r.shortSide()) + "mm < "
                        + (int) PackingEngine.REUSABLE_MIN_SHORT_MM + "mm，只能做小件或作废");
            } else {
                rr.setNote("面积 " + rr.getAreaSqm() + "㎡ < 0.30㎡，碎料不计可复用整块");
            }
        }
        return rr;
    }

    /** 未排入原因的现场可读说明（含可调整约束） */
    private void decorateReasons(StrategyResult sr, List<PieceRequest> pieces,
                                 int lengthMm, int widthMm, int kerf,
                                 boolean allowRotation, boolean areaOverflow) {
        for (UnplacedItem item : sr.getUnplaced()) {
            String spec = item.getLengthMm() + "×" + item.getWidthMm();
            switch (item.getReasonCode()) {
                case "NO_ROTATION_FIT":
                    item.setReason("规格 " + spec + "mm 旋转90°后仍超出原片 " + lengthMm + "×" + widthMm
                            + "mm（刀缝 " + kerf + "mm）。可调整：改小该规格或换更大幅面原片");
                    break;
                case "ROTATION_DISABLED":
                    item.setReason("规格 " + spec + "mm 原方向放不下，旋转90°后可入版，"
                            + "但当前规则禁止旋转。可调整：开启允许旋转，或改小规格 / 换更大原片");
                    break;
                case "SEARCH_TIMEOUT":
                    item.setReason("规格 " + spec + "mm 有 " + item.getMissingQuantity()
                            + " 片在限定搜索时间内未找到落位（不代表一定放不下）。"
                            + "可调整：稍后重试/放宽搜索时限、减少该规格数量"
                            + (allowRotation ? "" : "或开启旋转"));
                    break;
                default:
                    String extra = areaOverflow
                            ? "出片总面积已超原片面积，请减少数量或换更大原片；"
                            : "剩余空位几何上放不下该规格（总面积够也不算排入）；";
                    item.setReason("规格 " + spec + "mm 有 " + item.getMissingQuantity()
                            + " 片未排入：" + extra + "也可调整刀缝（当前 " + kerf + "mm）"
                            + (allowRotation ? "" : "或开启旋转"));
            }
        }
        StringBuilder msg = new StringBuilder();
        if (sr.isComplete()) {
            msg.append(sr.getPlacedCount()).append(" 片全部排入，利用率 ")
                    .append(sr.getUtilizationRate()).append("%");
            if (!"0×0".equals(sr.getRemnantSpec())) {
                msg.append("，最大整块可复用余料 ").append(sr.getRemnantSpec()).append("mm");
            } else {
                msg.append("，无达门槛整块余料（短边≥300mm 且 ≥0.30㎡）");
            }
        } else {
            msg.append("未排入 ").append(sr.getRequestedCount() - sr.getPlacedCount())
                    .append(" 片，已按真实几何（含刀缝）判定，未按总面积凑成功；");
            if (sr.isTimedOut()) {
                msg.append("搜索超时，结果为当前找到的最优排法；");
            }
            msg.append("详见未排入清单。");
        }
        sr.setMessage(msg.toString());
    }

    private StrategyResult baseStrategy(String strategy) {
        StrategyResult sr = new StrategyResult();
        sr.setStrategy(strategy);
        if (STRATEGY_USAGE.equals(strategy)) {
            sr.setStrategyName("利用率优先");
            sr.setStrategyDesc("以排齐全部出片、抬升本片利用率为首要目标，余料可能被切散");
        } else {
            sr.setStrategyName("整块余料优先");
            sr.setStrategyDesc("先在最窄条带内排齐出片，尽量保住一整块可二次套裁的余料");
        }
        return sr;
    }

    // ---------------- 推荐口径 ----------------

    private Recommendation buildRecommendation(StrategyResult usage, StrategyResult remnant) {
        Recommendation rec = new Recommendation();
        rec.setUtilizationGapPct(round(Math.abs(usage.getUtilizationRate() - remnant.getUtilizationRate()), 2));
        rec.setReusableGapSqm(round(Math.abs(usage.getReusableAreaSqm() - remnant.getReusableAreaSqm()), 3));
        rec.setPolicy("固定阈值带：①利用率差≤" + fmtPct(REC_GAP_NARROW_PCT)
                + "且余料方案保住≥" + fmt2(REC_REUSE_WORTH_M2) + "㎡整块→选余料方案；"
                + "②利用率差≥" + fmtPct(REC_GAP_WIDE_PCT) + "→选利用率方案；"
                + "③中间带看适配性：余料方案需多保≥" + fmt2(REC_MID_EXTRA_REUSE_M2)
                + "㎡整块且短边≥" + (int) REC_MID_SHORT_MM + "mm；"
                + "④两份均未排齐或差异在等价带（利用率<" + fmtPct(REC_EQUIV_UTIL_PCT)
                + "、整块差<" + fmt2(REC_EQUIV_REUSE_M2) + "㎡）内→中立不推荐。");

        if (!usage.isComplete() && !remnant.isComplete()) {
            return neutral(rec, "两份排样都未排齐全部必需出片（"
                    + unplacedSummary(usage, remnant) + "），不出推荐，请按未排入清单调整约束后重算");
        }
        if (!usage.isComplete() || !remnant.isComplete()) {
            StrategyResult only = usage.isComplete() ? usage : remnant;
            rec.setChoice(only.getStrategy());
            rec.setChoiceName(only.getStrategyName());
            rec.setReason("只有「" + only.getStrategyName() + "」能排入全部 "
                    + only.getRequestedCount() + " 片必需出片，另一份有未排入项，故选它");
            return rec;
        }

        double gap = usage.getUtilizationRate() - remnant.getUtilizationRate();
        double utilAbs = Math.abs(gap);
        double reuseExtra = remnant.getReusableAreaSqm() - usage.getReusableAreaSqm();
        boolean remnantWorth = remnant.getReusableAreaSqm() >= REC_REUSE_WORTH_M2 - 1e-9
                && remnant.getReusableMinSideMm() >= PackingEngine.REUSABLE_MIN_SHORT_MM;

        // 等价带：保持中立
        if (utilAbs < REC_EQUIV_UTIL_PCT && Math.abs(reuseExtra) < REC_EQUIV_REUSE_M2) {
            return neutral(rec, "两份排样利用率与整块余料均在等价带内（利用率差 "
                    + rec.getUtilizationGapPct() + " 个百分点、整块余料差 "
                    + rec.getReusableGapSqm() + "㎡），效果等价，现场任选");
        }
        // 窄差带：利用率几乎不吃亏，且余料方案保住值钱整块 → 余料
        if (gap <= REC_GAP_NARROW_PCT + 1e-9 && remnantWorth && reuseExtra > -REC_EQUIV_REUSE_M2) {
            rec.setChoice(STRATEGY_REMNANT);
            rec.setChoiceName(remnant.getStrategyName());
            rec.setReason("利用率只差 " + rec.getUtilizationGapPct() + " 个百分点（≤"
                    + fmtPct(REC_GAP_NARROW_PCT) + "），余料方案保住 "
                    + fmt2(remnant.getReusableAreaSqm()) + "㎡ 整块余料（"
                    + remnant.getRemnantSpec() + "mm，短边 " + fmt(remnant.getReusableMinSideMm())
                    + "mm），后续套裁更值");
            return rec;
        }
        // 宽差带：利用率差距太大 → 利用率
        if (gap >= REC_GAP_WIDE_PCT - 1e-9) {
            rec.setChoice(STRATEGY_USAGE);
            rec.setChoiceName(usage.getStrategyName());
            rec.setReason("利用率高出 " + rec.getUtilizationGapPct() + " 个百分点（≥"
                    + fmtPct(REC_GAP_WIDE_PCT) + "），多保的余料（"
                    + fmt2(Math.max(0, reuseExtra)) + "㎡）补不回本片损耗，选利用率方案");
            return rec;
        }
        // 中间带：综合余料形状（面积 + 短边）裁决
        if (gap > REC_GAP_NARROW_PCT && gap < REC_GAP_WIDE_PCT
                && remnantWorth
                && reuseExtra >= REC_MID_EXTRA_REUSE_M2 - 1e-9
                && remnant.getReusableMinSideMm() >= REC_MID_SHORT_MM) {
            rec.setChoice(STRATEGY_REMNANT);
            rec.setChoiceName(remnant.getStrategyName());
            rec.setReason("利用率差 " + rec.getUtilizationGapPct() + " 个百分点处于中间带，"
                    + "但余料方案多保 " + fmt2(reuseExtra) + "㎡ 整块、短边 "
                    + fmt(remnant.getReusableMinSideMm()) + "mm（" + remnant.getRemnantSpec()
                    + "mm），适配后续套裁，选余料方案");
            return rec;
        }
        rec.setChoice(STRATEGY_USAGE);
        rec.setChoiceName(usage.getStrategyName());
        rec.setReason("利用率高 " + rec.getUtilizationGapPct() + " 个百分点，且余料方案未保住足够大的整块"
                + "（多保 " + fmt2(Math.max(0, reuseExtra)) + "㎡、短边 "
                + fmt(remnant.getReusableMinSideMm()) + "mm，未达适配门槛），选利用率方案");
        return rec;
    }

    private Recommendation neutral(Recommendation rec, String reason) {
        rec.setChoice("NONE");
        rec.setChoiceName("中立");
        rec.setReason(reason);
        return rec;
    }

    private Recommendation neutralRec(String reason) {
        Recommendation rec = new Recommendation();
        rec.setChoice("NONE");
        rec.setChoiceName("中立");
        rec.setReason(reason);
        return rec;
    }

    private String unplacedSummary(StrategyResult a, StrategyResult b) {
        int ua = a.getRequestedCount() - a.getPlacedCount();
        int ub = b.getRequestedCount() - b.getPlacedCount();
        return "利用率方案缺 " + ua + " 片、余料方案缺 " + ub + " 片";
    }

    // ---------------- 旧版单策略试算（新引擎几何口径） ----------------

    private PreviewResult buildPreview(Long stockId, Integer sourceLength, Integer sourceWidth,
                                       List<PieceRequest> pieces, boolean strict,
                                       int kerf, boolean allowRotation,
                                       PackingEngine.Strategy strategy) {
        GlassStock stock = null;
        if (stockId != null) {
            stock = stockRepository.findById(stockId).orElse(null);
        }
        Integer lengthMm = stock != null ? stock.getLengthMm() : sourceLength;
        Integer widthMm = stock != null ? stock.getWidthMm() : sourceWidth;
        if (lengthMm == null || widthMm == null || lengthMm <= 0 || widthMm <= 0) {
            throw new BizException("请先选择原片批次或填写原片幅面尺寸");
        }

        PreviewResult result = new PreviewResult();
        result.setBatchNo(stock == null ? null : stock.getBatchNo());
        result.setGlassType(stock == null ? null : stock.getGlassType());
        result.setSourceLengthMm(lengthMm);
        result.setSourceWidthMm(widthMm);
        result.setPieces(pieces == null ? new ArrayList<>() : pieces);

        double sourceArea = lengthMm * widthMm / 1_000_000.0;
        result.setSourceAreaSqm(round(sourceArea, 3));

        if (pieces == null || pieces.isEmpty()) {
            result.setRemnantAreaSqm(round(sourceArea, 3));
            result.setRemnantSpec(lengthMm + "×" + widthMm);
            result.getLayout().add(new Rect(0, 0, lengthMm, widthMm, "REMNANT", "整版余料", null));
            return result;
        }

        List<PieceRequest> validPieces = validatePieces(pieces);

        // 单片几何校验：与双策略同一刀缝 / 旋转口径
        List<PackingEngine.PieceDef> rows = new ArrayList<>();
        for (int i = 0; i < validPieces.size(); i++) {
            PieceRequest p = validPieces.get(i);
            boolean plain = p.getLengthMm() <= lengthMm && p.getWidthMm() <= widthMm;
            boolean rotated = allowRotation
                    && p.getWidthMm() <= lengthMm && p.getLengthMm() <= widthMm;
            if (!plain && !rotated) {
                String msg = "出片规格 " + p.getLengthMm() + "×" + p.getWidthMm()
                        + "mm" + (allowRotation ? " 旋转90°后" : "") + "仍超出原片幅面 "
                        + lengthMm + "×" + widthMm + "mm（刀缝 " + kerf + "mm），"
                        + "请改小规格、换更大原片" + (allowRotation ? "" : "或开启旋转");
                if (strict) {
                    throw new BizException(msg);
                }
                result.setOverflow(true);
                result.setMessage(msg);
                result.setUtilizationRate(0.0);
                result.setUtilizationLevel("LOW");
                result.setRemnantAreaSqm(0.0);
                result.setRemnantSpec("0×0");
                return result;
            }
            rows.add(new PackingEngine.PieceDef(i, p.getLengthMm(), p.getWidthMm(), p.getQuantity()));
        }

        double outputArea = 0;
        int outputCount = 0;
        for (PieceRequest p : validPieces) {
            outputArea += p.getLengthMm() * p.getWidthMm() * p.getQuantity() / 1_000_000.0;
            outputCount += p.getQuantity();
        }
        result.setOutputAreaSqm(round(outputArea, 3));
        result.setOutputCount(outputCount);
        if (outputArea > sourceArea + 1e-9) {
            double over = outputArea - sourceArea;
            String message = "出片面积合计 " + round(outputArea, 3) + "㎡ 超出原片面积 "
                    + round(sourceArea, 3) + "㎡，超出 " + round(over, 3) + "㎡，请减少出片或换用更大原片";
            result.setOverflow(true);
            result.setMessage(message);
            result.setUtilizationRate(round(outputArea / sourceArea * 100.0, 2));
            result.setUtilizationLevel("LOW");
            result.setRemnantAreaSqm(0.0);
            result.setRemnantSpec("0×0");
            if (strict) {
                throw new BizException(message);
            }
            return result;
        }

        long deadline = System.nanoTime() + DEFAULT_TIME_LIMIT_MS * 1_000_000L;
        PackingEngine engine = new PackingEngine(lengthMm, widthMm, kerf, allowRotation, deadline);
        PackingEngine.Output out = engine.run(strategy, rows);

        double placedArea = 0;
        for (PackingEngine.Placement p : out.placements) {
            placedArea += p.w * p.h / 1_000_000.0;
            result.getLayout().add(new Rect(round3(p.x), round3(p.y), round3(p.w), round3(p.h),
                    "PIECE", fmt(p.w) + "×" + fmt(p.h) + (p.rotated ? "（旋）" : ""), 1,
                    (double) p.pieceLengthMm, (double) p.pieceWidthMm, p.rotated));
        }
        for (int i = 0; i < out.remnantRects.size(); i++) {
            PackingEngine.FreeRect r = out.remnantRects.get(i);
            boolean reusable = out.remnantReusable.get(i);
            Rect rect = new Rect(round3(r.x), round3(r.y), round3(r.w), round3(r.h),
                    "REMNANT", "余料 " + fmt(r.w) + "×" + fmt(r.h)
                            + (reusable ? "（整块可复用）" : ""), null);
            rect.setReusable(reusable);
            result.getLayout().add(rect);
        }

        double rate = Math.min(100.0, placedArea / sourceArea * 100.0);
        result.setUtilizationRate(round(rate, 2));
        result.setUtilizationLevel(rate < Dict.UTIL_WARN ? "LOW" : rate > Dict.UTIL_GOOD ? "HIGH" : "MID");
        result.setRemnantAreaSqm(round(sourceArea - placedArea, 3));
        result.setRemnantSpec(out.largestReusable == null
                ? "0×0" : fmt(out.largestReusable.w) + "×" + fmt(out.largestReusable.h));

        if (!out.complete) {
            StringBuilder sb = new StringBuilder();
            sb.append(out.timedOut ? "搜索超时：" : "当前排布在原片幅面内放不下全部出片（真实几何判定，非面积判定）：");
            for (PackingEngine.Unplaced u : out.unplaced) {
                PieceRequest p = validPieces.get(u.rowIndex);
                sb.append(p.getLengthMm()).append("×").append(p.getWidthMm())
                        .append("mm 缺 ").append(u.missing).append(" 片；");
            }
            sb.append("请减少数量、换更大原片").append(allowRotation ? "" : "或开启旋转");
            result.setOverflow(true);
            result.setMessage(sb.toString());
            if (strict) {
                throw new BizException(result.getMessage());
            }
        } else {
            result.setMessage("利用率 " + result.getUtilizationRate() + "%，余料 "
                    + result.getRemnantSpec() + "mm，刀缝 " + kerf + "mm");
        }
        return result;
    }

    // ---------------- 校验与工具 ----------------

    private List<PieceRequest> validatePieces(List<PieceRequest> pieces) {
        List<PieceRequest> valid = new ArrayList<>();
        if (pieces == null) {
            return valid;
        }
        for (PieceRequest piece : pieces) {
            if (piece == null) {
                continue;
            }
            if (piece.getLengthMm() == null || piece.getWidthMm() == null
                    || piece.getLengthMm() <= 0 || piece.getWidthMm() <= 0) {
                throw new BizException("出片规格长宽必须为正整数（mm），请修正标红行后重算，已填写内容不会丢失");
            }
            if (piece.getQuantity() == null || piece.getQuantity() <= 0) {
                throw new BizException("出片数量必须为正整数，请修正标红行后重算，已填写内容不会丢失");
            }
            valid.add(piece);
        }
        return valid;
    }

    private int normalizeKerf(Integer kerfMm) {
        if (kerfMm == null) {
            return 0;
        }
        if (kerfMm < 0 || kerfMm > MAX_KERF_MM) {
            throw new BizException("刀缝宽度需在 0~" + MAX_KERF_MM + "mm 之间，当前 "
                    + kerfMm + "mm，请检查录入");
        }
        return kerfMm;
    }

    private int normalizeTimeLimit(Integer ms) {
        if (ms == null) {
            return DEFAULT_TIME_LIMIT_MS;
        }
        return Math.max(MIN_TIME_LIMIT_MS, Math.min(MAX_TIME_LIMIT_MS, ms));
    }

    private String nextOrderNo() {
        String prefix = "CUT" + LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = orderRepository.count() + 1;
        String orderNo = prefix + String.format("-%03d", seq);
        while (orderRepository.existsByOrderNo(orderNo)) {
            seq++;
            orderNo = prefix + String.format("-%03d", seq);
        }
        return orderNo;
    }

    private static BigDecimal bd(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String fmtPct(double v) {
        return fmt(v) + "个百分点";
    }

    private static String fmt2(double v) {
        return String.valueOf(round(v, 2));
    }

    /** 尺寸展示：整数不带小数，带小数的保留一位 */
    private static String fmt(double value) {
        double v = round1(value);
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
