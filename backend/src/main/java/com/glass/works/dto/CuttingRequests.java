package com.glass.works.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 切割相关入参 / 出参。合并在一个文件里，避免过多细碎类文件。 */
public final class CuttingRequests {

    private CuttingRequests() {
    }

    /** 一条出片规格 */
    @Data
    @NoArgsConstructor
    public static class PieceRequest {
        private Integer lengthMm;
        private Integer widthMm;
        private Integer quantity;
        private String remark;
        /** 该规格是否允许 90° 旋转排样（默认允许）；关闭后名义方向放不下即记未排入 */
        private Boolean allowRotation;

        public PieceRequest(Integer lengthMm, Integer widthMm, Integer quantity) {
            this.lengthMm = lengthMm;
            this.widthMm = widthMm;
            this.quantity = quantity;
        }
    }

    /** 生成切割单入参 */
    @Data
    @NoArgsConstructor
    public static class OrderRequest {
        private Long stockId;
        private String layoutNo;
        private String cutter;
        private String operator;
        private List<PieceRequest> pieces = new ArrayList<>();
        /** 刀缝宽度 mm（0~20），不传按默认 3mm */
        private Integer kerfMm;
        /**
         * 下刀采用哪份策略：UTILIZATION / REMNANT。
         * 不传时取推荐结果；推荐为中立/无推荐时必须显式指定，否则报错而不是默认猜一份。
         */
        private String strategy;
    }

    /** 试算入参（不落库） */
    @Data
    @NoArgsConstructor
    public static class PreviewRequest {
        private Long stockId;
        private Integer sourceLengthMm;
        private Integer sourceWidthMm;
        private List<PieceRequest> pieces = new ArrayList<>();
        /** 刀缝宽度 mm（0~20），不传按默认 3mm */
        private Integer kerfMm;
        /** 全局是否允许旋转：false 时所有规格都不允许旋转（单项关闭更严格时从其设置） */
        private Boolean allowRotation;
    }

    /** 布局图上的一个矩形（单位 mm，原点左上） */
    @Data
    @NoArgsConstructor
    public static class Rect {
        private double x;
        private double y;
        private double w;
        private double h;
        /** PIECE 出片 / REMNANT 可复用整块余料 / SCRAP 碎余料 / KERF 刀缝损耗 */
        private String type;
        private String label;
        private Integer quantity;
        /** 出片矩形专用：是否旋转后落位 */
        private Boolean rotated;

        public Rect(double x, double y, double w, double h, String type, String label, Integer quantity) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.type = type;
            this.label = label;
            this.quantity = quantity;
        }

        public Rect(double x, double y, double w, double h, String type, String label,
                    Integer quantity, Boolean rotated) {
            this(x, y, w, h, type, label, quantity);
            this.rotated = rotated;
        }
    }

    /** 一片未排入出片及其具体原因 */
    @Data
    @NoArgsConstructor
    public static class UnplacedItem {
        /** 对应清单规格序号（从 0 开始） */
        private int specIndex;
        private int lengthMm;
        private int widthMm;
        private String remark;
        /** OVERSIZE 旋转后仍超幅面 / AREA 总面积超原片 / GEOMETRY 几何放不下 / TIMEOUT 搜索超时未确认 */
        private String reasonCode;
        private String reason;
    }

    /** 一份策略的排样结果：布局、利用率、余料矩形、未排入项 */
    @Data
    @NoArgsConstructor
    public static class StrategyResult {
        /** UTILIZATION 本次利用率优先 / REMNANT 整块余料优先 */
        private String strategy;
        private String strategyName;
        private String anchor;
        private List<Rect> layout = new ArrayList<>();
        /** 可复用整块余料矩形（最短边 ≥300mm 且 ≥0.25㎡） */
        private List<Rect> remnantRects = new ArrayList<>();
        /** 碎余料矩形（不可复用） */
        private List<Rect> scrapRects = new ArrayList<>();
        /** 刀缝损耗矩形（仅展示，宽度按真实刀缝画） */
        private List<Rect> kerfRects = new ArrayList<>();

        private int requestedCount;
        private int placedCount;
        private int unplacedCount;
        private List<UnplacedItem> unplacedItems = new ArrayList<>();

        /** 名义利用率 = 出片面积 / 原片面积 ×100% */
        private double utilizationRate;
        /** 有效利用率 = (出片面积 + 可复用整块余料面积) / 原片面积 ×100% */
        private double effectiveRate;
        private double pieceAreaSqm;
        private double remnantAreaSqm;
        private double reusableAreaSqm;
        private double scrapAreaSqm;
        private double kerfAreaSqm;
        /** 最大整块可复用余料规格串，如 900×630；无整块时为 "0×0" */
        private String remnantSpec;
        /** 最大整块余料最短边 mm（现场判断能不能再套裁的关键口径） */
        private double biggestRemnantMinSideMm;

        /** 搜索是否在时限/操作预算内完成；true 表示放入性未全部确认，结果不完整 */
        private boolean limited;
        private boolean complete;
        private String message;
    }

    /** 双策略推荐口径 */
    @Data
    @NoArgsConstructor
    public static class Recommendation {
        /** UTILIZATION / REMNANT / NEUTRAL 中立等价 / NONE 不给推荐 */
        private String code;
        private String text;
        /** 固定阈值口径（现场可理解） */
        private double effectiveGapThreshold = 2.0;
        private double remnantSideGapThresholdMm = 200.0;
        private double remnantAreaGapThresholdSqm = 0.30;
        /** 参与比较的实测差值，供页面展示「推荐边界」 */
        private double effectiveGap;
        private double remnantSideGapMm;
        private double remnantAreaGapSqm;
    }

    /** 试算出参：同一原片/清单/刀缝下的双策略结果 + 推荐口径 */
    @Data
    @NoArgsConstructor
    public static class PreviewResult {
        private String batchNo;
        private String glassType;
        private Integer sourceLengthMm;
        private Integer sourceWidthMm;
        private double sourceAreaSqm;
        private int kerfMm;
        private boolean allowRotation;
        private double outputAreaSqm;
        private int outputCount;

        private StrategyResult utilization;
        private StrategyResult remnant;
        private Recommendation recommendation;

        /** 是否总面积（含刀缝余量）超原片（两份结果都会有未排入项的常见原因） */
        private boolean areaOverflow;
        private String message;
        private List<PieceRequest> pieces = new ArrayList<>();
    }
}
