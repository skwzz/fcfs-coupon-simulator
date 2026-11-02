import { useEffect, useRef, useState, type MouseEvent as ReactMouseEvent } from 'react'
import { STATUSES, STATUS_META, S } from '../../mock/types'

// 사용자 1명 = 셀 1개. DOM 수천 개 대신 canvas로 그린다.
export function UserGrid({
  status,
  rank,
  version,
  selected,
  pinned,
  onSelect,
}: {
  status: Uint8Array
  rank: Int32Array
  version: number
  selected: number | null
  pinned: number[]
  onSelect: (id: number) => void
}) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [width, setWidth] = useState(800)
  const [hover, setHover] = useState<{ id: number; x: number; y: number } | null>(null)

  const n = status.length
  const cell = n <= 1000 ? 14 : n <= 3000 ? 10 : n <= 6000 ? 8 : 6
  const gap = cell >= 10 ? 2 : 1
  const pitch = cell + gap
  const cols = Math.max(1, Math.floor(width / pitch))
  const rows = Math.ceil(n / cols)
  const height = rows * pitch

  useEffect(() => {
    const ro = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    if (wrapRef.current) ro.observe(wrapRef.current)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const dpr = window.devicePixelRatio || 1
    canvas.width = cols * pitch * dpr
    canvas.height = height * dpr
    const ctx = canvas.getContext('2d')!
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, cols * pitch, height)

    let maxRank = 1
    for (let i = 0; i < n; i++) if (status[i] === S.WAITING && rank[i] > maxRank) maxRank = rank[i]

    for (let i = 0; i < n; i++) {
      const s = status[i]
      const x = (i % cols) * pitch
      const y = Math.floor(i / cols) * pitch
      ctx.fillStyle = STATUS_META[STATUSES[s]].color
      // 대기 중인 셀은 순번이 앞일수록 진하게
      ctx.globalAlpha = s === S.WAITING ? 0.25 + 0.75 * (1 - rank[i] / maxRank) : 1
      ctx.fillRect(x, y, cell, cell)
    }
    ctx.globalAlpha = 1

    const outline = (id: number, color: string) => {
      const x = (id % cols) * pitch
      const y = Math.floor(id / cols) * pitch
      ctx.strokeStyle = color
      ctx.lineWidth = 2
      ctx.strokeRect(x - 1, y - 1, cell + 2, cell + 2)
    }
    pinned.forEach((id) => outline(id, '#e2e8f0'))
    if (selected != null) outline(selected, '#38bdf8')
  }, [version, status, rank, n, cols, pitch, cell, height, selected, pinned])

  const idAt = (e: ReactMouseEvent) => {
    const r = canvasRef.current!.getBoundingClientRect()
    const col = Math.floor((e.clientX - r.left) / pitch)
    const row = Math.floor((e.clientY - r.top) / pitch)
    const id = row * cols + col
    return col < cols && id >= 0 && id < n ? id : null
  }

  return (
    <div ref={wrapRef} className="relative">
      <canvas
        ref={canvasRef}
        style={{ width: cols * pitch, height }}
        className="cursor-pointer"
        onMouseMove={(e) => {
          const id = idAt(e)
          setHover(id == null ? null : { id, x: e.nativeEvent.offsetX, y: e.nativeEvent.offsetY })
        }}
        onMouseLeave={() => setHover(null)}
        onClick={(e) => {
          const id = idAt(e)
          if (id != null) onSelect(id)
        }}
      />
      {hover && (
        <div
          className="pointer-events-none absolute z-10 whitespace-nowrap rounded-md border border-slate-700 bg-slate-900 px-2 py-1 text-xs shadow-lg"
          style={{ left: hover.x + 12, top: hover.y + 12 }}
        >
          <span className="text-slate-400">user #{hover.id + 1}</span>
          <span className="mx-1.5 text-slate-600">·</span>
          <span style={{ color: STATUS_META[STATUSES[status[hover.id]]].color }}>{STATUS_META[STATUSES[status[hover.id]]].label}</span>
          {status[hover.id] === S.WAITING && <span className="ml-1.5 text-slate-300">순번 {rank[hover.id].toLocaleString()}</span>}
        </div>
      )}
    </div>
  )
}

export function GridLegend() {
  return (
    <div className="flex flex-wrap gap-x-3 gap-y-1">
      {STATUSES.map((s) => (
        <span key={s} className="flex items-center gap-1.5 text-xs text-slate-400">
          <span className="size-2.5 rounded-sm" style={{ backgroundColor: STATUS_META[s].color, outline: s === 'IDLE' ? '1px solid #334155' : undefined }} />
          {STATUS_META[s].label}
        </span>
      ))}
    </div>
  )
}
