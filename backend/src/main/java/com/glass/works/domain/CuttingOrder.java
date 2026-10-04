package com.glass.works.domain;

import com.glass.works.common.BizException;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 切割单：一张原片批次按布局图切成若干出片。
 *
 * <p>派生属性 {@code utilization_rate}（利用率 %）走 {@code @Access(PROPERTY)}：
 * getter 由「出片面积合计 / 原片面积」四舍五入得出，
 * setter 做边界校验 —— 负值或超过 100% 直接抛业务异常。</p>
 */
@Entity
@Table(name = "cutting_order")
@Access(AccessType.FIELD)
@Getter
@Setter
public class CuttingOrder {

    /** 余料规格串：620×1830（mm×mm，可带一位小数） */
    private static final Pattern REMNANT_PATTERN = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)?)\\s*[×xX*]\\s*(\\d+(?:\\.\\d+)?)\\s*$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(name = "stock_id")
    private Long stockId;

    /** 冗余原片批次号，供报表直接统计 */
    @Column(name = "batch_no", length = 32)
    private String batchNo;

    @Column(name = "glass_type", length = 32)
    private String glassType;

    /** 切割布局图号 */
    @Column(name = "layout_no", length = 32)
    private String layoutNo;

    /** 出片数量（片数合计，非规格数） */
    @Column(name = "output_count")
    private Integer outputCount;

    /** 原片面积 ㎡ */
    @Column(name = "source_area_sqm", precision = 12, scale = 3)
    private BigDecimal sourceAreaSqm;

    /** 出片面积合计 ㎡ */
    @Column(name = "output_area_sqm", precision = 12, scale = 3)
    private BigDecimal outputAreaSqm;

    /** 余料面积 ㎡ */
    @Column(name = "remnant_area_sqm", precision = 12, scale = 3)
    private BigDecimal remnantAreaSqm;

    /** 余料规格 */
    @Column(name = "remnant_spec", length = 48)
    private String remnantSpec;

    /** 实际下刀采用的双策略：UTILIZATION 利用率优先 / REMNANT 整块余料优先 */
    @Column(name = "pack_strategy", length = 16)
    private String packStrategy;

    /** 策略中文名冗余，台账直接展示 */
    @Column(name = "strategy_name", length = 32)
    private String strategyName;

    /** 刀缝宽度 mm（切割损耗按此真实参与排样） */
    @Column(name = "kerf_mm")
    private Integer kerfMm;

    /** 有效利用率 %（出片面积 + 可复用整块余料面积）/ 原片面积 */
    @Column(name = "effective_rate", precision = 6, scale = 2)
    private BigDecimal effectiveRate;

    /** 可复用整块余料面积合计 ㎡（最短边 ≥300mm 且 ≥0.25㎡ 的整块） */
    @Column(name = "reusable_area_sqm", precision = 12, scale = 3)
    private BigDecimal reusableAreaSqm;

    /** 刀缝损耗面积 ㎡ */
    @Column(name = "kerf_area_sqm", precision = 12, scale = 3)
    private BigDecimal kerfAreaSqm;

    @Column(name = "cutter", length = 48)
    private String cutter;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 派生列（利用率 %），走属性访问 */
    private BigDecimal utilizationRate;

    @Access(AccessType.PROPERTY)
    @Column(name = "utilization_rate", precision = 6, scale = 2)
    public BigDecimal getUtilizationRate() {
        if (sourceAreaSqm == null || sourceAreaSqm.doubleValue() <= 0
                || outputAreaSqm == null) {
            return utilizationRate;
        }
        double rate = outputAreaSqm.doubleValue() / sourceAreaSqm.doubleValue() * 100.0;
        return BigDecimal.valueOf(Math.round(rate * 100.0) / 100.0).setScale(2, RoundingMode.HALF_UP);
    }

    public void setUtilizationRate(BigDecimal value) {
        if (value == null) {
            this.utilizationRate = null;
            return;
        }
        double rate = value.doubleValue();
        if (rate < 0 || rate > 100) {
            throw new BizException("切割利用率 " + rate + "% 超出合法区间（0~100%）");
        }
        this.utilizationRate = value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 余料面积由原片面积与出片面积派生（字段由 service 落库） */
    @Transient
    public BigDecimal getDerivedRemnantAreaSqm() {
        if (sourceAreaSqm == null) {
            return BigDecimal.ZERO;
        }
        double out = outputAreaSqm == null ? 0 : outputAreaSqm.doubleValue();
        return BigDecimal.valueOf(sourceAreaSqm.doubleValue() - out).setScale(3, RoundingMode.HALF_UP);
    }

    /** 余料规格：解析 "620×1830" 得到长宽（前端画斜纹余料区用） */
    @Transient
    public int[] getRemnantSize() {
        if (remnantSpec == null) {
            return new int[]{0, 0};
        }
        Matcher matcher = REMNANT_PATTERN.matcher(remnantSpec);
        if (!matcher.matches()) {
            return new int[]{0, 0};
        }
        return new int[]{
                (int) Math.round(Double.parseDouble(matcher.group(1))),
                (int) Math.round(Double.parseDouble(matcher.group(2)))};
    }

    /** 利用率档位：LOW（<70%）/ MID / HIGH（>95%） */
    @Transient
    public String getUtilizationLevel() {
        BigDecimal rate = getUtilizationRate();
        if (rate == null) {
            return "MID";
        }
        double v = rate.doubleValue();
        if (v < 70.0) {
            return "LOW";
        }
        return v > 95.0 ? "HIGH" : "MID";
    }
}
