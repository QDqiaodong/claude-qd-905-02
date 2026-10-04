// 双策略排样算法验证脚本（Node 原型）
'use strict'
const P = require('./packing.cjs')
const { EPS, makeUnit, runMaxRects, runShelf, gridBuild } = P

const KERF_DEFAULT = 3
const REUSE_MIN_SIDE = 300
const REUSE_MIN_AREA = 0.25 // ㎡

function area(rects) { return rects.reduce((s, r) => s + r.w * r.h, 0) }

// 几何不重叠校验（含刀缝间隔）
function assertNoOverlap(placed, kerf) {
  for (let i = 0; i < placed.length; i++) {
    for (let j = i + 1; j < placed.length; j++) {
      const a = placed[i], b = placed[j]
      const ox = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)
      const oy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y)
      // 有效排布：至少一个方向上留出 >= 刀缝的间隔；否则几何重叠或刀缝被吃
      if (!(ox <= -kerf + EPS || oy <= -kerf + EPS)) {
        throw new Error(`出片重叠/刀缝不足: (${a.x},${a.y} ${a.w}x${a.h}) vs (${b.x},${b.y} ${b.w}x${b.h}) gap=(${(-ox).toFixed(1)},${(-oy).toFixed(1)}) kerf=${kerf}`)
      }
    }
  }
}

function assertInSheet(placed, L, W) {
  for (const p of placed) {
    if (p.x < -EPS || p.y < -EPS || p.x + p.w > L + EPS || p.y + p.h > W + EPS) {
      throw new Error(`出片越界: ${p.x},${p.y} ${p.w}x${p.h} in ${L}x${W}`)
    }
  }
}

// 构造单元
function expand(specs, allowRotation) {
  const units = []
  let id = 0
  specs.forEach((s, oi) => {
    for (let q = 0; q < s.qty; q++) {
      units.push(makeUnit(id++, s.l, s.w, s.l, s.w, s.remark || '', s.qty, oi))
    }
  })
  return units
}

const orders = [
  (a, b) => b.h * b.w - a.h * a.w,                                   // 面积降序
  (a, b) => b.h - a.h || b.w - a.w,                                  // 高降序
  (a, b) => Math.min(b.L, b.W) - Math.min(a.L, a.W),                 // 短边降序
]
const shelfOrders = [
  (a, b) => (isRow ? -1 : 0) || 0 // placeholder, replaced below
]

function strategyA(units, L, W, kerf, allowRotation, timeMs, maxOps) {
  const deadline = timeMs ? Date.now() + timeMs : 0
  const ops = { n: 0, max: maxOps }
  const ords = [
    (a, b) => b.W * b.L - a.W * a.L || a.id - b.id,
    (a, b) => b.W - a.W || b.L - a.L || a.id - b.id,
    (a, b) => Math.min(b.L, b.W) - Math.min(a.L, a.W) || a.id - b.id
  ]
  let best = null
  for (const order of ords) {
    const r = runMaxRects(units, L, W, kerf, allowRotation, order, deadline, ops)
    const cand = evaluate(r, L, W, kerf)
    cand.timedOut = r.timedOut
    if (!best || better(cand, best, false)) best = cand
    if (best.placed.length === units.length && !r.timedOut) break
  }
  return best
}

function strategyB(units, L, W, kerf, allowRotation) {
  const ords = [
    (a, b) => b.W - a.W || b.L - a.L || a.id - b.id,
    (a, b) => b.L - a.L || b.W - a.W || a.id - b.id,
    (a, b) => b.W * b.L - a.W * a.L || a.id - b.id
  ]
  const anchors = ['TOP', 'BOTTOM', 'LEFT', 'RIGHT']
  let best = null
  for (const anchor of anchors) {
    for (const order of ords) {
      const r = runShelf(units, L, W, kerf, allowRotation, order, anchor)
      const cand = evaluate(r, L, W, kerf)
      cand.anchor = anchor
      if (!best || better(cand, best, true)) best = cand
    }
  }
  return best
}

function evaluate(r, L, W, kerf) {
  const g = gridBuild(L, W, r.placed, r.frees)
  const total = L * W
  const placedArea = r.placed.reduce((s, p) => s + p.w * p.h, 0)
  if (Math.abs(placedArea - g.pieceArea) > 0.01) {
    throw new Error(`网格出片面积不一致 ${placedArea} vs ${g.pieceArea}`)
  }
  if (Math.abs(g.pieceArea + g.freeArea + g.kerfArea - total) > 1) {
    throw new Error(`面积不闭合: piece=${g.pieceArea} free=${g.freeArea} kerf=${g.kerfArea} total=${total}`)
  }
  assertNoOverlap(r.placed, kerf)
  assertInSheet(r.placed, L, W)
  // 可复用余料：最短边 >= 300mm 且面积 >= 0.25㎡（整条断头台余料天然是整块）
  const reuse = g.freeRects
    .filter(rc => Math.min(rc.w, rc.h) >= REUSE_MIN_SIDE - EPS && rc.w * rc.h >= REUSE_MIN_AREA * 1e6 - EPS)
    .sort((a, b) => b.w * b.h - a.w * a.h)
  const biggest = reuse[0] || null
  const reuseArea = reuse.reduce((s, rc) => s + rc.w * rc.h, 0)
  return {
    placed: r.placed, unplaced: r.unplaced, frees: r.frees,
    grid: g, placedArea, freeArea: g.freeArea, kerfArea: g.kerfArea,
    util: placedArea / total * 100,
    // 有效利用率：出片 + 可复用整块余料（碎料和刀缝不计为可复用价值）
    effectiveUtil: (placedArea + reuseArea) / total * 100,
    reuseArea,
    timedOut: r.timedOut,
    reuseRects: reuse,
    biggest,
    biggestMinSide: biggest ? Math.min(biggest.w, biggest.h) : 0
  }
}

// 策略内选优：放全优先；A 再比出片面积/有效利用率；B 再比最大整块短边与可复用余料合计
function better(c, b, remnantFirst) {
  if (c.placed.length !== b.placed.length) return c.placed.length > b.placed.length
  if (remnantFirst) {
    if (Math.abs(c.biggestMinSide - b.biggestMinSide) > EPS) return c.biggestMinSide > b.biggestMinSide
    if (Math.abs(c.reuseArea - b.reuseArea) > EPS) return c.reuseArea > b.reuseArea
  } else {
    if (Math.abs(c.placedArea - b.placedArea) > EPS) return c.placedArea > b.placedArea
    if (Math.abs(c.reuseArea - b.reuseArea) > EPS) return c.reuseArea > b.reuseArea
  }
  return false
}

// ---------- 推荐口径（固定阈值 + 现场可理解） ----------
const REC_EFFECTIVE_GAP = 2.0      // 有效利用率相差 2 个点以上才算明显
const REC_REMNANT_SIDE_GAP = 200   // 最大整块余料短边相差 200mm 以上才算明显
const REC_REMNANT_AREA_GAP = 0.30  // 可复用余料面积相差 0.3㎡ 以上才算明显

function recommend(a, b, totalUnits) {
  // 搜索未完成：放入性未被确认，不给伪推荐
  if (a.timedOut || b.timedOut) {
    return { code: 'NONE', reason: '搜索超时，存在未能确认放入性的出片；请放宽数量/刀缝后重算，本次不给推荐' }
  }
  const aFull = a.placed.length === totalUnits
  const bFull = b.placed.length === totalUnits
  // 只放全 vs 未放全：放全那份唯一可用，但不叫「更优」，需说明另一份缺什么
  if (aFull && !bFull) {
    return { code: 'UTILIZATION_ONLY_FEASIBLE',
      reason: `仅利用率策略放完全部 ${totalUnits} 片；余料策略有 ${b.unplaced.length} 片未排入，保料方案本次不可直接下刀` }
  }
  if (bFull && !aFull) {
    return { code: 'REMNANT_ONLY_FEASIBLE',
      reason: `仅保余料策略放完全部 ${totalUnits} 片；利用率策略有 ${a.unplaced.length} 片未排入` }
  }
  if (!aFull && !bFull) {
    return { code: 'NONE', reason: '两份方案均有出片未排入，请先减少数量、调整规格或放宽刀缝，本次不给推荐' }
  }
  // 两份都放全：比有效利用率（出片+可复用整块余料）与整块形状
  const dEff = a.effectiveUtil - b.effectiveUtil
  const dSide = b.biggestMinSide - a.biggestMinSide
  const dArea = (b.reuseArea - a.reuseArea) / 1e6
  const aBig = a.biggest ? `${P.fmt(a.biggest.w)}×${P.fmt(a.biggest.h)}` : '无整块'
  const bBig = b.biggest ? `${P.fmt(b.biggest.w)}×${P.fmt(b.biggest.h)}` : '无整块'
  if (Math.abs(dEff) <= REC_EFFECTIVE_GAP + EPS &&
      Math.abs(dSide) <= REC_REMNANT_SIDE_GAP + EPS &&
      Math.abs(dArea) <= REC_REMNANT_AREA_GAP + EPS) {
    return { code: 'NEUTRAL',
      reason: `两策略等价（均放全 ${totalUnits} 片）：有效利用率差 ${Math.abs(dEff).toFixed(2)} 点、最大整块余料 ${aBig} vs ${bBig}，短边/面积差均未越过 200mm、0.3㎡ 阈值，现场任选` }
  }
  if (dEff > REC_EFFECTIVE_GAP + EPS) {
    return { code: 'UTILIZATION',
      reason: `选利用率策略：放全片数相同，有效利用率（出片＋可复用整块余料）高出 ${dEff.toFixed(2)} 个点（阈值 2 点）` }
  }
  if (dSide > REC_REMNANT_SIDE_GAP + EPS || dArea > REC_REMNANT_AREA_GAP + EPS) {
    return { code: 'REMNANT',
      reason: `选保余料策略：放全片数相同，最大整块余料短边多 ${P.fmt(dSide)}mm、可复用余料合计多 ${dArea.toFixed(2)}㎡（阈值 200mm / 0.3㎡），整块 ${bBig}` }
  }
  return { code: 'NEUTRAL',
    reason: `两策略等价：有效利用率差 ${Math.abs(dEff).toFixed(2)} 点、整块短边差 ${P.fmt(Math.abs(dSide))}mm、余料面积差 ${Math.abs(dArea).toFixed(2)}㎡，互有取舍但均未越固定阈值` }
}

// ---------- 场景 ----------
function run(name, specs, L, W, opts = {}) {
  const kerf = opts.kerf ?? KERF_DEFAULT
  const allowRotation = opts.allowRotation ?? true
  const units = expand(specs, allowRotation)
  const sourceArea = L * W / 1e6
  const outputArea = units.reduce((s, u) => s + u.L * u.W, 0) / 1e6
  const oversize = units.filter(u =>
    !(u.L <= L + EPS && u.W <= W + EPS) &&
    !(allowRotation && u.W <= L + EPS && u.L <= W + EPS))

  console.log(`\n===== ${name} =====`)
  console.log(`原片 ${L}x${W} (${sourceArea.toFixed(3)}㎡), 出片 ${units.length} 片 ${outputArea.toFixed(3)}㎡, 刀缝 ${kerf}mm, 旋转 ${allowRotation}`)
  if (oversize.length) {
    console.log(`  超限出片: ${[...new Set(oversize.map(o => o.specL + 'x' + o.specW))].join(', ')}`)
  }
  const a = strategyA(units, L, W, kerf, allowRotation, opts.timeMs ?? 300, opts.maxOps ?? 20_000_000)
  const b = strategyB(units, L, W, kerf, allowRotation)
  // 未排入项归因：超限 / 总面积超 / 几何塞不下 / 搜索超时
  const attachReasons = (st) => {
    for (const u of st.unplaced) {
      const isOversize = oversize.some(o => o.id === u.unit.id)
      if (u.reason === 'LIMITED') u.reasonText = '搜索超时，未能确认是否可放入'
      else if (isOversize) u.reasonText = '旋转后仍超出原片幅面'
      else if (outputArea > sourceArea + EPS) u.reasonText = '出片总面积超过原片，几何排样必然有余片'
      else u.reasonText = '其余出片占位后剩余空间几何上放不下（总面积够≠能放入）'
    }
  }
  attachReasons(a); attachReasons(b)
  const rec = recommend(a, b, units.length)
  const dump = (tag, st) => {
    console.log(`  [${tag}] 放入 ${st.placed.length}/${units.length} 利用率 ${st.util.toFixed(2)}% 有效 ${st.effectiveUtil.toFixed(2)}% 刀缝损耗 ${(st.kerfArea / 1e6).toFixed(3)}㎡ 可复用余料 ${(st.reuseArea / 1e6).toFixed(3)}㎡/共 ${(st.freeArea / 1e6).toFixed(3)}㎡${st.timedOut ? ' [搜索超时-结果不完整]' : ''}`)
    if (st.biggest) console.log(`        最大整块余料 ${P.fmt(st.biggest.w)}x${P.fmt(st.biggest.h)} (${(st.biggest.w * st.biggest.h / 1e6).toFixed(3)}㎡, 可复用块 ${st.reuseRects.length})`)
    else console.log('        无整块可复用余料')
    if (st.unplaced.length) {
      const by = {}
      for (const u of st.unplaced) by[u.reasonText] = (by[u.reasonText] || 0) + 1
      for (const [t, n] of Object.entries(by)) console.log(`        未排入 x${n}: ${t}`)
    }
  }
  dump('A 利用率', a)
  dump('B 保余料', b)
  console.log(`  推荐: ${rec.code} — ${rec.reason}`)
  return { a, b, rec }
}

// 场景1：默认种子数据（1200x900x2 + 800x600x3 on 2440x1830）
run('场景1 种子清单', [
  { l: 1200, w: 900, qty: 2 }, { l: 800, w: 600, qty: 3 }
], 2440, 1830)

// 场景2：A 与 B 应有显著差异 —— 少数大板 + 若干小板，小板可塞进角落
run('场景2 大板+小板', [
  { l: 2000, w: 1000, qty: 1 },
  { l: 600, w: 400, qty: 8 }
], 2440, 1830)

// 场景3：总面积够但几何放不下（经典反例）：两块 1300x1300 进 2440x1830 不可能（旋转也不行），面积占比仅 75%
run('场景3 面积够几何不够', [
  { l: 1300, w: 1300, qty: 2 }
], 2440, 1830)

// 场景4：旋转才放得下：1900x1000 必须旋转（1000 沿 x、1900 沿 y 才进 2440x1830？ 1900>1830；换 2000x1200：不旋转 2000<=2440,1200<=1830 可。要必须旋转: 2400x1000 → 不旋转2400<=2440且1000<=1830可...）
// 选 2600x1000: 原片 2440x1830，旋转后 1000x2600 也不行。换：L=2400 W=1000? 不旋转 2400<=2440,1000<=1830 OK，不需旋转。
// 真正必须旋转：出片 2000x2400? 不旋转 w=2000 h=2400 >1830 不行；旋转 2400x2000: 2400<=2440,2000>1830 也不行。
// 用 1300x2400: 不旋转 h=2400 超宽1830；旋转后 2400x1300: 2400<=2440,1300<=1830 可
run('场景4 必须旋转', [
  { l: 1300, w: 2400, qty: 1 }, { l: 800, w: 600, qty: 2 }
], 2440, 1830)

// 场景5：旋转关闭时放不下、开启时放得下
run('场景5 禁止旋转', [
  { l: 1300, w: 2400, qty: 1 }
], 2440, 1830, { allowRotation: false })

// 场景6：总面积超原片
run('场景6 总面积超', [
  { l: 1200, w: 1000, qty: 4 }
], 2440, 1830)

// 场景7：等价情形（小料很多、两种打法接近）
run('场景7 小料接近铺满', [
  { l: 600, w: 400, qty: 16 }
], 2440, 1830)

// 场景8：刀缝为 0
run('场景8 无刀缝', [
  { l: 1200, w: 900, qty: 2 }, { l: 800, w: 600, qty: 3 }
], 2440, 1830, { kerf: 0 })

// 场景9：单块超限
run('场景9 单片超限', [
  { l: 3000, w: 2000, qty: 1 }, { l: 800, w: 600, qty: 2 }
], 2440, 1830)

// 场景10：强制搜索预算耗尽（真实可放入，验证超时不误报成功）
run('场景10 搜索超时', [
  { l: 600, w: 400, qty: 8 }
], 2440, 1830, { maxOps: 50 })

// 场景11：总面积够、放得全但 B 保料与 A 省料差异明显（长窄条 + 余块）
run('场景11 长料靠边', [
  { l: 2300, w: 300, qty: 3 }, { l: 700, w: 700, qty: 2 }
], 2440, 1830)

// 场景12：空清单
run('场景12 空清单', [], 2440, 1830)

// 汇总校验
let failures = 0
function expect(name, cond, detail) {
  if (!cond) { failures++; console.log(`  !! 断言失败: ${name} ${detail || ''}`) }
}
{
  const r1 = run('场景1 种子清单', [
    { l: 1200, w: 900, qty: 2 }, { l: 800, w: 600, qty: 3 }
  ], 2440, 1830)
  expect('S1 两策略放全', r1.a.placed.length === 5 && r1.b.placed.length === 5)
  expect('S1 B整块短边>=A', r1.b.biggestMinSide >= r1.a.biggestMinSide - EPS)
  expect('S1 刀缝>0时损耗非负', r1.a.kerfArea >= -EPS && r1.b.kerfArea >= -EPS)

  const r3 = run('场景3 面积够几何不够', [{ l: 1300, w: 1300, qty: 2 }], 2440, 1830)
  expect('S3 不允许因总面积给成功', r3.a.placed.length < 2 && r3.b.placed.length < 2)
  expect('S3 无伪推荐', r3.rec.code === 'NONE')

  const r5 = run('场景5 禁止旋转', [{ l: 1300, w: 2400, qty: 1 }], 2440, 1830, { allowRotation: false })
  expect('S5 禁止旋转必失败', r5.a.placed.length === 0 && r5.b.placed.length === 0)

  const r10 = run('场景10 搜索超时', [{ l: 600, w: 400, qty: 8 }], 2440, 1830, { maxOps: 50 })
  expect('S10 超时有标记且未排入覆盖全部',
    r10.a.timedOut === true && r10.a.placed.length + r10.a.unplaced.length === 8)
  expect('S10 超时无伪推荐', r10.rec.code === 'NONE')
}
console.log(`\n断言结果: ${failures === 0 ? '全部通过' : failures + ' 项失败'}`)
process.exit(failures === 0 ? 0 : 1)

