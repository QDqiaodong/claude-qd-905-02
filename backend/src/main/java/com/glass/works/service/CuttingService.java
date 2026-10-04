package com.glass.works.service;

import com.glass.works.common.BizException;
import com.glass.works.common.Dict;
import com.glass.works.domain.CuttingOrder;
import com.glass.works.domain.CuttingPiece;
import com.glass.works.domain.GlassStock;
import com.glass.works.dto.CuttingRequests.OrderRequest;
import com.glass.works.dto.CuttingRequests.PieceRequest;
import com.glass.works.dto.CuttingRequests.PreviewRequest;
import com.glass.works.dto.CuttingRequests.PreviewResult;
import com.glass.works.dto.CuttingRequests.Rect;
import com.glass.works.dto.CuttingRequests.Recommendation;
import com.glass.works.dto.CuttingRequests.StrategyResult;
import com.glass.works.dto.CuttingRequests.UnplacedItem;
import com.glass.works.dto.RemnantRankDTO;
import com.glass.works.repository.CuttingOrderRepository;
import com.glass.works.repository.CuttingPieceRepository;
import com.glass.works.repository.GlassStockRepository;
import com.glass.works.service.PackingEngine.Grid;
import com.glass.works.service.PackingEngine.Placed;
import com.glass.works.service.PackingEngine.RawRun;
import com.glass.works.service.PackingEngine.Unit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 切割与优化：双策略真实几何排样（利用率优先 / 整块余料优先）、
 * 刀缝感知的面积派生、可复用余料矩形、未排入项原因，以及切割单生成。
 *
 * <p>两份结果始终基于<b>同一原片批次（幅面）、同一份出片清单、同一刀缝规则</b>，
 * 只差排样策略；任何一份都以真实几何放入片数判定成功，总面积足够不构成成功。</p>
 */
@Service
public class CuttingService {

    private final CuttingOrderRepository orderRepository;
    private final CuttingPieceRepository pieceRepository;
    private final GlassStockRepository stockRepository;

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

    public java.util.Map<String, Object> detail(Long id) {
        CuttingOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BizException("切割单不存在：" + id));
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("order", order);
        result.put("pieces", pieceRepository.findByCuttingOrderIdOrderBySortNoAscIdAsc(id));
        return result;
    }

    /** 双策略试算：不落库，返回两份布局、余料矩形、未排入项与推荐口径 */
    public PreviewResult preview(PreviewRequest request) {
        return buildPreview(request.getStockId(), request.getSourceLengthMm(),
                request.getSourceWidthMm(), request.getPieces(),
                resolveKerf(request.getKerfMm()), resolveGlobalRotation(request.getAllowRotation()),
                false, null);
    }

    @Transactional
    public CuttingOrder create(OrderRequest request) {
        GlassStock stock = stockRepository.findById(request.getStockId())
                .orElseThrow(() -> new BizException("原片批次不存在：" + request.getStockId()));
        if (request.getPieces() == null || request.getPieces().isEmpty()) {
            throw new BizException("出片规格清单不能为空");
        }
        int kerf = resolveKerf(request.getKerfMm());
        PreviewResult preview = buildPreview(stock.getId(), stock.getLengthMm(), stock.getWidthMm(),
                request.getPieces(), kerf, true, true, request.getStrategy());

        String chosen = resolveChosenStrategy(request.getStrategy(), preview);
        StrategyResult plan = Dict.PACK_REMNANT.equals(chosen)
                ? preview.getRemnant() : preview.getUtilization();
        if (plan.isLimited() || plan.getUnplacedCount() > 0) {
            // 下刀不允许带未排入项 / 不完整结果
            throw new BizException("所选「" + Dict.packStrategyName(chosen) + "」方案存在 "
                    + plan.getUnplacedCount() + " 片未排入" + (plan.isLimited() ? "（搜索超时）" : "")
                    + "，不能生成切割单：请调整数量、规格、刀缝或改选放全的方案");
        }

        String orderNo = nextOrderNo();
        CuttingOrder order = new CuttingOrder();
        order.setOrderNo(orderNo);
        order.setStockId(stock.getId());
        order.setBatchNo(stock.getBatchNo());
        order.setGlassType(stock.getGlassType());
        order.setLayoutNo(StringUtils.hasText(request.getLayoutNo())
                ? request.getLayoutNo() : "LAY-" + orderNo.substring(orderNo.length() - 4));
        order.setPackStrategy(chosen);
        order.setStrategyName(Dict.packStrategyName(chosen));
        order.setKerfMm(kerf);
        order.setOutputCount(plan.getPlacedCount());
        order.setSourceAreaSqm(bd(preview.getSourceAreaSqm(), 3));
        order.setOutputAreaSqm(bd(plan.getPieceAreaSqm(), 3));
        order.setRemnantAreaSqm(bd(plan.getRemnantAreaSqm(), 3));
        order.setReusableAreaSqm(bd(plan.getReusableAreaSqm(), 3));
        order.setKerfAreaSqm(bd(plan.getKerfAreaSqm(), 3));
        order.setEffectiveRate(bd(plan.getEffectiveRate(), 2));
        order.setRemnantSpec(plan.getRemnantSpec());
        order.setCutter(request.getCutter());
        order.setOperator(request.getOperator());
        order.setStatus(Dict.CUT_OPTIMIZED);
        order.setCreatedAt(LocalDateTime.now());
        // 名义利用率是派生列：setter 会做 0~100% 边界校验
        order.setUtilizationRate(bd(plan.getUtilizationRate(), 2));
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
            entity.setAllowRotation(piece.getAllowRotation() == null || piece.getAllowRotation() ? 1 : 0);
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

    // ---------------- 核心：双策略试算 ----------------

    private PreviewResult buildPreview(Long stockId, Integer sourceLength, Integer sourceWidth,
                                       List<PieceRequest> pieces, int kerf, boolean globalRotation,
                                       boolean strict, String requestedStrategy) {
        GlassStock stock = null;
        if (stockId != null) {
            stock = stockRepository.findById(stockId).orElse(null);
        }
        Integer lengthMm = stock != null ? stock.getLengthMm() : sourceLength;
        Integer widthMm = stock != null ? stock.getWidthMm() : sourceWidth;
        if (lengthMm == null || widthMm == null || lengthMm <= 0 || widthMm <= 0) {
            throw new BizException("请先选择原片批次或填写有效的原片幅面尺寸（长宽必须为正整数）");
        }

        PreviewResult result = new PreviewResult();
        result.setBatchNo(stock == null ? null : stock.getBatchNo());
        result.setGlassType(stock == null ? null : stock.getGlassType());
        result.setSourceLengthMm(lengthMm);
        result.setSourceWidthMm(widthMm);
        result.setSourceAreaSqm(round(lengthMm.doubleValue() * widthMm.doubleValue() / 1_000_000.0, 3));
        result.setKerfMm(kerf);
        result.setAllowRotation(globalRotation);
        result.setPieces(pieces == null ? new ArrayList<>() : pieces);

        if (pieces == null || pieces.isEmpty()) {
            // 空清单：整版都是可复用余料，两策略等价
            result.setOutputCount(0);
            result.setOutputAreaSqm(0.0);
            result.setUtilization(emptyStrategy(Dict.PACK_UTILIZATION, "利用率优先",
                    lengthMm, widthMm, kerf));
            result.setRemnant(emptyStrategy(Dict.PACK_REMNANT, "整块余料优先",
                    lengthMm, widthMm, kerf));
            result.setRecommendation(neutral("尚未录入出片，整版为原片余料，两策略等价"));
            return result;
        }

        // 1) 规格级校验 + 展开单元（全局旋转开关 ∩ 单规格旋转开关）
        List<Unit> units = new ArrayList<>();
        double outputArea = 0;
        int outputCount = 0;
        int idSeq = 0;
        for (int si = 0; si < pieces.size(); si++) {
            PieceRequest piece = pieces.get(si);
            if (piece.getLengthMm() == null || piece.getWidthMm() == null
                    || piece.getLengthMm() <= 0 || piece.getWidthMm() <= 0) {
                // 尺寸无效：输入级错误，必须直接报错并保留前端输入
                throw new BizException("第 " + (si + 1) + " 行出片规格长宽必须为正整数");
            }
            if (piece.getQuantity() == null || piece.getQuantity() <= 0) {
                throw new BizException("第 " + (si + 1) + " 行出片数量必须为正整数");
            }
            int l = piece.getLengthMm();
            int w = piece.getWidthMm();
            int qty = piece.getQuantity();
            boolean specRotate = globalRotation
                    && (piece.getAllowRotation() == null || piece.getAllowRotation());
            boolean singleFits = PackingEngine.canFitSingle(l, w, lengthMm, widthMm, specRotate);
            if (!singleFits && strict) {
                // 下刀时存在「旋转后仍超出原片幅面」的必需出片：直接拒绝
                throw new BizException("第 " + (si + 1) + " 行出片 " + l + "×" + w
                        + "mm " + (specRotate ? "旋转后仍" : "（未允许旋转）")
                        + "超出原片幅面 " + lengthMm + "×" + widthMm
                        + "mm：请调小规格、更换更大原片或允许旋转");
            }
            // 试算时不抛异常：这些片会在两份布局里如实记为未排入（总面积够也不能算成功）
            for (int i = 0; i < qty; i++) {
                units.add(new Unit(idSeq++, l, w, si, piece.getRemark(), specRotate));
            }
            outputArea += (double) l * w * qty / 1_000_000.0;
            outputCount += qty;
        }
        result.setOutputAreaSqm(round(outputArea, 3));
        result.setOutputCount(outputCount);
        boolean areaOverflow = outputArea
                > result.getSourceAreaSqm() + 1e-9 + (double) kerf * (lengthMm + widthMm) / 1e6;
        result.setAreaOverflow(areaOverflow);

        // 2) 同一原片/清单/刀缝下跑两套策略
        long deadline = System.nanoTime() + Dict.PACK_SEARCH_TIMEOUT_MS * 1_000_000L;
        RawRun rawA = PackingEngine.search(Dict.PACK_UTILIZATION, units,
                lengthMm, widthMm, kerf, deadline, Dict.PACK_SEARCH_MAX_OPS);
        RawRun rawB = PackingEngine.search(Dict.PACK_REMNANT, units,
                lengthMm, widthMm, kerf, 0L, Dict.PACK_SEARCH_MAX_OPS);

        StrategyResult a = toStrategy(Dict.PACK_UTILIZATION, "利用率优先",
                rawA, pieces, lengthMm, widthMm, kerf, outputArea, result.getSourceAreaSqm(), areaOverflow);
        StrategyResult b = toStrategy(Dict.PACK_REMNANT, "整块余料优先",
                rawB, pieces, lengthMm, widthMm, kerf, outputArea, result.getSourceAreaSqm(), areaOverflow);
        result.setUtilization(a);
        result.setRemnant(b);

        // 3) 推荐口径
        result.setRecommendation(buildRecommendation(a, b, outputCount));

        // 4) 总体提示
        if (a.isComplete() && b.isComplete()) {
            result.setMessage("双策略均放全 " + outputCount + " 片，刀缝 " + kerf
                    + "mm；推荐口径见下，等价时保持中立。");
        } else {
            result.setMessage("存在未排入出片（成功以真实几何放入为准，总面积足够不算成功）；"
                    + "可调约束：减少出片数量、缩小规格、允许旋转、加大原片或减小刀缝。");
        }

        // 5) 下刀（create）走严格校验：所选策略必须放全
        if (strict) {
            String chosen = resolveChosenStrategy(requestedStrategy, result);
            StrategyResult plan = Dict.PACK_REMNANT.equals(chosen) ? b : a;
            if (plan.isLimited() || plan.getUnplacedCount() > 0) {
                throw new BizException("所选「" + Dict.packStrategyName(chosen) + "」方案有 "
                        + plan.getUnplacedCount() + " 片未排入" + (plan.isLimited() ? "（搜索超时）" : "")
                        + "，不能生成切割单；请调整数量/规格/刀缝，或改选已放全的方案");
            }
        }
        return result;
    }

    private StrategyResult emptyStrategy(String code, String name, int sheetL, int sheetW, int kerf) {
        StrategyResult s = baseStrategy(code, name, sheetL, sheetW, kerf);
        Rect whole = new Rect(0, 0, sheetL, sheetW, "REMNANT",
                "整块余料 " + sheetL + "×" + sheetW, null);
        s.getRemnantRects().add(whole);
        s.getLayout().add(whole);
        s.setRemnantSpec(sheetL + "×" + sheetW);
        s.setBiggestRemnantMinSideMm(Math.min(sheetL, sheetW));
        s.setRemnantAreaSqm(round((double) sheetL * sheetW / 1e6, 3));
        s.setReusableAreaSqm(s.getRemnantAreaSqm());
        s.setEffectiveRate(100.0);
        s.setComplete(true);
        s.setMessage("整版余料 " + sheetL + "×" + sheetW + "mm");
        return s;
    }

    private StrategyResult baseStrategy(String code, String name, int sheetL, int sheetW, int kerf) {
        StrategyResult s = new StrategyResult();
        s.setStrategy(code);
        s.setStrategyName(name);
        s.setRequestedCount(0);
        return s;
    }

    private StrategyResult toStrategy(String code, String name, RawRun run,
                                      List<PieceRequest> pieces, int sheetL, int sheetW, int kerf,
                                      double outputArea, double sourceArea, boolean areaOverflow) {
        StrategyResult s = new StrategyResult();
        s.setStrategy(code);
        s.setStrategyName(name);
        s.setRequestedCount(run.placed.size() + run.unplaced.size());
        s.setPlacedCount(run.placed.size());
        s.setLimited(run.timedOut);
        s.setComplete(!run.timedOut && run.unplaced.isEmpty());

        Grid grid = Grid.build(sheetL, sheetW, run.placed, run.frees);

        // 布局矩形：出片 + 可复用整块余料 + 碎余料（刀缝不画在主布局，避免细线淹没，面积单独交代）
        for (Placed p : run.placed) {
            String dim = fmt(p.w) + "×" + fmt(p.h) + (p.rotated ? "（旋转）" : "");
            Rect rect = new Rect(p.x, p.y, p.w, p.h, "PIECE", dim, 1, p.rotated);
            s.getLayout().add(rect);
        }
        for (double[] r : grid.freeRects) {
            double minSide = Math.min(r[2], r[3]);
            double areaM2 = r[2] * r[3] / 1_000_000.0;
            boolean reusable = minSide >= PackingEngine.REUSE_MIN_SIDE_MM - PackingEngine.EPS
                    && areaM2 >= PackingEngine.REUSE_MIN_AREA_M2 - PackingEngine.EPS;
            String label = (reusable ? "可复用余料 " : "碎余料 ") + fmt(r[2]) + "×" + fmt(r[3]);
            Rect rect = new Rect(r[0], r[1], r[2], r[3], reusable ? "REMNANT" : "SCRAP", label, null);
            s.getLayout().add(rect);
            if (reusable) {
                s.getRemnantRects().add(rect);
            } else {
                s.getScrapRects().add(rect);
            }
        }
        for (double[] r : grid.kerfRects) {
            if (r[2] * r[3] >= 1.0) { // 不足 1mm² 的是网格数值毛刺，不展示
                s.getKerfRects().add(new Rect(r[0], r[1], r[2], r[3], "KERF",
                        "刀缝 " + fmt(Math.max(r[2], r[3])) + "mm", null));
            }
        }

        double pieceArea = grid.pieceArea / 1_000_000.0;
        double freeArea = grid.freeArea / 1_000_000.0;
        double reusableArea = grid.reuseArea / 1_000_000.0;
        double kerfArea = grid.kerfArea / 1_000_000.0;
        s.setPieceAreaSqm(round(pieceArea, 3));
        s.setRemnantAreaSqm(round(freeArea, 3));
        s.setReusableAreaSqm(round(reusableArea, 3));
        s.setScrapAreaSqm(round(freeArea - reusableArea, 3));
        s.setKerfAreaSqm(round(kerfArea, 3));
        s.setUtilizationRate(round(pieceArea / sourceArea * 100.0, 2));
        s.setEffectiveRate(round((pieceArea + reusableArea) / sourceArea * 100.0, 2));

        double[] biggest = grid.biggestReuseRect();
        if (biggest != null) {
            s.setRemnantSpec(fmt(biggest[2]) + "×" + fmt(biggest[3]));
            s.setBiggestRemnantMinSideMm(round(Math.min(biggest[2], biggest[3]), 1));
        } else {
            s.setRemnantSpec("0×0");
            s.setBiggestRemnantMinSideMm(0.0);
        }

        // 未排入项 + 具体原因
        for (PackingEngine.Unplaced u : run.unplaced) {
            PieceRequest spec = pieces.get(u.unit.specIndex);
            UnplacedItem item = new UnplacedItem();
            item.setSpecIndex(u.unit.specIndex);
            item.setLengthMm(spec.getLengthMm());
            item.setWidthMm(spec.getWidthMm());
            item.setRemark(spec.getRemark());
            if ("LIMITED".equals(u.reason)) {
                item.setReasonCode("TIMEOUT");
                item.setReason("搜索超时，该片能否放入尚未确认；可减少片数/规格种类或减小刀缝后重算");
            } else if (!PackingEngine.canFitSingle(spec.getLengthMm(), spec.getWidthMm(),
                    sheetL, sheetW, u.unit.allowRotation)) {
                // 即便允许旋转，单片名义/旋转两种朝向都进不去幅面 —— 与总面积无关
                item.setReasonCode("OVERSIZE");
                item.setReason(spec.getLengthMm() + "×" + spec.getWidthMm()
                        + "mm " + (u.unit.allowRotation ? "旋转后仍超出原片幅面"
                        : "未允许旋转，名义方向超出原片幅面（开启旋转可能可放）")
                        + "，只能调小规格或换更大原片");
            } else if (areaOverflow) {
                item.setReasonCode("AREA");
                item.setReason("出片总面积（含刀缝余量）超过原片，几何上必然有余片");
            } else {
                item.setReasonCode("GEOMETRY");
                item.setReason("其余出片占位后，剩余空间的长宽组合放不下该片——总面积足够不等于几何可放入");
            }
            s.getUnplacedItems().add(item);
        }
        s.setUnplacedCount(s.getUnplacedItems().size());

        if (s.isComplete()) {
            s.setMessage("放全 " + s.getPlacedCount() + " 片；名义利用率 " + s.getUtilizationRate()
                    + "%，有效利用率 " + s.getEffectiveRate() + "%；最大整块余料 "
                    + s.getRemnantSpec() + "mm，刀缝损耗 " + s.getKerfAreaSqm() + "㎡");
        } else {
            s.setMessage("放入 " + s.getPlacedCount() + "/" + s.getRequestedCount() + " 片，"
                    + s.getUnplacedCount() + " 片未排入" + (run.timedOut ? "（搜索超时，结果不完整）" : "")
                    + "；名义利用率 " + s.getUtilizationRate() + "%");
        }
        return s;
    }

    /**
     * 推荐：固定阈值（现场可理解）+ 综合余料形状/未排入的适配性。
     * 边界：超时不给；只有一份放全时只推荐可下刀那份；都放全时比有效利用率 2 点 /
     * 整块短边 200mm / 可复用余料 0.3㎡，均未越线即中立。
     */
    private Recommendation buildRecommendation(StrategyResult a, StrategyResult b, int totalCount) {
        Recommendation rec = new Recommendation();
        double dEff = a.getEffectiveRate() - b.getEffectiveRate();
        double dSide = b.getBiggestRemnantMinSideMm() - a.getBiggestRemnantMinSideMm();
        double dArea = b.getReusableAreaSqm() - a.getReusableAreaSqm();
        rec.setEffectiveGap(round(Math.abs(dEff), 2));
        rec.setRemnantSideGapMm(round(dSide, 1));
        rec.setRemnantAreaGapSqm(round(dArea, 3));

        if (a.isLimited() || b.isLimited()) {
            rec.setCode("NONE");
            rec.setText("至少一份方案搜索超时、放入性未全部确认，不给伪推荐；请调整后重算。");
            return rec;
        }
        boolean aFull = a.getPlacedCount() == totalCount;
        boolean bFull = b.getPlacedCount() == totalCount;
        if (aFull && !bFull) {
            rec.setCode("UTILIZATION");
            rec.setText("只有「利用率优先」放全 " + totalCount + " 片（整块余料方案 "
                    + b.getUnplacedCount() + " 片未排入），当前仅该方案可下刀。");
            return rec;
        }
        if (bFull && !aFull) {
            rec.setCode("REMNANT");
            rec.setText("只有「整块余料优先」放全 " + totalCount + " 片（利用率方案 "
                    + a.getUnplacedCount() + " 片未排入），当前仅该方案可下刀。");
            return rec;
        }
        if (!aFull && !bFull) {
            rec.setCode("NONE");
            rec.setText("两份方案都有出片未排入，先减少数量/缩小规格/允许旋转/减小刀缝，本次不给推荐。");
            return rec;
        }

        boolean effClose = Math.abs(dEff) <= Dict.REC_EFFECTIVE_GAP_POINTS + 1e-9;
        boolean sideClose = Math.abs(dSide) <= Dict.REC_REMNANT_SIDE_GAP_MM + 1e-9;
        boolean areaClose = Math.abs(dArea) <= Dict.REC_REMNANT_AREA_GAP_SQM + 1e-9;
        if (effClose && sideClose && areaClose) {
            rec.setCode("NEUTRAL");
            rec.setText("两策略等价：均放全 " + totalCount + " 片，有效利用率差 "
                    + rec.getEffectiveGap() + " 点、最大整块余料短边差 "
                    + fmt(Math.abs(dSide)) + "mm、可复用余料差 "
                    + fmt(Math.abs(dArea)) + "㎡，均未越过 2 点 / 200mm / 0.3㎡ 阈值，现场任选。");
            return rec;
        }
        if (dEff > Dict.REC_EFFECTIVE_GAP_POINTS + 1e-9) {
            rec.setCode("UTILIZATION");
            rec.setText("推荐「利用率优先」：放全片数相同，有效利用率（出片＋可复用整块余料）高 "
                    + rec.getEffectiveGap() + " 个点，超过 2 点阈值。");
            return rec;
        }
        if (dSide > Dict.REC_REMNANT_SIDE_GAP_MM + 1e-9 || dArea > Dict.REC_REMNANT_AREA_GAP_SQM + 1e-9) {
            rec.setCode("REMNANT");
            rec.setText("推荐「整块余料优先」：放全片数相同，最大整块余料短边多 " + fmt(dSide)
                    + "mm（阈值 200mm）、可复用余料合计多 " + fmt(dArea)
                    + "㎡（阈值 0.3㎡），后续套裁空间更完整。");
            return rec;
        }
        rec.setCode("NEUTRAL");
        rec.setText("两策略等价：有效利用率差 " + rec.getEffectiveGap() + " 点、整块短边差 "
                + fmt(Math.abs(dSide)) + "mm、余料面积差 " + fmt(Math.abs(dArea))
                + "㎡，各有取舍但都未越过固定阈值。");
        return rec;
    }

    private Recommendation neutral(String text) {
        Recommendation rec = new Recommendation();
        rec.setCode("NEUTRAL");
        rec.setText(text);
        return rec;
    }

    /** 下刀策略：显式指定优先；未指定时仅接受明确推荐；中立/无推荐时报错要求现场拍板 */
    private String resolveChosenStrategy(String requested, PreviewResult preview) {
        if (StringUtils.hasText(requested)) {
            if (!Dict.isValidPackStrategy(requested)) {
                throw new BizException("排样策略非法：" + requested + "，仅支持 UTILIZATION / REMNANT");
            }
            return requested;
        }
        String code = preview.getRecommendation() == null ? null : preview.getRecommendation().getCode();
        if (Dict.PACK_UTILIZATION.equals(code) || Dict.PACK_REMNANT.equals(code)) {
            return code;
        }
        throw new BizException("两策略推荐结果为「"
                + ("NEUTRAL".equals(code) ? "等价中立" : "无推荐")
                + "」，生成切割单时必须显式选择采用哪一份方案");
    }

    private int resolveKerf(Integer kerf) {
        if (kerf == null) {
            return Dict.KERF_DEFAULT_MM;
        }
        if (kerf < Dict.KERF_MIN_MM || kerf > Dict.KERF_MAX_MM) {
            throw new BizException("刀缝宽度必须在 " + Dict.KERF_MIN_MM + "~" + Dict.KERF_MAX_MM + "mm 之间");
        }
        return kerf;
    }

    private boolean resolveGlobalRotation(Boolean allowRotation) {
        return allowRotation == null || allowRotation;
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

    /** 尺寸展示：整数不带小数，带小数的保留一位 */
    private static String fmt(double value) {
        double v = Math.round(value * 10.0) / 10.0;
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
