package com.glass.works.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glass.works.dto.CuttingRequests.PieceRequest;
import com.glass.works.dto.CuttingRequests.PreviewRequest;
import com.glass.works.dto.CuttingRequests.PreviewResult;
import com.glass.works.repository.CuttingOrderRepository;
import com.glass.works.repository.CuttingPieceRepository;
import com.glass.works.repository.GlassStockRepository;
import com.glass.works.domain.GlassStock;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

/** 校验双策略结果能被 Jackson 正常序列化（前端实际消费的 JSON）。 */
public class JsonHarness {

    public static void main(String[] args) throws Exception {
        GlassStock st = new GlassStock();
        st.setId(3L);
        st.setBatchNo("YP-2401-01");
        st.setGlassType("FLOAT_CLEAR");
        st.setLengthMm(2440);
        st.setWidthMm(1830);
        st.setSheetCount(100);

        GlassStockRepository stockRepo = (GlassStockRepository) Proxy.newProxyInstance(
                GlassStockRepository.class.getClassLoader(),
                new Class<?>[]{GlassStockRepository.class},
                (p, m, a) -> m.getName().equals("findById") ? Optional.of(st) : null);
        CuttingOrderRepository orderRepo = (CuttingOrderRepository) Proxy.newProxyInstance(
                CuttingOrderRepository.class.getClassLoader(),
                new Class<?>[]{CuttingOrderRepository.class},
                (p, m, a) -> null);
        CuttingPieceRepository pieceRepo = (CuttingPieceRepository) Proxy.newProxyInstance(
                CuttingPieceRepository.class.getClassLoader(),
                new Class<?>[]{CuttingPieceRepository.class},
                (p, m, a) -> null);

        CuttingService svc = new CuttingService(orderRepo, pieceRepo, stockRepo);
        PreviewRequest req = new PreviewRequest();
        req.setStockId(3L);
        req.setKerfMm(3);
        PieceRequest p1 = new PieceRequest(1200, 900, 2);
        PieceRequest p2 = new PieceRequest(800, 600, 3);
        p1.setAllowRotation(true);
        p2.setAllowRotation(true);
        req.setPieces(List.of(p1, p2));

        PreviewResult r = svc.preview(req);
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        String json = om.writerWithDefaultPrettyPrinter().writeValueAsString(r);
        // 关键断言：前端依赖字段都在
        for (String key : List.of(
                "\"utilization\"", "\"remnant\"", "\"recommendation\"",
                "\"layout\"", "\"remnantRects\"", "\"scrapRects\"",
                "\"unplacedItems\"", "\"utilizationRate\"", "\"effectiveRate\"",
                "\"kerfAreaSqm\"", "\"reusableAreaSqm\"", "\"biggestRemnantMinSideMm\"",
                "\"code\"", "\"text\"", "\"effectiveGap\"",
                "\"kerfMm\"", "\"allowRotation\"", "\"rotated\"", "\"complete\"", "\"limited\"")) {
            if (!json.contains(key)) {
                System.out.println("!! JSON 缺少字段 " + key);
                System.exit(1);
            }
        }
        System.out.println("JSON 字段齐全，长度 " + json.length());
        System.out.println("推荐 code = " + r.getRecommendation().getCode());
        System.out.println("A layout 矩形数 = " + r.getUtilization().getLayout().size()
                + "，B = " + r.getRemnant().getLayout().size());
    }
}
