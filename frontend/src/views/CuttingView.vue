<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import router from '../router'
import StrategyPanel from '../components/StrategyPanel.vue'

const route = computed(() => router.currentRoute.value)
const isRank = computed(() => (route.value?.query?.view || '') === 'rank')

const stocks = ref([])
const orders = ref([])
const rank = ref([])
const stockId = ref(null)
const pieces = ref([])
const dual = ref(null)
const layoutNo = ref('')
const cutter = ref('数控切割线 CTL-01')
const operator = ref('张伟')
const submitting = ref(false)
const loading = ref(false)
const stale = ref(false)
const lastError = ref('')
const activeTab = ref('USAGE')
const timeLimitMs = ref(800)
const kerfMm = ref(2)
const allowRotation = ref(true)
const histDetail = ref(null)

const SVG_W = 560

// 版本归属：自增序号；较早发起但较晚返回的响应按序号丢弃，不占据当前版本
let reqSeq = 0

const currentStock = computed(() => stocks.value.find((s) => s.id === stockId.value) || null)

const sourceL = computed(() => dual.value?.sourceLengthMm || currentStock.value?.lengthMm || 2440)
const sourceW = computed(() => dual.value?.sourceWidthMm || currentStock.value?.widthMm || 1830)

const svgBox = computed(() => {
  const scale = SVG_W / sourceL.value
  return { w: SVG_W, h: Math.max(120, Math.round(sourceW.value * scale)), scale }
})

function rectOf(r) {
  const s = svgBox.value.scale
  return { x: r.x * s, y: r.y * s, w: r.w * s, h: r.h * s }
}

const usage = computed(() => dual.value?.usage || null)
const remnant = computed(() => dual.value?.remnant || null)
const recommendation = computed(() => dual.value?.recommendation || null)

const recommendedStrategy = computed(() => {
  const c = recommendation.value?.choice
  return c === 'USAGE' || c === 'REMNANT' ? c : null
})

// 输入指纹：任一关键参数变化 → 旧结果立即标过期并触发重算
const inputsSig = computed(() => JSON.stringify({
  stockId: stockId.value,
  kerf: kerfMm.value,
  rot: allowRotation.value,
  tl: timeLimitMs.value,
  pieces: pieces.value.map((p) => [p.lengthMm, p.widthMm, p.quantity])
}))

function rowInvalid(p) {
  return !p.lengthMm || !p.widthMm || !p.quantity
    || p.lengthMm <= 0 || p.widthMm <= 0 || p.quantity <= 0
}

function addPiece() {
  pieces.value.push({ lengthMm: 800, widthMm: 600, quantity: 2, remark: '' })
}

function removePiece(idx) {
  pieces.value.splice(idx, 1)
}

let timer = null
function schedulePreview() {
  clearTimeout(timer)
  timer = setTimeout(runPreview, 260)
}

function localValidationError() {
  if (!stockId.value) return '请先选择原片批次'
  if (!pieces.value.length) return ''
  const bad = pieces.value.some(rowInvalid)
  if (bad) return '出片长宽与数量必须为正整数，请修正标红行（已填内容已保留）'
  if (kerfMm.value == null || kerfMm.value < 0 || kerfMm.value > 100) {
    return '刀缝需在 0~100mm 之间'
  }
  return ''
}

async function runPreview() {
  if (!stockId.value || !pieces.value.length) {
    loading.value = false
    return
  }
  const err = localValidationError()
  if (err) {
    stale.value = true
    lastError.value = err
    return
  }
  lastError.value = ''
  const seq = ++reqSeq
  loading.value = true
  stale.value = !!dual.value
  try {
    const res = await api.cutting.dualPreview({
      stockId: stockId.value,
      pieces: pieces.value,
      kerfMm: kerfMm.value,
      allowRotation: allowRotation.value,
      timeLimitMs: timeLimitMs.value,
      clientSeq: seq
    })
    // 乱序防护：只接受最新一次请求的结果
    if (seq !== reqSeq) return
    dual.value = res
    stale.value = false
    if (recommendedStrategy.value) activeTab.value = recommendedStrategy.value
  } catch (e) {
    if (seq !== reqSeq) return
    // 尺寸无效 / 必需出片放不下：保留输入，旧结果保留并标过期，不做伪推荐
    lastError.value = e.message
    stale.value = true
    ElMessage.error(e.message)
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

function pickStock(id) {
  stockId.value = id
  const stock = stocks.value.find((s) => s.id === id)
  if (stock && !pieces.value.length) {
    pieces.value = [
      { lengthMm: 1200, widthMm: 900, quantity: 2, remark: '' },
      { lengthMm: 800, widthMm: 600, quantity: 3, remark: '' }
    ]
  }
}

async function submit(strategyArg) {
  if (!stockId.value) return ElMessage.warning('请选择原片批次')
  if (!pieces.value.length) return ElMessage.warning('请添加出片规格')
  const err = localValidationError()
  if (err) return ElMessage.warning(err)
  const strategy = strategyArg || recommendedStrategy.value
  if (!strategy) {
    return ElMessage.warning('两份方案为等价或均未排齐，请在对应方案卡片上点“采用此方案生成切割单”')
  }
  const sr = strategy === 'REMNANT' ? remnant.value : usage.value
  if (sr && !sr.complete) {
    try {
      await ElMessageBox.confirm(
        `「${sr.strategyName}」仍有 ${sr.requestedCount - sr.placedCount} 片未排入，提交会被后端拒绝。是否仍要尝试？`,
        '存在未排入出片', { type: 'warning', confirmButtonText: '仍要提交', cancelButtonText: '返回调整' })
    } catch {
      return
    }
  }
  submitting.value = true
  try {
    const saved = await api.cutting.create({
      stockId: stockId.value,
      layoutNo: layoutNo.value,
      cutter: cutter.value,
      operator: operator.value,
      kerfMm: kerfMm.value,
      allowRotation: allowRotation.value,
      strategy,
      pieces: pieces.value
    })
    ElMessage.success(`切割单 ${saved.orderNo} 已生成（${strategy === 'REMNANT' ? '整块余料优先' : '利用率优先'}），利用率 ${saved.utilizationRate}%`)
    await loadOrders()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

async function reopenOrder(row) {
  try {
    const detail = await api.cutting.detail(row.id)
    if (detail.order?.stockId && stocks.value.some((s) => s.id === detail.order.stockId)) {
      stockId.value = detail.order.stockId
    }
    if (Array.isArray(detail.pieces) && detail.pieces.length) {
      pieces.value = detail.pieces.map((p) => ({
        lengthMm: p.lengthMm,
        widthMm: p.widthMm,
        quantity: p.quantity,
        remark: p.remark || ''
      }))
    }
    if (detail.order?.kerfMm != null) kerfMm.value = detail.order.kerfMm
    if (detail.order?.allowRotation != null) allowRotation.value = !!detail.order.allowRotation
    if (detail.order?.strategy === 'REMNANT' || detail.order?.strategy === 'USAGE') {
      activeTab.value = detail.order.strategy
    }
    ElMessage.success(`已按切割单 ${row.orderNo} 的清单重新排样`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function showDetail(row) {
  try {
    histDetail.value = await api.cutting.detail(row.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function loadOrders() {
  orders.value = await api.cutting.list()
}

function utilRateColor(rate) {
  if (rate == null) return '#8c98cc'
  if (rate < 70) return '#ffb74d'
  if (rate > 95) return '#4dd6a8'
  return '#4fc3f7'
}

function pct(sr) {
  return sr ? sr.utilizationRate : 0
}

const compareRows = computed(() => {
  if (!dual.value) return []
  return [
    {
      key: '利用率',
      u: usage.value ? usage.value.utilizationRate + '%' : '-',
      r: remnant.value ? remnant.value.utilizationRate + '%' : '-'
    },
    {
      key: '排入片数',
      u: usage.value ? `${usage.value.placedCount}/${usage.value.requestedCount}` : '-',
      r: remnant.value ? `${remnant.value.placedCount}/${remnant.value.requestedCount}` : '-'
    },
    {
      key: '最大整块余料',
      u: usage.value ? `${usage.value.remnantSpec}mm · ${usage.value.reusableAreaSqm}㎡` : '-',
      r: remnant.value ? `${remnant.value.remnantSpec}mm · ${remnant.value.reusableAreaSqm}㎡` : '-'
    },
    { key: '余料总面积', u: usage.value ? usage.value.remnantAreaSqm + '㎡' : '-', r: remnant.value ? remnant.value.remnantAreaSqm + '㎡' : '-' },
    { key: '刀缝/碎料损耗', u: usage.value ? usage.value.kerfAreaSqm + '㎡' : '-', r: remnant.value ? remnant.value.kerfAreaSqm + '㎡' : '-' },
    {
      key: '未排入',
      u: usage.value ? (usage.value.unplaced.length ? usage.value.unplaced.reduce((a, i) => a + i.missingQuantity, 0) + ' 片' : '无') : '-',
      r: remnant.value ? (remnant.value.unplaced.length ? remnant.value.unplaced.reduce((a, i) => a + i.missingQuantity, 0) + ' 片' : '无') : '-'
    }
  ]
})

const recClass = computed(() => {
  const c = recommendation.value?.choice
  if (c === 'USAGE') return 'rec-usage'
  if (c === 'REMNANT') return 'rec-remnant'
  return 'rec-none'
})

onMounted(async () => {
  try {
    stocks.value = await api.stock.list()
    rank.value = await api.cutting.remnantRank()
    await loadOrders()
    if (stocks.value.length) pickStock(stocks.value[0].id)
  } catch (e) {
    lastError.value = e.message
    ElMessage.error(e.message)
  }
})

watch(inputsSig, () => {
  if (dual.value) stale.value = true
  schedulePreview()
}, { immediate: true })
</script>

<template>
  <div>
    <div class="view-head">
      <div class="view-title">切割与优化 · 双策略布局</div>
      <div class="view-tip">
        同一原片批次 / 出片清单 / 刀缝规则下同时给出「利用率优先」与「整块余料优先」两份排样；
        改任一参数旧结果立即标过期，乱序返回自动丢弃
      </div>
    </div>

    <!-- 余料排行子功能 -->
    <div v-if="isRank" class="glass glass-pad" style="margin-bottom: 14px">
      <div class="section-title">原片余料排行（可二次套裁）</div>
      <el-table :data="rank" size="small" stripe>
        <el-table-column prop="orderNo" label="切割单号" width="180" />
        <el-table-column prop="batchNo" label="原片批次" width="130" />
        <el-table-column prop="glassTypeName" label="玻璃种类" width="110" />
        <el-table-column prop="remnantAreaSqm" label="余料面积㎡" width="110" />
        <el-table-column prop="utilizationRate" label="利用率%" width="100" />
        <el-table-column label="余料占比">
          <template #default="{ row }">
            <div class="rank-bar"><i :style="{ width: Math.min(100, 100 - Number(row.utilizationRate)) + '%' }"></i></div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 原片选择玻璃条 -->
    <div class="glass-bar">
      <span class="glass-bar-label">原片批次</span>
      <el-select v-model="stockId" size="small" style="width: 320px" @change="pickStock">
        <el-option
          v-for="s in stocks" :key="s.id"
          :label="s.batchNo + ' · ' + s.specText + ' · ' + s.sheetCount + '片'"
          :value="s.id"
        />
      </el-select>
      <span class="glass-bar-label" v-if="currentStock">
        {{ currentStock.glassType }} · 单版 {{ (currentStock.lengthMm * currentStock.widthMm / 1000000).toFixed(3) }} ㎡
      </span>
      <span style="margin-left: auto" class="glass-bar-label">
        刀缝 {{ kerfMm }}mm · 旋转 {{ allowRotation ? '允许' : '禁止' }}
      </span>
    </div>

    <div class="grid-2-wide">
      <!-- 左：双策略结果 -->
      <div class="glass glass-pad result-col">
        <!-- 过期 / 错误 / 计算中状态条 -->
        <div v-if="lastError" class="state-strip state-error">
          <b>无法完成计算：</b>{{ lastError }}
          <span class="state-sub">输入已保留，可调整规格、数量、刀缝、原片幅面或旋转选项后自动重算。</span>
        </div>
        <div v-else-if="stale || loading" class="state-strip state-stale">
          {{ dual ? '原片规格 / 出片数量 / 旋转选项已变化，以下为旧版本结果（已标过期）' : '正在计算双策略排样…' }}
          <span v-if="loading" class="state-sub">进行中，较早发起的迟到结果会被自动丢弃，不会覆盖当前版本。</span>
        </div>

        <template v-if="dual">
          <!-- 双策略对比表 -->
          <div class="section-title">双策略对比（同批次 / 同清单 / 同刀缝 {{ dual.kerfMm }}mm）</div>
          <table class="cmp-table">
            <thead>
              <tr>
                <th style="width: 130px"></th>
                <th :class="{ on: activeTab === 'USAGE' }">利用率优先</th>
                <th :class="{ on: activeTab === 'REMNANT' }">整块余料优先</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in compareRows" :key="row.key">
                <td class="cmp-k">{{ row.key }}</td>
                <td :class="{ bad: row.key === '未排入' && row.u !== '无' && row.u !== '-' }">{{ row.u }}</td>
                <td :class="{ bad: row.key === '未排入' && row.r !== '无' && row.r !== '-' }">{{ row.r }}</td>
              </tr>
            </tbody>
          </table>

          <!-- 推荐口径 -->
          <div class="rec-banner" :class="recClass">
            <div class="rec-head">
              <span class="rec-tag">
                {{ recommendation?.choice === 'USAGE' ? '推荐：利用率优先'
                  : recommendation?.choice === 'REMNANT' ? '推荐：整块余料优先'
                  : '推荐：中立（不强推任一份）' }}
              </span>
              <span class="rec-gap" v-if="recommendation">
                利用率差 {{ recommendation.utilizationGapPct }} 个百分点 · 整块余料差 {{ recommendation.reusableGapSqm }}㎡
              </span>
            </div>
            <div class="rec-reason">{{ recommendation?.reason }}</div>
            <el-collapse class="rec-policy">
              <el-collapse-item title="推荐口径与边界（固定阈值带 + 综合适配）">
                <div class="policy-text">{{ recommendation?.policy }}</div>
              </el-collapse-item>
            </el-collapse>
          </div>

          <el-tabs v-model="activeTab" class="strategy-tabs">
            <el-tab-pane label="利用率优先" name="USAGE" />
            <el-tab-pane label="整块余料优先" name="REMNANT" />
          </el-tabs>

          <StrategyPanel
            v-if="usage && activeTab === 'USAGE'"
            variant="USAGE"
            :sr="usage" :dual="dual" :svg-box="svgBox" :rect-of="rectOf"
            :stale="stale" :loading="loading"
            @adopt="submit('USAGE')"
          />
          <StrategyPanel
            v-if="remnant && activeTab === 'REMNANT'"
            variant="REMNANT"
            :sr="remnant" :dual="dual" :svg-box="svgBox" :rect-of="rectOf"
            :stale="stale" :loading="loading"
            @adopt="submit('REMNANT')"
          />

          <div class="version-line" v-if="dual">
            计算版本 #{{ dual.calculationId }} · 请求序号 {{ dual.clientSeq }} ·
            {{ dual.allowRotation ? '允许旋转' : '禁止旋转' }} · 搜索时限 {{ dual.timeLimitMs }}ms/策略
          </div>
        </template>

        <div v-else-if="!lastError" class="empty-result">
          <el-icon :size="26" style="margin-right: 8px"><svg width="26" height="26" viewBox="0 0 24 24" fill="none"><rect x="3" y="3" width="18" height="18" rx="2" stroke="#8c98cc" stroke-width="1.6"/><path d="M3 9h18M9 9v12" stroke="#8c98cc" stroke-width="1.6"/></svg></el-icon>
          选择原片批次并添加出片规格后，此处同时给出两份排样
        </div>
      </div>

      <!-- 右：规则 + 出片清单 -->
      <div class="glass glass-pad">
        <div class="section-title">切割规则</div>
        <div class="rule-grid">
          <div class="rule-item">
            <span>刀缝（锯路）mm</span>
            <el-input-number v-model="kerfMm" :min="0" :max="100" :step="1" size="small" controls-position="right" />
          </div>
          <div class="rule-item">
            <span>允许旋转 90°</span>
            <el-switch v-model="allowRotation" inline-prompt active-text="允许" inactive-text="禁止" />
          </div>
          <div class="rule-item">
            <span>单策略搜索时限</span>
            <el-select v-model="timeLimitMs" size="small" style="width: 130px">
              <el-option :value="100" label="100ms（快）" />
              <el-option :value="300" label="300ms" />
              <el-option :value="800" label="800ms（默认）" />
              <el-option :value="2000" label="2000ms（精）" />
            </el-select>
          </div>
        </div>
        <div class="hint" style="margin: 6px 0 12px">
          两份排样共用同一刀缝与旋转规则；相邻出片之间按刀缝留缝，外沿不额外吃刀缝。
          旋转后仍放不下的出片会计入“未排入”，不会因总面积足够而判成功。
        </div>

        <div class="section-title">
          出片规格清单
          <el-button size="small" style="margin-left: auto" @click="addPiece">+ 添加规格</el-button>
        </div>
        <div class="piece-head">
          <span>长 mm</span><span>宽 mm</span><span>数量</span><span>面积㎡</span><span></span>
        </div>
        <div class="piece-list">
          <div v-for="(p, i) in pieces" :key="i" class="piece-row" :class="{ invalid: rowInvalid(p) }">
            <el-input-number v-model="p.lengthMm" :min="1" :max="6000" :step="10" size="small" controls-position="right" />
            <el-input-number v-model="p.widthMm" :min="1" :max="4000" :step="10" size="small" controls-position="right" />
            <el-input-number v-model="p.quantity" :min="1" :max="200" :step="1" size="small" controls-position="right" />
            <span class="piece-area">
              {{ (((p.lengthMm || 0) * (p.widthMm || 0) * (p.quantity || 0)) / 1000000).toFixed(4) }}
            </span>
            <el-button size="small" type="danger" plain @click="removePiece(i)">删</el-button>
          </div>
          <div v-if="!pieces.length" class="piece-empty">暂无出片规格，点右上角添加</div>
        </div>

        <el-divider content-position="left">生成切割单</el-divider>
        <div class="form-grid">
          <el-input v-model="layoutNo" size="small" placeholder="布局图号（可留空）" />
          <el-input v-model="cutter" size="small" placeholder="切割机" />
          <el-input v-model="operator" size="small" placeholder="操作员" />
        </div>
        <div class="submit-row">
          <el-button size="small" type="primary" :loading="submitting"
                     :disabled="!!stale || !recommendedStrategy" @click="submit(null)">
            按推荐生成{{ recommendedStrategy === 'REMNANT' ? '（余料优先）' : recommendedStrategy === 'USAGE' ? '（利用率优先）' : '' }}
          </el-button>
          <el-button size="small" :loading="submitting" :disabled="!!stale" @click="submit(activeTab)">
            采用当前方案生成（{{ activeTab === 'REMNANT' ? '余料优先' : '利用率优先' }}）
          </el-button>
        </div>
        <div class="hint">
          规则：尺寸非法直接拦截；必需出片几何上放不下（含刀缝）或搜索超时时不做伪推荐，请按未排入原因调整。
          两份方案等价或都不完整时保持中立，由人工选择采用哪份。
        </div>
      </div>
    </div>

    <!-- 已有切割单 -->
    <div class="glass glass-pad" style="margin-top: 14px">
      <div class="section-title">切割单台账（原有切割单继续可用，可一键按旧单重新排样）</div>
      <el-table :data="orders" size="small" stripe max-height="260">
        <el-table-column prop="orderNo" label="切割单号" width="170" />
        <el-table-column prop="batchNo" label="原片批次" width="120" />
        <el-table-column prop="layoutNo" label="布局图号" width="100" />
        <el-table-column label="方案" width="90">
          <template #default="{ row }">
            {{ row.strategy === 'REMNANT' ? '余料优先' : (row.strategy === 'USAGE' ? '利用率优先' : '单策略') }}
          </template>
        </el-table-column>
        <el-table-column prop="outputCount" label="出片数" width="70" />
        <el-table-column prop="sourceAreaSqm" label="原片㎡" width="84" />
        <el-table-column prop="outputAreaSqm" label="出片㎡" width="84" />
        <el-table-column label="利用率%" width="90">
          <template #default="{ row }">
            <span :style="{ color: utilRateColor(Number(row.utilizationRate)), fontWeight: 700 }">
              {{ row.utilizationRate }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="remnantSpec" label="余料规格" width="100" />
        <el-table-column label="刀缝" width="64">
          <template #default="{ row }">{{ row.kerfMm != null ? row.kerfMm + 'mm' : '-' }}</template>
        </el-table-column>
        <el-table-column prop="cutter" label="切割机" />
        <el-table-column prop="status" label="状态" width="84" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="showDetail(row)">明细</el-button>
            <el-button size="small" link type="primary" @click="reopenOrder(row)">重新排样</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 历史切割单明细 -->
    <el-drawer v-model="histDetail" title="切割单明细（历史单据只读）" size="420px"
               :with-header="true" @update:model-value="(v) => !v && (histDetail = null)">
      <template v-if="histDetail">
        <div class="hist-block">
          <div><b>{{ histDetail.order.orderNo }}</b> · {{ histDetail.order.batchNo }} · {{ histDetail.order.layoutNo }}</div>
          <div class="hint">
            方案：{{ histDetail.order.strategy === 'REMNANT' ? '整块余料优先' : (histDetail.order.strategy === 'USAGE' ? '利用率优先' : '旧版单策略') }}
            · 刀缝 {{ histDetail.order.kerfMm != null ? histDetail.order.kerfMm + 'mm' : '未记录（按0）' }}
            · 旋转 {{ histDetail.order.allowRotation === false ? '禁止' : '允许' }}
          </div>
          <div class="hint">
            原片 {{ histDetail.order.sourceAreaSqm }}㎡ / 出片 {{ histDetail.order.outputAreaSqm }}㎡
            / 利用率 {{ histDetail.order.utilizationRate }}% / 余料 {{ histDetail.order.remnantSpec }}mm
          </div>
        </div>
        <el-table :data="histDetail.pieces" size="small" stripe style="margin-top: 10px">
          <el-table-column prop="lengthMm" label="长mm" width="80" />
          <el-table-column prop="widthMm" label="宽mm" width="80" />
          <el-table-column prop="quantity" label="数量" width="70" />
          <el-table-column prop="areaSqm" label="面积㎡" />
          <el-table-column prop="remark" label="备注" />
        </el-table>
        <el-button size="small" type="primary" style="margin-top: 14px" @click="reopenOrder(histDetail.order); histDetail = null">
          按此单清单重新排样
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>


<style scoped>
.grid-2-wide { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr); gap: 14px; align-items: start; }
.result-col { min-height: 420px; }

.state-strip {
  border-radius: 10px; padding: 8px 12px; font-size: 12.5px; line-height: 1.6;
  margin-bottom: 10px; border: 1px solid;
}
.state-stale { background: rgba(232,163,61,0.16); border-color: rgba(232,163,61,0.55); color: #ffd7a0; }
.state-error { background: rgba(228,87,61,0.18); border-color: rgba(228,87,61,0.55); color: #ffd7cf; }
.state-warn { background: rgba(232,163,61,0.12); border-color: rgba(232,163,61,0.45); color: #ffd7a0; margin: 8px 0; }
.state-sub { display: block; color: rgba(255,255,255,0.7); font-size: 11.5px; }

.cmp-table { width: 100%; border-collapse: collapse; font-size: 12.5px; margin-bottom: 10px; }
.cmp-table th, .cmp-table td { border: 1px solid rgba(255,255,255,0.14); padding: 6px 10px; text-align: center; }
.cmp-table th { background: rgba(255,255,255,0.08); color: #aab6ee; font-weight: 700; }
.cmp-table th.on { color: #4dd6a8; box-shadow: inset 0 -2px 0 #4dd6a8; }
.cmp-table .cmp-k { color: var(--ink-dim); text-align: left; background: rgba(255,255,255,0.04); }
.cmp-table td.bad { color: #ff8a7a; font-weight: 700; }

.rec-banner { border-radius: 12px; padding: 10px 14px; margin: 8px 0 6px; border: 1px solid; }
.rec-usage { background: rgba(79,195,247,0.12); border-color: rgba(79,195,247,0.5); }
.rec-remnant { background: rgba(77,214,168,0.12); border-color: rgba(77,214,168,0.5); }
.rec-none { background: rgba(255,255,255,0.06); border-color: rgba(255,255,255,0.22); }
.rec-head { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.rec-tag { font-weight: 800; color: #fff; font-size: 13.5px; }
.rec-gap { font-size: 11.5px; color: var(--ink-dim); margin-left: auto; }
.rec-reason { font-size: 12.5px; color: var(--ink-soft); margin-top: 5px; line-height: 1.65; }
.rec-policy { margin-top: 4px; }
.rec-policy :deep(.el-collapse-item__header) { background: transparent; border: none; color: var(--ink-dim); font-size: 11.5px; height: 26px; }
.rec-policy :deep(.el-collapse-item__wrap) { background: transparent; border: none; }
.rec-policy :deep(.el-collapse-item__content) { padding-bottom: 4px; }
.policy-text { font-size: 11.5px; color: var(--ink-dim); line-height: 1.7; }

.strategy-tabs { margin-top: 2px; }
.strategy-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }

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
.bad-table tr td { color: #ffc9c0; }
.row-reuse td { color: #9af0d2; }
.panel-desc { margin-top: 10px; }
.adopt-row { margin-top: 10px; display: flex; justify-content: flex-end; }
.version-line { font-size: 10.5px; color: var(--ink-dim); margin-top: 10px; text-align: right; }
.empty-result { display: flex; align-items: center; justify-content: center; min-height: 360px; color: var(--ink-dim); font-size: 13px; }

.rule-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 12px; align-items: center; }
.rule-item { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; color: var(--ink-soft); }

.piece-head, .piece-row {
  display: grid; grid-template-columns: 1fr 1fr 1fr 78px 44px; gap: 7px; align-items: center;
}
.piece-head { font-size: 11px; color: var(--ink-dim); padding: 0 2px 6px; }
.piece-list { max-height: 240px; overflow: auto; }
.piece-row { margin-bottom: 7px; }
.piece-row.invalid :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px rgba(228,87,61,0.8) inset !important; }
.piece-area { font-size: 12px; color: #4fc3f7; font-weight: 700; text-align: center; }
.piece-empty { font-size: 12px; color: var(--ink-dim); padding: 12px 2px; }

.form-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; margin-bottom: 8px; }
.submit-row { display: flex; gap: 8px; flex-wrap: wrap; }
.hint { font-size: 11px; color: var(--ink-dim); margin-top: 8px; line-height: 1.6; }
.rank-bar { height: 7px; border-radius: 5px; background: rgba(255,255,255,0.12); overflow: hidden; }
.rank-bar i { display: block; height: 100%; background: linear-gradient(90deg, #ffb74d, #e4573d); }
.hist-block b { font-size: 14px; }
</style>
