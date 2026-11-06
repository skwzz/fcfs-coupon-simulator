import { useCallback, useEffect, useMemo, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Badge, Button, Card, fmt } from '../../components/ui'
import type { Comparison, RunSummary } from '../../sim/types'

// 비교 차트에서 실행마다 다른 색
const PALETTE = ['#38bdf8', '#f59e0b', '#22c55e', '#a78bfa', '#f472b6', '#ef4444', '#14b8a6', '#eab308']

const label = (r: RunSummary) => `#${r.runId} ${r.strategy}`

export function HistoryPage() {
  const [runs, setRuns] = useState<RunSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [checked, setChecked] = useState<number[] | null>(null)
  const [comparisons, setComparisons] = useState<Comparison[]>([])

  const load = useCallback(async () => {
    try {
      const res = await fetch('/api/simulations?limit=50')
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const list: RunSummary[] = await res.json()
      setRuns(list)
      setError(null)
      // 처음 열 때는 끝난 실행 중 최근 5개를 골라 둔다
      setChecked((c) => c ?? list.filter((r) => r.state === 'DONE').slice(0, 5).map((r) => r.runId))
    } catch (e) {
      setError(`실행 기록을 불러오지 못했습니다 (${e instanceof Error ? e.message : e}). simulator가 떠 있는지 확인하세요.`)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    if (!checked || checked.length === 0) {
      setComparisons([])
      return
    }
    let cancelled = false
    fetch(`/api/simulations/compare?ids=${checked.join(',')}`)
      .then((res) => (res.ok ? res.json() : []))
      .then((c: Comparison[]) => !cancelled && setComparisons(c))
    return () => {
      cancelled = true
    }
  }, [checked])

  const toggle = (id: number) => setChecked((c) => ((c ?? []).includes(id) ? (c ?? []).filter((x) => x !== id) : [...(c ?? []), id]))

  const remove = async (id: number) => {
    if (!confirm(`실행 #${id} 기록을 삭제할까요?`)) return
    const res = await fetch(`/api/simulations/${id}`, { method: 'DELETE' })
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      alert(body?.detail ?? `삭제 실패 (HTTP ${res.status})`)
      return
    }
    setChecked((c) => (c ?? []).filter((x) => x !== id))
    load()
  }

  // 막대 비교: 오래된 실행이 위로
  const bars = useMemo(
    () =>
      [...comparisons]
        .sort((a, b) => a.summary.runId - b.summary.runId)
        .map(({ summary: r }) => ({
          name: label(r),
          TPS: r.avgTps ?? 0,
          p99: Math.round(r.latencyMs.p99 ?? 0),
          초과발급: r.verification?.overIssued ?? 0,
          중복발급: r.verification?.duplicateIssued ?? 0,
        })),
    [comparisons],
  )

  // 시계열 비교: 실행마다 시작 시점을 0초로 맞춰서 겹친다
  const series = useMemo(() => {
    const rows = new Map<number, Record<string, number | null>>()
    comparisons.forEach(({ summary, series }) =>
      (series ?? []).forEach((p) => {
        const sec = Math.round(p.t / 1000)
        const row = rows.get(sec) ?? { sec }
        row[`tps_${summary.runId}`] = p.tps
        row[`p99_${summary.runId}`] = p.latencyMs.p99 == null ? null : Math.round(p.latencyMs.p99)
        rows.set(sec, row)
      }),
    )
    return [...rows.values()].sort((a, b) => (a.sec as number) - (b.sec as number))
  }, [comparisons])

  const lines = comparisons.map(({ summary }, i) => ({ id: summary.runId, name: label(summary), color: PALETTE[i % PALETTE.length] }))

  return (
    <div className="space-y-4">
      <Card
        title="실행 기록"
        right={
          <div className="flex items-center gap-3">
            <span className="text-xs text-slate-500">체크한 실행을 아래에서 비교 · simulation DB 기준</span>
            <Button onClick={load}>↻ 새로고침</Button>
          </div>
        }
      >
        {error && <div className="mb-3 rounded-md border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">{error}</div>}
        {runs?.length === 0 && <div className="py-8 text-center text-sm text-slate-500">아직 실행 기록이 없습니다. 시뮬레이션 탭에서 실행해 보세요.</div>}
        {runs && runs.length > 0 && (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-800 text-left text-xs text-slate-400">
                <th className="w-8 pb-2" />
                <th className="pb-2">#</th>
                <th className="pb-2">전략</th>
                <th className="pb-2">행동</th>
                <th className="pb-2">대상</th>
                <th className="pb-2 text-right">사용자</th>
                <th className="pb-2 text-right">재고</th>
                <th className="pb-2 text-right">요청 (재시도)</th>
                <th className="pb-2 text-right">평균 TPS</th>
                <th className="pb-2 text-right">p99</th>
                <th className="pb-2 text-right">소요</th>
                <th className="pb-2 pl-6">정합성 (DB)</th>
                <th className="pb-2 text-right">실행 시각</th>
                <th className="w-8 pb-2" />
              </tr>
            </thead>
            <tbody>
              {runs.map((r) => (
                <tr key={r.runId} className="border-b border-slate-800/60 hover:bg-slate-800/30">
                  <td className="py-2">
                    <input type="checkbox" className="size-4 accent-sky-500" checked={checked?.includes(r.runId) ?? false} onChange={() => toggle(r.runId)} />
                  </td>
                  <td className="py-2 text-slate-500">{r.runId}</td>
                  <td className="py-2 font-medium">
                    {r.strategy}
                    {r.queueEnabled && <span className="ml-2 rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] text-amber-400">대기열</span>}
                  </td>
                  <td className="py-2 text-xs text-slate-400">{r.behaviorPreset}</td>
                  <td className="py-2 text-xs text-slate-400" title={[r.targetBaseUrl, ...Object.entries(r.servedBy ?? {}).map(([k, v]) => `${k}: ${v}`)].join('\n')}>
                    {modeLabel(r)}
                  </td>
                  <td className="py-2 text-right tabular-nums">{fmt(r.userCount)}</td>
                  <td className="py-2 text-right tabular-nums">{fmt(r.stock)}</td>
                  <td className="py-2 text-right tabular-nums text-slate-400">
                    {r.requests != null ? fmt(r.requests) : '-'}
                    {r.retries ? <span className="text-rose-300"> ({fmt(r.retries)})</span> : null}
                  </td>
                  <td className="py-2 text-right tabular-nums">{r.avgTps != null ? fmt(r.avgTps) : '-'}</td>
                  <td className={`py-2 text-right tabular-nums ${(r.latencyMs.p99 ?? 0) > 1000 ? 'text-amber-400' : ''}`}>
                    {r.latencyMs.p99 != null ? `${fmt(Math.round(r.latencyMs.p99))}ms` : '-'}
                  </td>
                  <td className="py-2 text-right tabular-nums text-slate-400">{r.durationMs != null ? `${(r.durationMs / 1000).toFixed(1)}s` : '-'}</td>
                  <td className="py-2 pl-6">
                    <StateBadge run={r} />
                  </td>
                  <td className="py-2 text-right text-xs text-slate-500">{formatTime(r.startedAt)}</td>
                  <td className="py-2 text-right">
                    <button onClick={() => remove(r.runId)} className="text-xs text-slate-600 hover:text-rose-400" title="기록 삭제">
                      ✕
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {comparisons.length > 0 && (
        <>
          <div className="grid grid-cols-3 gap-4">
            <Card title="평균 TPS (높을수록 좋음)">
              <CompareChart data={bars} keys={[{ key: 'TPS', color: '#38bdf8' }]} />
            </Card>
            <Card title="p99 지연시간 ms (낮을수록 좋음)">
              <CompareChart data={bars} keys={[{ key: 'p99', color: '#f59e0b' }]} />
            </Card>
            <Card title="정합성 위반 건수">
              <CompareChart
                data={bars}
                keys={[
                  { key: '초과발급', color: '#ef4444' },
                  { key: '중복발급', color: '#f472b6' },
                ]}
                legend
              />
            </Card>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Card title="시간에 따른 TPS (시작 시점을 0초로 맞춤)">
              <SeriesChart data={series} prefix="tps" lines={lines} />
            </Card>
            <Card title="시간에 따른 p99 지연시간 ms (1초 구간별)">
              <SeriesChart data={series} prefix="p99" lines={lines} />
            </Card>
          </div>
        </>
      )}
    </div>
  )
}

function StateBadge({ run }: { run: RunSummary }) {
  if (run.state === 'RUNNING') return <Badge color="#38bdf8">실행 중</Badge>
  if (run.state === 'FAILED') return <span title={run.error ?? ''}><Badge color="#64748b">실패</Badge></span>
  const v = run.verification
  const stopped = run.state === 'STOPPED' ? <span className="ml-1.5 text-[10px] text-slate-500">중단됨</span> : null
  if (!v) return <span title={run.error ?? ''}><Badge color="#64748b">검증 없음</Badge>{stopped}</span>
  return (
    <span>
      {v.passed ? <Badge color="#22c55e">통과</Badge> : <Badge color="#ef4444">초과 {fmt(v.overIssued)} · 중복 {fmt(v.duplicateIssued)}</Badge>}
      {stopped}
    </span>
  )
}

/** 요청 대상과 응답한 인스턴스 수로 개발 모드/실측 모드를 구분해서 보여준다 */
function modeLabel(r: RunSummary) {
  const instances = Object.keys(r.servedBy ?? {}).length
  const host = r.targetBaseUrl.replace(/^https?:\/\//, '')
  const mode = host.startsWith('nginx') ? '실측' : '개발'
  return instances > 0 ? `${mode} · API ${instances}대` : mode
}

function formatTime(iso: string) {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function CompareChart({ data, keys, legend }: { data: object[]; keys: { key: string; color: string }[]; legend?: boolean }) {
  return (
    <div className="h-64">
      <ResponsiveContainer>
        <BarChart data={data} layout="vertical" margin={{ top: 0, right: 12, bottom: 0, left: 8 }}>
          <CartesianGrid stroke="#1e293b" horizontal={false} />
          <XAxis type="number" tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} axisLine={false} />
          <YAxis type="category" dataKey="name" width={150} interval={0} tick={{ fontSize: 11, fill: '#94a3b8' }} tickLine={false} axisLine={false} />
          <Tooltip cursor={{ fill: '#1e293b' }} contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 8, fontSize: 12 }} />
          {legend && <Legend wrapperStyle={{ fontSize: 11 }} />}
          {keys.map((k) => (
            <Bar key={k.key} dataKey={k.key} fill={k.color} radius={[0, 4, 4, 0]} barSize={14} />
          ))}
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

function SeriesChart({ data, prefix, lines }: { data: object[]; prefix: string; lines: { id: number; name: string; color: string }[] }) {
  return (
    <div className="h-64">
      <ResponsiveContainer>
        <LineChart data={data} margin={{ top: 4, right: 12, bottom: 0, left: -4 }}>
          <CartesianGrid stroke="#1e293b" vertical={false} />
          <XAxis dataKey="sec" unit="s" tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} axisLine={false} />
          <YAxis tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} axisLine={false} />
          <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 8, fontSize: 12 }} labelFormatter={(s) => `${s}초`} />
          <Legend wrapperStyle={{ fontSize: 11 }} />
          {lines.map((l) => (
            <Line key={l.id} type="monotone" dataKey={`${prefix}_${l.id}`} name={l.name} stroke={l.color} dot={false} strokeWidth={2} isAnimationActive={false} connectNulls />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
