<script setup>
import { ElButton } from 'element-plus'

const props = defineProps({
  sr: { type: Object, required: true },
  dual: { type: Object, required: true },
  svgBox: { type: Object, required: true },
  rectOf: { type: Function, required: true },
  variant: { type: String, default: 'USAGE' }, // USAGE / REMNANT，决定配色
  stale: Boolean,
  loading: Boolean
})
const emit = defineEmits(['adopt'])

const SVG_W = 560
const hatchId = props.variant === 'REMNANT' ? 'remnantHatchR' : 'remnantHatchU'
const scrapHatchId = props.variant === 'REMNANT' ? 'scrapHatchR' : 'scrapHatchU'
const pieceGradId = props.variant === 'REMNANT' ? 'pieceGradR' : 'pieceGradU'

function unplacedTotal() {
  return props.sr.unplaced.reduce((a, i) => a + i.missingQuantity, 0)
}
</script>

<template>
  <div class="strategy-panel">
    <div class="layout-wrap" :class="{ dimmed: stale }">
      <svg :viewBox="`0 0 ${svgBox.w} ${svgBox.h}`" class="layout-svg">
        <defs>
          <pattern :id="hatchId" width="9" height="9" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="9" height="9" fill="rgba(255,255,255,0.06)" />
            <line x1="0" y1="0" x2="0" y2="9" stroke="rgba(77,214,168,0.65)" stroke-width="2.4" />
          </pattern>
          <pattern :id="scrapHatchId" width="7" height="7" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <line x1="0" y1="0" x2="0" y2="7" stroke="rgba(180,190,220,0.4)" stroke-width="2" />
          </pattern>
          <linearGradient id="sheetGradX" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="rgba(150,200,255,0.30)" />
            <stop offset="50%" stop-color="rgba(255,255,255,0.10)" />
            <stop offset="100%" stop-color="rgba(120,150,255,0.22)" />
          </linearGradient>
          <linearGradient :id="pieceGradId" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="rgba(79,195,247,0.62)" />
            <stop offset="100%" stop-color="rgba(26,35,126,0.72)" />
          </linearGradient>
        </defs>

        <rect x="0" y="0" :width="svgBox.w" :height="svgBox.h"
              fill="url(#sheetGradX)" stroke="rgba(255,255,255,0.55)" stroke-width="1.5" />

        <g v-for="(r, i) in sr.layout" :key="i">
          <rect
            v-if="r.type === 'REMNANT'"
            :x="rectOf(r).x" :y="rectOf(r).y"
            :width="Math.max(1, rectOf(r).w)" :height="Math.max(1, rectOf(r).h)"
            :fill="`url(#${r.reusable ? hatchId : scrapHatchId})`"
            :stroke="r.reusable ? 'rgba(77,214,168,0.75)' : 'rgba(180,190,220,0.45)'"
            stroke-width="1.2"
            :stroke-dasharray="r.reusable ? 'none' : '4 3'"
          />
          <g v-else>
            <rect
              :x="rectOf(r).x" :y="rectOf(r).y" :width="rectOf(r).w" :height="rectOf(r).h"
              :fill="`url(#${pieceGradId})`"
              :stroke="r.rotated ? 'rgba(212,167,44,0.95)' : 'rgba(255,255,255,0.8)'"
              :stroke-width="r.rotated ? 1.8 : 1.2" rx="2"
            />
            <text
              v-if="rectOf(r).w > 46 && rectOf(r).h > 16"
              :x="rectOf(r).x + rectOf(r).w / 2" :y="rectOf(r).y + rectOf(r).h / 2 + 4"
              text-anchor="middle" font-size="11" fill="#ffffff" font-weight="700"
            >{{ r.label }}</text>
          </g>
        </g>

        <text v-if="stale" :x="svgBox.w / 2" :y="svgBox.h / 2"
              text-anchor="middle" fill="#ffd7a0" font-size="15" font-weight="700">
          旧版本结果 · 等待重算
        </text>
      </svg>
    </div>

    <div class="legend-row">
      <span class="legend"><i class="lg lg-piece"></i>出片</span>
      <span class="legend"><i class="lg lg-rot"></i>旋转90°片（金边）</span>
      <span class="legend"><i class="lg lg-reuse"></i>整块可复用余料</span>
      <span class="legend"><i class="lg lg-scrap"></i>碎料余料</span>
      <span class="legend"><i class="lg lg-kerf"></i>出片间隙即刀缝 {{ dual.kerfMm }}mm</span>
    </div>

    <div class="util-block">
      <div class="util-head">
        <span>{{ sr.strategyName }} · 利用率</span>
        <b :style="{ color: sr.utilizationRate < 70 ? '#ffb74d' : sr.utilizationRate > 95 ? '#4dd6a8' : '#4fc3f7' }">
          {{ sr.utilizationRate }}%
        </b>
        <span class="util-split">
          排入 {{ sr.placedCount }}/{{ sr.requestedCount }} 片 · 出片 {{ sr.placedAreaSqm }}㎡ / 原片 {{ dual.sourceAreaSqm }}㎡
        </span>
      </div>
      <div class="util-track">
        <i :style="{ width: Math.min(100, sr.utilizationRate) + '%',
                    background: `linear-gradient(90deg, #1a237e, ${sr.utilizationRate < 70 ? '#ffb74d' : sr.utilizationRate > 95 ? '#4dd6a8' : '#4fc3f7'})` }"></i>
        <span class="mark warn"></span>
        <span class="mark good"></span>
      </div>
      <div class="mini-stat-row">
        <div class="mini-card"><div class="k">余料总面积</div><div class="v">{{ sr.remnantAreaSqm }}㎡</div></div>
        <div class="mini-card">
          <div class="k">最大整块余料</div>
          <div class="v" style="color:#4dd6a8">{{ sr.remnantSpec === '0×0' ? '无达标整块' : sr.remnantSpec + 'mm' }}</div>
        </div>
        <div class="mini-card"><div class="k">整块面积/短边</div><div class="v">{{ sr.reusableAreaSqm }}㎡ / {{ Math.round(sr.reusableMinSideMm) }}mm</div></div>
        <div class="mini-card"><div class="k">刀缝碎料损耗</div><div class="v">{{ sr.kerfAreaSqm }}㎡</div></div>
      </div>
    </div>

    <div v-if="sr.timedOut" class="state-strip state-warn">
      搜索在 {{ dual.timeLimitMs }}ms 时限内未穷尽：当前为找到的最优排法，未排入项不保证一定放不下。
      可调大搜索时限、减少数量{{ dual.allowRotation ? '' : '或开启旋转' }}后重算。
    </div>

    <div v-if="sr.unplaced.length" class="sub-title bad-text">
      未排入项（{{ unplacedTotal() }} 片，真实几何判定，非总面积判定）
    </div>
    <table v-if="sr.unplaced.length" class="data-table bad-table">
      <thead>
        <tr><th>规格mm</th><th>未排片数</th><th>具体原因与可调整约束</th></tr>
      </thead>
      <tbody>
        <tr v-for="(u, i) in sr.unplaced" :key="i">
          <td>{{ u.lengthMm }}×{{ u.widthMm }}</td>
          <td>{{ u.missingQuantity }}</td>
          <td class="reason-cell">{{ u.reason }}</td>
        </tr>
      </tbody>
    </table>

    <div class="sub-title">余料矩形（整块门槛：短边≥300mm 且面积≥0.30㎡）</div>
    <table v-if="sr.remnants.length" class="data-table">
      <thead>
        <tr><th>位置(x,y)</th><th>规格mm</th><th>面积㎡</th><th>可复用整块</th><th>说明</th></tr>
      </thead>
      <tbody>
        <tr v-for="(rm, i) in sr.remnants" :key="i" :class="{ 'row-reuse': rm.reusable }">
          <td>{{ Math.round(rm.x) }},{{ Math.round(rm.y) }}</td>
          <td>{{ rm.spec }}</td>
          <td>{{ rm.areaSqm }}</td>
          <td>{{ rm.reusable ? '是' : '否' }}</td>
          <td class="reason-cell">{{ rm.note || '整块保留，可用于二次套裁' }}</td>
        </tr>
      </tbody>
    </table>
    <div v-else class="hint">无单独成块的余料（均为刀缝或窄缝碎料）</div>

    <div class="panel-desc hint">{{ sr.strategyDesc }}</div>
    <div class="adopt-row">
      <ElButton type="primary" size="small"
                :disabled="stale || loading || !sr.complete"
                @click="emit('adopt')">
        采用「{{ sr.strategyName }}」生成切割单
      </ElButton>
    </div>
  </div>
</template>

<style scoped>
.state-strip {
  border-radius: 10px; padding: 8px 12px; font-size: 12.5px; line-height: 1.6;
  margin: 8px 0; border: 1px solid;
}
.state-warn { background: rgba(232,163,61,0.12); border-color: rgba(232,163,61,0.45); color: #ffd7a0; }

.layout-wrap {
  border-radius: 12px; padding: 10px;
  background: linear-gradient(150deg, rgba(10,16,60,0.55), rgba(20,28,82,0.35));
  border: 1px solid rgba(255,255,255,0.18);
}
.layout-wrap.dimmed { opacity: 0.55; filter: saturate(0.7); }
.layout-svg { width: 100%; height: auto; display: block; max-height: 330px; }

.legend-row { display: flex; gap: 14px; flex-wrap: wrap; margin: 8px 2px 2px; font-size: 11px; color: var(--ink-dim); }
.legend { display: inline-flex; align-items: center; gap: 5px; }
.lg { width: 12px; height: 12px; border-radius: 3px; display: inline-block; border: 1px solid rgba(255,255,255,0.4); }
.lg-piece { background: linear-gradient(135deg, rgba(79,195,247,0.7), rgba(26,35,126,0.8)); }
.lg-rot { background: linear-gradient(135deg, rgba(79,195,247,0.7), rgba(26,35,126,0.8)); border-color: rgba(212,167,44,0.95); border-width: 2px; }
.lg-reuse { background: repeating-linear-gradient(45deg, rgba(77,214,168,0.55) 0 3px, transparent 3px 6px); }
.lg-scrap { background: repeating-linear-gradient(45deg, rgba(180,190,220,0.35) 0 2px, transparent 2px 5px); }
.lg-kerf { background: #0d1450; border-style: dashed; border-color: rgba(255,255,255,0.5); }

.util-block { margin-top: 10px; }
.util-head { display: flex; align-items: baseline; gap: 10px; font-size: 12px; color: var(--ink-soft); }
.util-head b { font-size: 20px; }
.util-split { margin-left: auto; color: var(--ink-dim); }
.util-track {
  position: relative; height: 13px; margin-top: 7px; border-radius: 8px; overflow: hidden;
  background: rgba(255,255,255,0.12); border: 1px solid rgba(255,255,255,0.2);
}
.util-track i { display: block; height: 100%; transition: width .35s ease; }
.util-track .mark { position: absolute; top: 0; bottom: 0; width: 2px; background: rgba(255,255,255,0.65); }
.util-track .warn { left: 70%; }
.util-track .good { left: 95%; }
.mini-stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 10px; }
.mini-card { padding: 8px 10px; border-radius: 10px; background: rgba(255,255,255,0.07); border: 1px solid var(--line); }
.mini-card .k { font-size: 10.5px; color: var(--ink-dim); }
.mini-card .v { font-size: 14px; font-weight: 800; color: #fff; margin-top: 2px; white-space: nowrap; }

.sub-title { font-size: 12.5px; font-weight: 700; color: #fff; margin: 12px 0 6px; }
.bad-text { color: #ff8a7a; }
.data-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.data-table th, .data-table td { border: 1px solid rgba(255,255,255,0.13); padding: 5px 8px; text-align: left; vertical-align: top; }
.data-table th { background: rgba(255,255,255,0.08); color: #aab6ee; }
.data-table .reason-cell { color: var(--ink-soft); line-height: 1.55; }
.bad-table :deep(td) { color: #ffc9c0; }
.row-reuse :deep(td) { color: #9af0d2; }
.panel-desc { margin-top: 10px; }
.adopt-row { margin-top: 10px; display: flex; justify-content: flex-end; }
</style>
