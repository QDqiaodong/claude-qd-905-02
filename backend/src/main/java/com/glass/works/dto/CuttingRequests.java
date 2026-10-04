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
        /** 刀缝（锯路）宽度 mm，null 按 0 处理，与试算共用同一刀缝规则 */
        private Integer kerfMm;
        /** 是否允许出片旋转 90°，null 按 false 处理 */
        private Boolean allowRotation;
        /** 落库采用哪份排样：USAGE 利用率优先 / REMNANT 整块余料优先，null 按 USAGE */
        private String strategy;
        private List<PieceRequest> pieces = new ArrayList<>();
    }

    /** 试算入参（不落库） */
    @Data
    @NoArgsConstructor
    public static class PreviewRequest {
        private Long stockId;
        private Integer sourceLengthMm;
        private Integer sourceWidthMm;
        private List<PieceRequest> pieces = new ArrayList<>();
        /** 刀缝（锯路）宽度 mm，老调用方不传按 0 处理 */
        private Integer kerfMm;
        /** 是否允许出片旋转 90°，老调用方不传按不允许处理 */
        private Boolean allowRotation;
        /** 单策略搜索预算 ms，null 走服务端默认，范围 50~5000 */
        private Integer timeLimitMs;
        /** 前端自增序号，服务端原样回显，用于丢弃乱序 / 过期响应 */
        private Long clientSeq;
    }

    /** 布局图上的一个矩形（单位 mm，原点左上） */
    @Data
    @NoArgsConstructor
    public static class Rect {
        private double x;
        private double y;
        private double w;
        private double h;
        /** PIECE 出片 / REMNANT 余料 */
        private String type;
        private String label;
        private Integer quantity;
        /** 出片原始长（mm），旋转时与 w/h 对调，便于核对 */
        private Double pieceLengthMm;
        private Double pieceWidthMm;
        /** 该片是否相对输入规格旋转了 90° */
        private Boolean rotated;
        /** 余料矩形是否达到可复用整块门槛（仅 REMNANT 使用） */
        private Boolean reusable;

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
                    Integer quantity, Double pieceLengthMm, Double pieceWidthMm, Boolean rotated) {
            this(x, y, w, h, type, label, quantity);
            this.pieceLengthMm = pieceLengthMm;
            this.pieceWidthMm = pieceWidthMm;
            this.rotated = rotated;
        }
    }

    /** 余料矩形：布局图几何 + 是否算可复用整块的判定 */
    @Data
    @NoArgsConstructor
    public static class RemnantRect {
        private double x;
        private double y;
        private double w;
        private double h;
        /** 规格文本，如 618×1830 */
        private String spec;
        /** 面积 ㎡ */
        private double areaSqm;
        /** 是否达到可复用整块门槛（短边 / 面积） */
        private boolean reusable;
        /** 不可复用时给出原因，可复用时为 null */
        private String note;
    }

    /** 未排入项：按规格聚合，并交代具体原因，绝不因总面积足够而判成功 */
    @Data
    @NoArgsConstructor
    public static class UnplacedItem {
        private Integer lengthMm;
        private Integer widthMm;
        /** 该规格未排入的片数 */
        private int missingQuantity;
        /** 未排入原因码：NO_ROTATION_FIT / GEOMETRY_FULL / SEARCH_TIMEOUT */
        private String reasonCode;
        /** 现场可读的原因说明 */
        private String reason;
    }

    /** 单策略排样结果 */
    @Data
    @NoArgsConstructor
    public static class StrategyResult {
        /** USAGE 利用率优先 / REMNANT 余料保留优先 */
        private String strategy;
        private String strategyName;
        private String strategyDesc;
        /** 布局矩形（出片 + 余料；出片之间露出的底色即刀缝） */
        private List<Rect> layout = new ArrayList<>();
        private List<RemnantRect> remnants = new ArrayList<>();
        private List<UnplacedItem> unplaced = new ArrayList<>();

        private int requestedCount;
        private int placedCount;
        /** 已排入出片面积 ㎡ */
        private double placedAreaSqm;
        /** 请求出片总面积 ㎡（数量 × 单片面积） */
        private double requestedAreaSqm;
        /** 利用率 = 已排入面积 / 原片面积 ×100% */
        private double utilizationRate;
        private String utilizationLevel;
        /** 余料总面积（未覆盖区域，已含刀缝之外的全部空白）㎡ */
        private double remnantAreaSqm;
        /** 刀缝（锯路）损耗面积 ㎡ */
        private double kerfAreaSqm;
        /** 最大一块可复用整块余料的规格，无则 0×0 */
        private String remnantSpec;
        /** 最大可复用整块余料面积 ㎡ */
        private double reusableAreaSqm;
        /** 最大可复用整块的短边 mm（越宽越值钱） */
        private double reusableMinSideMm;
        /** 是否搜索超时（超时也返回当前最优，但不算完整成功） */
        private boolean timedOut;
        /** 是否全部排入（未超时且无未排入项） */
        private boolean complete;
        private String message;
    }

    /** 双策略推荐口径 */
    @Data
    @NoArgsConstructor
    public static class Recommendation {
        /** USAGE / REMNANT / NONE（等价或无法判定时中立） */
        private String choice;
        private String choiceName;
        /** 现场可读的推荐理由 */
        private String reason;
        /** 口径说明：固定阈值带与综合适配覆盖关系 */
        private String policy;
        /** 两策略利用率差（百分点，绝对值） */
        private Double utilizationGapPct;
        /** 两策略最大整块余料面积差 ㎡（绝对值） */
        private Double reusableGapSqm;
    }

    /** 双策略试算出参：同一原片批次、同一份出片清单、同一刀缝规则下的两份排样 */
    @Data
    @NoArgsConstructor
    public static class DualPreviewResult {
        /** 本次计算标识（服务端生成，前端用于丢弃过期 / 乱序响应） */
        private long calculationId;
        /** 前端自增序号（原样回显） */
        private Long clientSeq;
        private String batchNo;
        private String glassType;
        private Integer sourceLengthMm;
        private Integer sourceWidthMm;
        private double sourceAreaSqm;
        private int kerfMm;
        private boolean allowRotation;
        private Integer timeLimitMs;
        private int requestedCount;
        private double requestedAreaSqm;
        /** 回显出片清单（保留输入用） */
        private List<PieceRequest> pieces = new ArrayList<>();
        private StrategyResult usage = new StrategyResult();
        private StrategyResult remnant = new StrategyResult();
        private Recommendation recommendation = new Recommendation();
    }

    /** 试算出参：利用率、余料、布局矩形（旧单策略接口保持不变） */
    @Data
    @NoArgsConstructor
    public static class PreviewResult {
        private String batchNo;
        private String glassType;
        private Integer sourceLengthMm;
        private Integer sourceWidthMm;
        private double sourceAreaSqm;
        private double outputAreaSqm;
        private double remnantAreaSqm;
        private double utilizationRate;
        private String utilizationLevel;
        private String remnantSpec;
        private int outputCount;
        private boolean overflow;
        private String message;
        private List<Rect> layout = new ArrayList<>();
        private List<PieceRequest> pieces = new ArrayList<>();
    }
}
