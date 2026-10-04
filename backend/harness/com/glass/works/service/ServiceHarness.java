package com.glass.works.service;

import com.glass.works.common.BizException;
import com.glass.works.domain.CuttingOrder;
import com.glass.works.domain.CuttingPiece;
import com.glass.works.domain.GlassStock;
import com.glass.works.dto.CuttingRequests.OrderRequest;
import com.glass.works.dto.CuttingRequests.PieceRequest;
import com.glass.works.dto.CuttingRequests.PreviewRequest;
import com.glass.works.dto.CuttingRequests.PreviewResult;
import com.glass.works.dto.CuttingRequests.StrategyResult;
import com.glass.works.repository.CuttingOrderRepository;
import com.glass.works.repository.CuttingPieceRepository;
import com.glass.works.repository.GlassStockRepository;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 双策略服务端到端人工校验：不启 Spring，用动态代理顶替三个 Repository。
 * 覆盖：双策略同原片/清单/刀缝、未排入原因、推荐口径、严格下刀、尺寸无效保留输入语义。
 */
public class ServiceHarness {

    static int failures = 0;

    static void check(String name, boolean cond) {
        if (!cond) {
            failures++;
            System.out.println("  !! 断言失败: " + name);
        }
    }

    static GlassStock stock(long id, String batch, int l, int w) {
        GlassStock s = new GlassStock();
        s.setId(id);
        s.setBatchNo(batch);
        s.setGlassType("FLOAT_CLEAR");
        s.setLengthMm(l);
        s.setWidthMm(w);
        s.setSheetCount(100);
        return s;
    }

    static PieceRequest piece(int l, int w, int q) {
        PieceRequest p = new PieceRequest(l, w, q);
        p.setAllowRotation(true);
        return p;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.apply(method.getName()));
    }

    public static void main(String[] args) {
        GlassStock st = stock(3L, "YP-2401-01", 2440, 1830);
        AtomicLong orderSeq = new AtomicLong(100);

        GlassStockRepository stockRepo = proxy(GlassStockRepository.class, name -> {
            throw new IllegalStateException("stockRepo needs args-based proxy");
        });
        stockRepo = (GlassStockRepository) Proxy.newProxyInstance(
                GlassStockRepository.class.getClassLoader(),
                new Class<?>[]{GlassStockRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "findById" -> Optional.of(st);
                    default -> defaultVal(m.getReturnType());
                });

        List<CuttingOrder> savedOrders = new ArrayList<>();
        CuttingOrderRepository orderRepo = (CuttingOrderRepository) Proxy.newProxyInstance(
                CuttingOrderRepository.class.getClassLoader(),
                new Class<?>[]{CuttingOrderRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "existsByOrderNo" -> false;
                    case "count" -> 1L;
                    case "save" -> {
                        CuttingOrder o = (CuttingOrder) a[0];
                        o.setId(orderSeq.incrementAndGet());
                        savedOrders.add(o);
                        yield o;
                    }
                    case "findAll" -> savedOrders;
                    case "remnantRank" -> List.of();
                    default -> defaultVal(m.getReturnType());
                });

        List<CuttingPiece> savedPieces = new ArrayList<>();
        CuttingPieceRepository pieceRepo = (CuttingPieceRepository) Proxy.newProxyInstance(
                CuttingPieceRepository.class.getClassLoader(),
                new Class<?>[]{CuttingPieceRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        savedPieces.add((CuttingPiece) a[0]);
                        yield a[0];
                    }
                    default -> defaultVal(m.getReturnType());
                });

        CuttingService svc = new CuttingService(orderRepo, pieceRepo, stockRepo);

        // ---- 场景1：种子清单，双策略放全 ----
        PreviewRequest req = new PreviewRequest();
        req.setStockId(3L);
        req.setKerfMm(3);
        req.setPieces(List.of(piece(1200, 900, 2), piece(800, 600, 3)));
        PreviewResult r = svc.preview(req);
        StrategyResult a = r.getUtilization();
        StrategyResult b = r.getRemnant();
        check("同原片批次", "YP-2401-01".equals(r.getBatchNo()));
        check("同刀缝 3", r.getKerfMm() == 3);
        check("A 放全 5", a.getPlacedCount() == 5 && a.getUnplacedCount() == 0 && a.isComplete());
        check("B 放全 5", b.getPlacedCount() == 5 && b.getUnplacedCount() == 0 && b.isComplete());
        check("A 有布局矩形", a.getLayout().size() >= 5);
        check("B 有余料矩形", b.getRemnantRects().size() >= 1);
        check("面积闭合",
                Math.abs(r.getSourceAreaSqm() - a.getPieceAreaSqm() - a.getRemnantAreaSqm() - a.getKerfAreaSqm()) < 0.005);
        check("B 最大整块 900×630", "900×630".equals(b.getRemnantSpec()));
        check("推荐保余料", "REMNANT".equals(r.getRecommendation().getCode()));
        System.out.println("场景1 推荐: " + r.getRecommendation().getText());

        // ---- 场景3：面积够几何不够，不出伪成功 ----
        PreviewRequest req3 = new PreviewRequest();
        req3.setStockId(3L);
        req3.setPieces(List.of(piece(1300, 1300, 2)));
        PreviewResult r3 = svc.preview(req3);
        check("S3 两份均有未排入",
                r3.getUtilization().getUnplacedCount() >= 1 && r3.getRemnant().getUnplacedCount() >= 1);
        check("S3 原因是GEOMETRY",
                "GEOMETRY".equals(r3.getUtilization().getUnplacedItems().get(0).getReasonCode()));
        check("S3 无伪推荐", "NONE".equals(r3.getRecommendation().getCode()));

        // ---- 场景4：旋转后仍超限，preview 不抛、create 抛 ----
        PreviewRequest req9 = new PreviewRequest();
        req9.setStockId(3L);
        req9.setPieces(List.of(piece(3000, 2000, 1), piece(800, 600, 2)));
        PreviewResult r9 = svc.preview(req9);
        check("S9 preview 不抛异常且保留输入", r9.getPieces().size() == 2);
        check("S9 超限记 OVERSIZE", r9.getUtilization().getUnplacedItems().stream()
                .anyMatch(i -> "OVERSIZE".equals(i.getReasonCode())));
        check("S9 无推荐", "NONE".equals(r9.getRecommendation().getCode()));

        OrderRequest badOrder = new OrderRequest();
        badOrder.setStockId(3L);
        badOrder.setStrategy("UTILIZATION");
        badOrder.setPieces(List.of(piece(3000, 2000, 1)));
        boolean threw = false;
        try {
            svc.create(badOrder);
        } catch (BizException e) {
            threw = true;
            System.out.println("S9 create 拦截: " + e.getMessage());
        }
        check("S9 必需片超限禁止下刀", threw);

        // ---- 场景5：禁旋全局开关 ----
        PreviewRequest req5 = new PreviewRequest();
        req5.setStockId(3L);
        req5.setAllowRotation(false);
        req5.setPieces(List.of(piece(1300, 2400, 1)));
        PreviewResult r5 = svc.preview(req5);
        check("S5 禁旋放不下", r5.getUtilization().getUnplacedCount() == 1);
        check("S5 原因 OVERSIZE",
                "OVERSIZE".equals(r5.getUtilization().getUnplacedItems().get(0).getReasonCode()));

        // ---- 尺寸无效：直接抛（前端保留输入） ----
        PreviewRequest bad = new PreviewRequest();
        bad.setStockId(3L);
        bad.setPieces(List.of(new PieceRequest(0, 600, 2)));
        boolean badThrew = false;
        try {
            svc.preview(bad);
        } catch (BizException e) {
            badThrew = true;
            System.out.println("尺寸无效拦截: " + e.getMessage());
        }
        check("尺寸无效抛异常", badThrew);

        // ---- 刀缝非法 ----
        PreviewRequest badKerf = new PreviewRequest();
        badKerf.setStockId(3L);
        badKerf.setKerfMm(99);
        badKerf.setPieces(List.of(piece(800, 600, 1)));
        boolean kerfThrew = false;
        try {
            svc.preview(badKerf);
        } catch (BizException e) {
            kerfThrew = true;
        }
        check("刀缝非法拦截", kerfThrew);

        // ---- 严格下刀：场景1 推荐 REMNANT，不显式指定策略时自动取推荐 ----
        OrderRequest ok = new OrderRequest();
        ok.setStockId(3L);
        ok.setCutter("CTL-01");
        ok.setOperator("测试员");
        ok.setPieces(List.of(piece(1200, 900, 2), piece(800, 600, 3)));
        CuttingOrder saved = svc.create(ok);
        check("下刀策略=推荐 REMNANT", "REMNANT".equals(saved.getPackStrategy()));
        check("下刀刀缝=3", Integer.valueOf(3).equals(saved.getKerfMm()));
        check("下刀出片数=5", saved.getOutputCount() == 5);
        check("保存了出片明细", savedPieces.size() == 2);
        check("名义利用率 80.62", Math.abs(saved.getUtilizationRate().doubleValue() - 80.62) < 0.01);
        check("有效率已落库", saved.getEffectiveRate() != null);
        check("刀缝损耗已落库", saved.getKerfAreaSqm() != null);
        System.out.println("下刀成功 " + saved.getOrderNo() + " " + saved.getStrategyName()
                + " 名义 " + saved.getUtilizationRate() + "% 有效 " + saved.getEffectiveRate()
                + "% 余料 " + saved.getRemnantSpec());

        // ---- 中立时不指定策略 → 拒绝（不默认猜）----
        // 构造一组两策略等价的小料清单
        OrderRequest neutralOrder = new OrderRequest();
        neutralOrder.setStockId(3L);
        neutralOrder.setPieces(List.of(piece(600, 400, 16)));
        PreviewResult rn = svc.preview(previewOf(neutralOrder, 3));
        if ("NEUTRAL".equals(rn.getRecommendation().getCode())) {
            boolean neutralThrew = false;
            try {
                svc.create(neutralOrder);
            } catch (BizException e) {
                neutralThrew = true;
            }
            check("中立必须显式选策略", neutralThrew);
        }

        System.out.println(failures == 0 ? "\n服务层断言：全部通过" : "\n失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    static PreviewRequest previewOf(OrderRequest o, int kerf) {
        PreviewRequest p = new PreviewRequest();
        p.setStockId(o.getStockId());
        p.setKerfMm(kerf);
        p.setPieces(o.getPieces());
        return p;
    }

    static Object defaultVal(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        return null;
    }
}
