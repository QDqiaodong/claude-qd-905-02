<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import router from '../router'
import StrategyPanel from './StrategyPanel.vue'

const route = computed(() => router.currentRoute.value)
const isRank = computed(() => (route.value?.query?.view || '') === 'rank')

const stocks = ref([])
const orders = ref([])
const rank = ref([])
const stockId = ref(null)
const pieces = ref([])
const result = ref(null)
const layoutNo = ref('')
const cutter = ref('数控切割线 CTL-01')
const operator = ref('张伟')
const submitting = ref(false)

// 刀缝与旋转（两份策略共用同一规则）
const kerfMm = ref(3)
const allowRotation = ref(true)

// 请求版本：较早发起但较晚返回的计算不得占据当前版本
let reqSeq = 0
let timer = null
const loading = ref(false)
const stale = ref(false)
const requestError = ref('')
let lastFingerprint = ''

const currentStock = computed(() => stocks.value.find((s) => s.id === stockId.value) || null)

function fingerprint() {
  return JSON.stringify({
    stockId: stockId.value,
    kerf: kerfMm.value,
    rot: allowRotation.value,
    pieces: pieces.value
  })
}

function addPiece() {
  pieces.value.push({ lengthMm: 800, widthMm: 600, quantity: 2, remark: '', allowRotation: true })
}

function removePiece(idx) {
  pieces.value.splice(idx, 1)
}

function markStale() {
  stale.value = true
}

function schedulePreview() {
  clearTimeout(timer)
  timer = setTimeout(runPreview, 240)
}

async function runPreview() {
  if (!stockId.value || !pieces.value.length) {
    result.value = null
    requestError.value = ''
    stale.value = false
    lastFingerprint = ''
    return
  }
  const seq = ++reqSeq
  const fp = fingerprint()
  stale.value = false
  requestError.value = ''
  loading.value = true
  try {
    const data = await api.cutting.preview({
      stockId: stockId.value,
      kerfMm: Number(kerfMm.value),
      allowRotation: allowRotation.value,
      pieces: pieces.value
    })
    // 版本归属：只有最新一次请求能写当前结果
    if (seq !== reqSeq) return
    result.value = data
    lastFingerprint = fp
  } catch (e) {
    if (seq !== reqSeq) return
    // 尺寸无效等整体错误：保留输入与上一次结果（若有），只提示可调整的约束
    requestError.value = e.message
    stale.value = true
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

// 输入变化（原片规格、出片数量、旋转选项、刀缝）：先把当前结果标为过期，再防抖重算
watch([stockId, pieces, kerfMm, allowRotation], () => {
  if (result.value && fingerprint() !== lastFingerprint) markStale()
  schedulePreview()
}, { deep: true })

function pickStock(id) {
  stockId.value = id
  const stock = stocks.value.find((s) => s.id === id)
  if (stock && !pieces.value.length) {
    pieces.value = [
      { lengthMm: 1200, widthMm: 900, quantity: 2, remark: '', allowRotation: true },
      { lengthMm: 800, widthMm: 600, quantity: 3, remark: '', allowRotation: true }
    ]
  }
  // stockId / pieces 的 watcher 会统一完成「标过期 → 防抖重算」，避免重复请求
}

const rec = computed(() => result.value?.recommendation || null)
const recClass = computed(() => {
  const code = rec.value?.code
  if (code === 'UTILIZATION') return 'rec-u'
  if (code === 'REMNANT') return 'rec-r'
  if (code === 'NEUTRAL') return 'rec-n'
  return 'rec-x'
})
const recTitle = computed(() => ({
  UTILIZATION: '推荐：利用率优先',
  REMNANT: '推荐：整块余料优先',
  NEUTRAL: '中立：两策略等价'
}[rec.value?.code] || '无推荐'))

async function submit(strategy) {
  if (!stockId.value) return ElMessage.warning('请选择原片批次')
  if (!pieces.value.length) return ElMessage.warning('请添加出片规格')
  if (!result.value || stale.value) return ElMessage.warning('结果已过期，请等待重算完成后再下刀')
  const plan = strategy === 'REMNANT' ? result.value.remnant : result.value.utilization
  if (plan.limited || plan.unplacedCount > 0) {
    return ElMessage.warning(`该方案有 ${plan.unplacedCount} 片未排入${plan.limited ? '（搜索超时）' : ''}，不能生成切割单`)
  }
  submitting.value = strategy
  try {
    const saved = await api.cutting.create({
      stockId: stockId.value,
      layoutNo: layoutNo.value,
      cutter: cutter.value,
      operator: operator.value,
      kerfMm: Number(kerfMm.value),
      strategy,
      pieces: pieces.value
    })
    ElMessage.success(`切割单 ${saved.orderNo} 已生成（${saved.strategyName || ''}，利用率 ${saved.utilizationRate}%）`)
    await loadOrders()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

async function loadOrders() {
  orders.value = await api.cutting.list()
}

onMounted(async () => {
  try {
    stocks.value = await api.stock.list()
    rank.value = await api.cutting.remnantRank()
    await loadOrders()
    if (stocks.value.length) pickStock(stocks.value[0].id)
  } catch (e) {
    ElMessage.error(e.message)
  }
})
</script>

<template>
  <div>
    <div class="view-head">
      <div class="view-title">切割与优化 · 双策略布局</div>
      <div class="view-tip">同一原片 / 清单 / 刀缝同时给「利用率优先」与「整块余料优先」两份结果；几何放不进就如实记未排入</div>
    </div>

    <!-- 余料排行子功能（原有） -->
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

    <!-- 原片与规则条 -->
    <div class="glass-bar">
      <span class="glass-bar-label">原片批次</span>
      <el-select v-model="stockId" size="small" style="width: 300px" @change="pickStock">
        <el-option
          v-for="s in stocks" :key="s.id"
          :label="s.batchNo + ' · ' + s.specText + ' · ' + s.sheetCount + '片'"
          :value="s.id"
        />
      </el-select>
      <span class="glass-bar-label" v-if="currentStock">
        {{ currentStock.glassType }} · 单版 {{ (currentStock.lengthMm * currentStock.widthMm / 1000000).toFixed(3) }} ㎡
      </span>
      <span class="glass-bar-label rule-item">
        刀缝
        <el-input-number v-model="kerfMm" :min="0" :max="20" :step="1" size="small"
                         controls-position="right" style="width: 96px; margin: 0 4px" />
        mm
      </span>
      <span class="glass-bar-label rule-item">
        <el-switch v-model="allowRotation" size="small" style="margin: 0 6px" />
        允许 90° 旋转
      </span>
      <span style="margin-left: auto" class="glass-bar-label">
        <el-tag v-if="loading" size="small" type="info" effect="dark">计算中…</el-tag>
        <el-tag v-else-if="stale" size="small" type="warning" effect="dark">结果已过期·重算中</el-tag>
        <el-tag v-else-if="result" size="small" type="success" effect="dark">当前版本</el-tag>
      </span>
    </div>

    <div v-if="requestError" class="util-error" style="margin: 8px 0">
      输入问题：{{ requestError }}　——输入已保留，可调整尺寸、刀缝或原片后自动重算，不会给出伪推荐。
    </div>

    <!-- 推荐口径 -->
    <div v-if="result" class="glass glass-pad rec-panel" :class="recClass">
      <div class="rec-head">
        <span class="rec-badge">{{ recTitle }}</span>
        <span class="rec-text">{{ rec.text }}</span>
      </div>
      <div class="rec-bound">
        推荐边界（固定阈值，现场可理解）：两份都放全时，有效利用率（出片＋可复用整块余料）相差
        <b>＞2 个点</b>取利用率方案；最大整块余料短边相差<b>＞200mm</b> 或可复用余料面积相差
        <b>＞0.3㎡</b>取保余料方案；都未越线即等价中立。任一方案有未排入项或搜索超时，则不给伪推荐。
      </div>
      <div class="rec-metrics">
        本次实测：有效利用率差 {{ rec.effectiveGap }} 点 ｜ 整块短边差 {{ rec.remnantSideGapMm }}mm ｜ 可复用余料差 {{ rec.remnantAreaGapSqm }}㎡
      </div>
    </div>

    <!-- 双策略布局 -->
    <div class="grid-2">
      <StrategyPanel
        v-if="result"
        :strategy="result.utilization"
        :result="result"
        kind="util"
        :stale="stale"
        :submitting="submitting === 'UTILIZATION'"
        @cut="submit('UTILIZATION')"
      />
      <StrategyPanel
        v-if="result"
        :strategy="result.remnant"
        :result="result"
        kind="rem"
        :stale="stale"
        :submitting="submitting === 'REMNANT'"
        @cut="submit('REMNANT')"
      />

      <div v-if="!result && !requestError" class="glass glass-pad placeholder">
        <el-skeleton :rows="6" animated />
        <div class="ph-text">选择原片并添加出片后，这里同时出现两份排样</div>
      </div>
    </div>

    <!-- 出片清单（两份共用） -->
    <div class="glass glass-pad" style="margin-top: 14px">
      <div class="section-title">
        出片规格清单（两份策略共用，同刀缝同旋转规则）
        <el-button size="small" style="margin-left: auto" @click="addPiece">+ 添加规格</el-button>
      </div>

      <div class="piece-head">
        <span>长 mm</span><span>宽 mm</span><span>数量</span><span>旋转</span><span>面积㎡</span><span></span>
      </div>
      <div class="piece-list">
        <div v-for="(p, i) in pieces" :key="i" class="piece-row">
          <el-input-number v-model="p.lengthMm" :min="1" :max="6000" :step="10" size="small" controls-position="right" />
          <el-input-number v-model="p.widthMm" :min="1" :max="4000" :step="10" size="small" controls-position="right" />
          <el-input-number v-model="p.quantity" :min="1" :max="200" :step="1" size="small" controls-position="right" />
          <el-tooltip content="关闭后该规格只能按名义方向放入，放不下即记未排入" placement="top">
            <el-switch v-model="p.allowRotation" size="small" />
          </el-tooltip>
          <span class="piece-area">
            {{ ((p.lengthMm * p.widthMm * p.quantity) / 1000000).toFixed(4) }}
          </span>
          <el-button size="small" type="danger" plain @click="removePiece(i)">删</el-button>
        </div>
        <div v-if="!pieces.length" class="piece-empty">暂无出片规格，点右上角添加</div>
      </div>

      <el-divider content-position="left">生成切割单（需明确采用哪份方案）</el-divider>
      <div class="form-grid">
        <el-input v-model="layoutNo" size="small" placeholder="布局图号（可留空）" />
        <el-input v-model="cutter" size="small" placeholder="切割机" />
        <el-input v-model="operator" size="small" placeholder="操作员" />
      </div>
      <div class="hint">
        规则：刀缝真实参与排布（片间留缝）；旋转后仍放不下的出片记未排入，总面积足够不算成功。
        推荐中立或无推荐时，必须由现场显式选择一份方案下刀。
      </div>
    </div>

    <!-- 切割单台账（原有，新增策略/刀缝/有效率列） -->
    <div class="glass glass-pad" style="margin-top: 14px">
      <div class="section-title">切割单台账（原切割单与布局信息继续可用）</div>
      <el-table :data="orders" size="small" stripe max-height="260">
        <el-table-column prop="orderNo" label="切割单号" width="170" />
        <el-table-column prop="batchNo" label="原片批次" width="115" />
        <el-table-column prop="layoutNo" label="布局图号" width="100" />
        <el-table-column label="策略" width="104">
          <template #default="{ row }">
            <el-tag size="small" effect="plain"
                    :type="row.packStrategy === 'REMNANT' ? 'warning' : (row.packStrategy === 'UTILIZATION' ? 'primary' : 'info')">
              {{ row.strategyName || (row.packStrategy === 'REMNANT' ? '整块余料' : row.packStrategy === 'UTILIZATION' ? '利用率' : '旧单') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="刀缝mm" width="80">
          <template #default="{ row }">{{ row.kerfMm ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="outputCount" label="出片数" width="72" />
        <el-table-column label="名义利用率%" width="105">
          <template #default="{ row }">
            <span :style="{ color: Number(row.utilizationRate) < 70 ? '#ffb74d' : (Number(row.utilizationRate) > 95 ? '#4dd6a8' : '#4fc3f7'), fontWeight: 700 }">
              {{ row.utilizationRate }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column label="有效率%" width="88">
          <template #default="{ row }">{{ row.effectiveRate ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="remnantSpec" label="最大整块余料" width="118" />
        <el-table-column label="刀缝损耗㎡" width="95">
          <template #default="{ row }">{{ row.kerfAreaSqm ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="cutter" label="切割机" />
        <el-table-column prop="operator" label="操作员" width="80" />
        <el-table-column prop="status" label="状态" width="90" />
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.rule-item { display: inline-flex; align-items: center; }
.rec-panel { margin: 12px 0; border-width: 1.5px; }
.rec-u { border-color: rgba(79, 155, 247, 0.7); }
.rec-r { border-color: rgba(255, 183, 77, 0.75); }
.rec-n { border-color: rgba(180, 190, 220, 0.5); }
.rec-x { border-color: rgba(228, 87, 61, 0.65); }
.rec-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.rec-badge { font-weight: 800; font-size: 14px; white-space: nowrap; padding: 3px 10px; border-radius: 8px;
  background: rgba(255,255,255,0.12); }
.rec-u .rec-badge { color: #9cc8ff; }
.rec-r .rec-badge { color: #ffd28c; }
.rec-n .rec-badge { color: #cfd8f8; }
.rec-x .rec-badge { color: #ffb3a6; }
.rec-text { font-size: 12.5px; color: var(--ink-soft); }
.rec-bound { margin-top: 8px; font-size: 11.5px; color: var(--ink-dim); line-height: 1.7; }
.rec-bound b { color: var(--ink); }
.rec-metrics { margin-top: 4px; font-size: 11.5px; color: var(--ink-dim); }

.placeholder { min-height: 320px; }
.ph-text { text-align: center; color: var(--ink-dim); font-size: 12px; margin-top: 10px; }

.piece-head, .piece-row {
  display: grid; grid-template-columns: 1fr 1fr 0.8fr 64px 86px 44px; gap: 7px; align-items: center;
}
.piece-head { font-size: 11px; color: var(--ink-dim); padding: 0 2px 6px; }
.piece-list { max-height: 260px; overflow: auto; }
.piece-row { margin-bottom: 7px; }
.piece-area { font-size: 12px; color: #4fc3f7; font-weight: 700; text-align: center; }
.piece-empty { font-size: 12px; color: var(--ink-dim); padding: 12px 2px; }

.form-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
.hint { font-size: 11px; color: var(--ink-dim); margin-top: 8px; line-height: 1.6; }
.rank-bar { height: 7px; border-radius: 5px; background: rgba(255,255,255,0.12); overflow: hidden; }
.rank-bar i { display: block; height: 100%; background: linear-gradient(90deg, #ffb74d, #e4573d); }
.util-error {
  padding: 7px 10px; border-radius: 9px; font-size: 12px; color: #ffd7cf;
  background: rgba(228, 87, 61, 0.22); border: 1px solid rgba(228, 87, 61, 0.5);
}
</style>
