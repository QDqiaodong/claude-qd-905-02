// 算法原型（与 Java 版 PackingEngine 逐行对应），仅用于在没有 JDK 的环境里验证几何与口径。
'use strict'

const EPS = 1e-6

function round(v, s) { const f = 10 ** s; return Math.round(v * f) / f }
function fmt(v) { v = round(v, 1); return Number.isInteger(v) ? String(v) : String(v) }

// ---------- 基础结构 ----------
function makeUnit(id, L, W, specL, specW, remark, quantity, orderedIndex) {
  return { id, L, W, specL, specW, remark, quantity, orderedIndex }
}

function makeFree(x, y, w, h) { return { x, y, w, h } }

// 占用 (px,py,pw,ph)（含刀缝扩张）后，把自由矩形 f 切成至多四个
function splitFree(f, px, py, pw, ph, kerf) {
  if (px >= f.x + f.w - EPS || px + pw <= f.x + EPS ||
      py >= f.y + f.h - EPS || py + ph <= f.y + EPS) return [f]
  const out = []
  const leftW = px - kerf - f.x
  if (leftW > EPS) out.push(makeFree(f.x, f.y, leftW, f.h))
  const rightX = px + pw + kerf
  if (rightX < f.x + f.w - EPS) out.push(makeFree(rightX, f.y, f.x + f.w - rightX, f.h))
  const topH = py - kerf - f.y
  if (topH > EPS) out.push(makeFree(f.x, f.y, f.w, topH))
  const bottomY = py + ph + kerf
  if (bottomY < f.y + f.h - EPS) out.push(makeFree(f.x, bottomY, f.w, f.y + f.h - bottomY))
  return out
}

function containedIn(a, b) {
  return a.x >= b.x - EPS && a.y >= b.y - EPS &&
         a.x + a.w <= b.x + b.w + EPS && a.y + a.h <= b.y + b.h + EPS
}

function pruneContained(list) {
  const keep = []
  for (let i = 0; i < list.length; i++) {
    let covered = false
    for (let j = 0; j < list.length; j++) {
      if (i !== j && containedIn(list[i], list[j]) &&
          (list[j].w * list[j].h > list[i].w * list[i].h + EPS || j < i)) {
        covered = true; break
      }
    }
    if (!covered) keep.push(list[i])
  }
  return keep
}

// 占用后重建自由矩形表
function occupy(frees, px, py, pw, ph, kerf) {
  const next = []
  for (const f of frees) {
    for (const s of splitFree(f, px, py, pw, ph, kerf)) next.push(s)
  }
  return pruneContained(next)
}

function orientationsFor(u, allowRotation) {
  const fits0 = (w, h) => ({ ok: w <= L0 + EPS && h <= W0 + EPS })
  const opts = []
  if (u.L <= L0 + EPS && u.W <= W0 + EPS) opts.push({ w: u.L, h: u.W, rotated: false })
  if (allowRotation && !(u.L === u.W) && u.W <= L0 + EPS && u.L <= W0 + EPS) {
    opts.push({ w: u.W, h: u.L, rotated: true })
  }
  return opts
}
let L0 = 0, W0 = 0

// 单个候选位置评分（BSSF：短边差最小，再比长边差）
function placementScore(f, w, h) {
  return { short: Math.min(f.w - w, f.h - h), long: Math.max(f.w - w, f.h - h) }
}

// ---------- 策略A：MaxRects 自由矩形 ----------
function runMaxRects(units, L, W, kerf, allowRotation, order, deadline, ops) {
  L0 = L; W0 = W
  const arr = units.slice().sort(order)
  let frees = [makeFree(0, 0, L, W)]
  const placed = []
  const unplaced = []
  let timedOut = false
  let idx = 0
  outer:
  for (; idx < arr.length; idx++) {
    const u = arr[idx]
    ops.n += arr.length + frees.length * 2
    if (ops.n > ops.max || (deadline && Date.now() > deadline)) { timedOut = true; break outer }
    let best = null
    const opts = orientationsFor(u, allowRotation)
    for (const o of opts) {
      for (const f of frees) {
        if (o.w <= f.w + EPS && o.h <= f.h + EPS) {
          const s = placementScore(f, o.w, o.h)
          if (!best || s.short < best.score.short - EPS ||
              (Math.abs(s.short - best.score.short) <= EPS && s.long < best.score.long - EPS)) {
            best = { f, o, score: s }
          }
        }
      }
    }
    if (!best) {
      unplaced.push({ unit: u, reason: 'NO_FIT' })
      continue
    }
    const { f, o } = best
    placed.push({ x: f.x, y: f.y, w: o.w, h: o.h, unit: u, rotated: o.rotated })
    frees = occupy(frees, f.x, f.y, o.w + kerf, o.h + kerf, kerf)
  }
  if (timedOut) {
    // 超时：当前项及其后所有项都记为「未能确认放入」，不得伪装成功
    for (; idx < arr.length; idx++) unplaced.push({ unit: arr[idx], reason: 'LIMITED' })
  }
  return { placed, unplaced, frees, timedOut }
}

// ---------- 策略B：条带（断头台）排样 ----------
function runShelf(units, L, W, kerf, allowRotation, order, anchor) {
  L0 = L; W0 = W
  const arr = units.slice().sort(order)
  const isRow = anchor === 'TOP' || anchor === 'BOTTOM'
  const rows = [] // {cross, used, items:[{along,w,h}]}
  const placed = []
  const unplaced = []
  let usedBand = 0

  for (const u of arr) {
    const opts = orientationsFor(u, allowRotation)
    // 条带策略下优先选「沿条带延伸方向更长」的朝向，其次更少旋转
    opts.sort((a, b) => {
      const da = isRow ? a.h : a.w
      const db = isRow ? b.h : b.w
      if (Math.abs(da - db) > EPS) return da - db
      return (a.rotated === b.rotated) ? 0 : (a.rotated ? 1 : -1)
    })
    let done = false
    for (const o of opts) {
      // 先尝试放进已有条带
      for (const r of rows) {
        const needAlong = (r.items.length ? kerf : 0)
        const along = isRow ? o.w : o.h
        const cross = isRow ? o.h : o.w
        if (cross <= r.cross + EPS && r.used + needAlong + along <= (isRow ? L : W) + EPS) {
          r.items.push({ along, cross, o })
          r.used += needAlong + along
          done = true
          break
        }
      }
      if (done) break
      // 新开条带
      const cross = isRow ? o.h : o.w
      const along = isRow ? o.w : o.h
      if (usedBand + (rows.length ? kerf : 0) + cross <= (isRow ? W : L) + EPS) {
        usedBand += (rows.length ? kerf : 0) + cross
        rows.push({ cross, used: along, items: [{ along, cross, o }] })
        done = true
        break
      }
    }
    if (!done) unplaced.push({ unit: u, reason: 'NO_FIT' })
  }

  // 落坐标
  const frees = []
  if (isRow) {
    let y = anchor === 'TOP' ? 0 : W - usedBand
    for (const r of rows) {
      let x = 0
      for (const it of r.items) {
        placed.push({ x, y, w: it.o.w, h: it.o.h, unit: it.o.unit, rotated: it.o.rotated })
        x += it.along + kerf
      }
      // 行尾
      if (L - r.used > EPS) frees.push(makeFree(r.used, y, L - r.used, r.cross))
      y += r.cross + kerf
    }
    // 大块余料
    if (anchor === 'TOP' && usedBand < W - EPS) frees.push(makeFree(0, usedBand, L, W - usedBand))
    if (anchor === 'BOTTOM' && usedBand < W - EPS) frees.push(makeFree(0, 0, L, W - usedBand))
  } else {
    let x = anchor === 'LEFT' ? 0 : L - usedBand
    for (const r of rows) {
      let y = 0
      for (const it of r.items) {
        placed.push({ x, y, w: it.o.w, h: it.o.h, unit: it.o.unit, rotated: it.o.rotated })
        y += it.along + kerf
      }
      if (W - r.used > EPS) frees.push(makeFree(x, r.used, r.cross, W - r.used))
      x += r.cross + kerf
    }
    if (anchor === 'LEFT' && usedBand < L - EPS) frees.push(makeFree(usedBand, 0, L - usedBand, W))
    if (anchor === 'RIGHT' && usedBand < L - EPS) frees.push(makeFree(0, 0, L - usedBand, W))
  }
  return { placed, unplaced, frees, timedOut: false }
}

// ---------- 网格并集（刀缝、余料精确面积 + 不重叠矩形分解） ----------
function gridBuild(L, W, placed, frees) {
  const xs = [0, L], ys = [0, W]
  for (const p of placed) { xs.push(p.x, p.x + p.w); ys.push(p.y, p.y + p.h) }
  for (const f of frees) { xs.push(f.x, f.x + f.w); ys.push(f.y, f.y + f.h) }
  xs.sort((a, b) => a - b); ys.sort((a, b) => a - b)
  const ux = [...new Set(xs.map(v => round(v, 4)))], uy = [...new Set(ys.map(v => round(v, 4)))]
  const nx = ux.length - 1, ny = uy.length - 1
  const pieceG = new Uint8Array(nx * ny)
  const freeG = new Uint8Array(nx * ny)
  const mark = (grid, r) => {
    const x0 = ux.indexOf(round(r.x, 4)), x1 = ux.indexOf(round(r.x + r.w, 4))
    const y0 = uy.indexOf(round(r.y, 4)), y1 = uy.indexOf(round(r.y + r.h, 4))
    for (let j = y0; j < y1; j++) for (let i = x0; i < x1; i++) grid[j * nx + i] = 1
  }
  for (const p of placed) mark(pieceG, p)
  for (const f of frees) mark(freeG, f)

  function rectsOf(grid, otherGrid) {
    let area = 0
    // 每行连续段
    const rowRuns = []
    for (let j = 0; j < ny; j++) {
      let i = 0
      while (i < nx) {
        if (grid[j * nx + i]) {
          let k = i
          while (k < nx && grid[j * nx + k]) k++
          rowRuns.push({ j, i0: i, i1: k })
          for (let q = i; q < k; q++) area += (ux[q + 1] - ux[q]) * (uy[j + 1] - uy[j])
          i = k
        } else i++
      }
    }
    // 同行起止一致的连续行合并成矩形（不重叠铺盖）
    const rects = []
    for (let a = 0; a < rowRuns.length; a++) {
      const r = rowRuns[a]
      if (r.used) continue
      let j1 = r.j + 1
      outer: while (j1 < ny) {
        for (const q of rowRuns) {
          if (!q.used && q.j === j1 && q.i0 === r.i0 && q.i1 === r.i1) { j1++; continue outer }
        }
        break
      }
      for (const q of rowRuns) if (q.j >= r.j && q.j < j1 && q.i0 === r.i0 && q.i1 === r.i1) q.used = true
      rects.push({ x: ux[r.i0], y: uy[r.j], w: ux[r.i1] - ux[r.i0], h: uy[j1] - uy[r.j] })
    }
    return { area, rects }
  }
  const pieceArea = rectsOf(pieceG, null)
  const free = rectsOf(freeG, pieceG)
  // 刀缝：既非出片也非自由的格子
  const kerfG = new Uint8Array(nx * ny)
  for (let i = 0; i < kerfG.length; i++) if (!pieceG[i] && !freeG[i]) kerfG[i] = 1
  const kerf = rectsOf(kerfG, null)
  return {
    pieceArea: pieceArea.area, freeArea: free.area, kerfArea: kerf.area,
    freeRects: free.rects, kerfRects: kerf.rects
  }
}

module.exports = {
  EPS, round, fmt, makeUnit, makeFree,
  runMaxRects, runShelf, gridBuild, pruneContained, splitFree, occupy
}
