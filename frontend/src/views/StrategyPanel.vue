<script setup>
import { computed } from 'vue'

const props = defineProps({
  strategy: { type: Object, required: true },
  result: { type: Object, required: true },
  kind: { type: String, required: true }, // util / rem
  stale: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false }
})
const emit = defineEmits(['cut'])

const SVG_W = 520

const box = computed(() => {
  const len = props.result.sourceLengthMm || 2440
  const wid = props.result.sourceWidthMm || 1830
  const scale = SVG_W / len
  return { w: SVG_W, h: Math.round(wid * scale), scale }
})

function ro(r) {
  const s = box.value.scale
  return { x: r.x * s, y: r.y * s, w: r.w * s, h: r.h * s }
}

function utilColor(rate) {
  return rate < 70 ? '#ffb74d' : rate > 95 ? '#4dd6a8' : '#4fc3f7'
}

const title = computed(() => props.strategy.strategy === 'UTILIZATION'
  ? '方案 A · 利用率优先（自由矩形排布）'
  : '方案 B · 整块余料优先（断头台一刀可分）')

const cutDisabled = computed(() =>
  props.strategy.unplacedCount > 0 || props.strategy.limited || props.stale)

const cutText = computed(() => {
  if (props.submitting) return '生成中…'
  if (props.stale) return '结果已过期'
  if (props.strategy.limited) return '搜索超时·不可下刀'
  if (props.strategy.unplacedCount > 0) return '存在未排入项·不可下刀'
  return '按此方案生成切割单'
})

function areaOf(r) {
  return (r.w * r.h / 1_000_000).toFixed(3)
}
</script>

<template>
  <div class="glass glass-pad strategy-panel"
       :class="[kind === 'util' ? 'panel-u' : 'panel-r', { stale }]">
    <div class="section-title panel-title">
      {{ title }}
      <el-tag v-if="stale" size="small" type="warning" effect="dark" style="margin-left: auto">已过期</el-tag>
      <el-tag v-else-if="strategy.limited" size="small" type="danger" effect="dark" style="margin-left: auto">结果不完整</el-tag>
    </div>

    <div class="layout-wrap">
      <svg :viewBox="`0 0 ${box.w} ${box.h}`" class="layout-svg">
        <defs>
          <pattern :id="`remHatch-${kind}`" width="9" height="9"
                   patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="9" height="9" fill="rgba(255,255,255,0.05)" />
            <line x1="0" y1="0" x2="0" y2="9" stroke="rgba(255,183,77,0.55)" stroke-width="2.2" />
          </pattern>
          <pattern :id="`scrapHatch-${kind}`" width="6" height="6"
                   patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="6" height="6" fill="rgba(255,255,255,0.03)" />
            <line x1="0" y1="0" x2="0" y2="6" stroke="rgba(160,160,180,0.35)" stroke-width="1.5" />
          </pattern>
        </defs>

        <rect x="0" y="0" :width="box.w" :height="box.h"
              fill="rgba(150,200,255,0.10)" stroke="rgba(255,255,255,0.55)" stroke-width="1.5" />

        <g v-for="(r, i) in strategy.layout" :key="i">
          <rect v-if="r.type === 'REMNANT'"
                :x="ro(r).x" :y="ro(r).y" :width="ro(r).w" :height="ro(r).h"
                :fill="`url(#remHatch-${kind})`" stroke="rgba(255,183,77,0.65)" stroke-width="1" />
          <rect v-else-if="r.type === 'SCRAP'"
                :x="ro(r).x" :y="ro(r).y" :width="ro(r).w" :height="ro(r).h"
                :fill="`url(#scrapHatch-${kind})`" stroke="rgba(170,170,190,0.4)" stroke-width="0.8" />
          <g v-else>
            <rect
              :x="ro(r).x" :y="ro(r).y" :width="ro(r).w" :height="ro(r).h"
              :fill="kind === 'util' ? 'rgba(79,155,247,0.55)' : 'rgba(120,110,230,0.55)'"
              stroke="rgba(255,255,255,0.8)" stroke-width="1.1" rx="2"
            />
            <text
              v-if="ro(r).w > 46 && ro(r).h > 16"
              :x="ro(r).x + ro(r).w / 2" :y="ro(r).y + ro(r).h / 2 + 4"
              text-anchor="middle" font-size="10.5" fill="#fff" font-weight="700"
            >{{ r.label }}</text>
          </g>
        </g>
      </svg>
    </div>

    <div class="metric-grid">
      <div class="metric">
        <label>名义利用率</label>
        <b :style="{ color: utilColor(strategy.utilizationRate) }">{{ strategy.utilizationRate }}%</b>
      </div>
      <div class="metric">
        <label>有效利用率</label>
        <b :style="{ color: utilColor(strategy.effectiveRate) }">{{ strategy.effectiveRate }}%</b>
      </div>
      <div class="metric">
        <label>放入片数</label>
        <b>{{ strategy.placedCount }}/{{ strategy.requestedCount }}</b>
      </div>
      <div class="metric">
        <label>刀缝损耗</label>
        <b>{{ strategy.kerfAreaSqm }}㎡</b>
      </div>
    </div>

    <div class="rect-list">
      <div class="rect-list-title">
        可复用整块余料（最短边≥300mm 且 ≥0.25㎡）：{{ strategy.remnantRects.length }} 块，
        合计 {{ strategy.reusableAreaSqm }}㎡，最大 {{ strategy.remnantSpec }}mm
      </div>
      <span v-for="(r, i) in strategy.remnantRects" :key="'r' + i" class="rect-chip rem">
        {{ r.label }} = {{ areaOf(r) }}㎡
      </span>
      <div v-if="strategy.scrapRects.length" class="rect-list-title">
        碎余料 {{ strategy.scrapRects.length }} 块，合计 {{ strategy.scrapAreaSqm }}㎡（不可整块复用）
      </div>
    </div>

    <div v-if="strategy.unplacedCount" class="unplaced-box">
      <div class="unplaced-title">
        {{ strategy.unplacedCount }} 片未排入<span v-if="strategy.limited">（搜索超时，结果不完整）</span>：
      </div>
      <div v-for="(u, i) in strategy.unplacedItems" :key="i" class="unplaced-item">
        <el-tag size="small" :type="u.reasonCode === 'TIMEOUT' ? 'warning' : 'danger'" effect="plain">
          {{ u.reasonCode }}
        </el-tag>
        <span class="up-spec">{{ u.lengthMm }}×{{ u.widthMm }}mm</span>
        <span class="up-reason">{{ u.reason }}</span>
      </div>
    </div>
    <div v-else class="all-placed">
      ✔ 全部 {{ strategy.placedCount }} 片真实几何放入（含 {{ result.kerfMm }}mm 刀缝）
    </div>

    <div class="cut-row">
      <button class="cut-btn" :class="kind === 'util' ? 'cut-u' : 'cut-r'"
              :disabled="cutDisabled" @click="emit('cut')">
        {{ cutText }}
      </button>
      <span class="plan-msg">{{ strategy.message }}</span>
    </div>
  </div>
</template>

<style scoped>
.layout-wrap {
  border-radius: 12px; padding: 10px;
  background: linear-gradient(150deg, rgba(10, 16, 60, 0.55), rgba(20, 28, 82, 0.35));
  border: 1px solid rgba(255, 255, 255, 0.18);
}
.layout-svg { width: 100%; height: auto; display: block; max-height: 300px; }

.strategy-panel { position: relative; }
.panel-u { border-top: 3px solid rgba(79,155,247,0.65); }
.panel-r { border-top: 3px solid rgba(255,183,77,0.7); }
.strategy-panel.stale::after {
  content: '已过期'; position: absolute; top: 44px; right: 12px; z-index: 3;
  font-size: 11px; font-weight: 700; color: #2a1d04; background: #ffb74d;
  padding: 2px 8px; border-radius: 6px;
}
.strategy-panel.stale .layout-wrap { opacity: 0.55; filter: grayscale(0.4); }
.panel-title { font-size: 13.5px; display: flex; align-items: center; }

.metric-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 10px; }
.metric { background: rgba(255,255,255,0.06); border-radius: 8px; padding: 7px 9px; }
.metric label { display: block; font-size: 10.5px; color: var(--ink-dim); }
.metric b { font-size: 16px; }

.rect-list { margin-top: 10px; }
.rect-list-title { font-size: 11.5px; color: var(--ink-soft); margin: 6px 0 4px; }
.rect-chip { display: inline-block; font-size: 11px; margin: 2px 6px 2px 0; padding: 3px 8px; border-radius: 7px; }
.rect-chip.rem { background: rgba(255,183,77,0.16); border: 1px solid rgba(255,183,77,0.5); color: #ffd28c; }

.unplaced-box {
  margin-top: 10px; padding: 8px 10px; border-radius: 9px;
  background: rgba(228, 87, 61, 0.13); border: 1px solid rgba(228, 87, 61, 0.45);
}
.unplaced-title { font-size: 12px; font-weight: 700; color: #ffb3a6; margin-bottom: 4px; }
.unplaced-item { font-size: 11.5px; color: var(--ink-soft); line-height: 1.9; display: flex; align-items: baseline; gap: 6px; }
.up-spec { font-weight: 700; color: #ffd28c; white-space: nowrap; }
.up-reason { line-height: 1.6; }
.all-placed {
  margin-top: 10px; font-size: 12px; color: #8fe0bd;
  background: rgba(47,185,138,0.12); border: 1px solid rgba(47,185,138,0.4);
  padding: 7px 10px; border-radius: 9px;
}
.cut-row { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.cut-btn {
  border: none; border-radius: 9px; padding: 8px 14px; font-size: 12.5px; font-weight: 700;
  color: #fff; cursor: pointer; white-space: nowrap;
}
.cut-u { background: linear-gradient(90deg, #1a237e, #3f7ce0); }
.cut-r { background: linear-gradient(90deg, #b8771a, #e8a33d); }
.cut-btn:disabled { background: rgba(140,152,204,0.35); color: rgba(255,255,255,0.55); cursor: not-allowed; }
.plan-msg { font-size: 10.5px; color: var(--ink-dim); line-height: 1.5; }
</style>
