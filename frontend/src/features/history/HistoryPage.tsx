import { useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Badge, Card, fmt } from '../../components/ui'

interface Run {
  id: number
  strategy: string
  queue: boolean
  users: number
  stock: number
  tps: number
  p99: number
  overIssued: number
  duplicate: number
  durationSec: number
  at: string
}

// 목업용 고정 데이터
const RUNS: Run[] = [
  { id: 7, strategy: 'REDIS_KAFKA', queue: false, users: 5000, stock: 100, tps: 4120, p99: 38, overIssued: 0, duplicate: 0, durationSec: 1.4, at: '11-02 14:21' },
  { id: 6, strategy: 'REDIS_ATOMIC', queue: true, users: 10000, stock: 1000, tps: 198, p99: 92, overIssued: 0, duplicate: 0, durationSec: 51.2, at: '11-02 14:12' },
  { id: 5, strategy: 'REDIS_ATOMIC', queue: false, users: 5000, stock: 100, tps: 2380, p99: 81, overIssued: 0, duplicate: 0, durationSec: 2.3, at: '11-02 14:05' },
  { id: 4, strategy: 'REDIS_LOCK', queue: false, users: 5000, stock: 100, tps: 910, p99: 310, overIssued: 0, duplicate: 0, durationSec: 5.6, at: '11-02 13:58' },
  { id: 3, strategy: 'DB_OPTIMISTIC', queue: false, users: 5000, stock: 100, tps: 440, p99: 1240, overIssued: 0, duplicate: 0, durationSec: 11.8, at: '11-02 13:47' },
  { id: 2, strategy: 'DB_PESSIMISTIC', queue: false, users: 5000, stock: 100, tps: 610, p99: 520, overIssued: 0, duplicate: 0, durationSec: 8.4, at: '11-02 13:40' },
  { id: 1, strategy: 'NAIVE', queue: false, users: 5000, stock: 100, tps: 2610, p99: 74, overIssued: 37, duplicate: 12, durationSec: 2.0, at: '11-02 13:31' },
]

export function HistoryPage() {
  const [checked, setChecked] = useState<number[]>([1, 2, 3, 4, 5, 7])
  const compared = RUNS.filter((r) => checked.includes(r.id))
    .reverse()
    .map((r) => ({ name: `#${r.id} ${r.strategy}`, TPS: r.tps, p99: r.p99, 초과발급: r.overIssued, 중복발급: r.duplicate }))

  const toggle = (id: number) => setChecked((c) => (c.includes(id) ? c.filter((x) => x !== id) : [...c, id]))

  return (
    <div className="space-y-4">
      <Card title="실행 기록" right={<span className="text-xs text-slate-500">체크한 실행을 아래에서 비교</span>}>
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-800 text-left text-xs text-slate-400">
              <th className="w-8 pb-2" />
              <th className="pb-2">#</th>
              <th className="pb-2">전략</th>
              <th className="pb-2 text-right">사용자</th>
              <th className="pb-2 text-right">재고</th>
              <th className="pb-2 text-right">TPS</th>
              <th className="pb-2 text-right">p99</th>
              <th className="pb-2 text-right">소요</th>
              <th className="pb-2 pl-6">정합성</th>
              <th className="pb-2 text-right">실행 시각</th>
            </tr>
          </thead>
          <tbody>
            {RUNS.map((r) => {
              const ok = r.overIssued === 0 && r.duplicate === 0
              return (
                <tr key={r.id} className="border-b border-slate-800/60 hover:bg-slate-800/30">
                  <td className="py-2">
                    <input type="checkbox" className="size-4 accent-sky-500" checked={checked.includes(r.id)} onChange={() => toggle(r.id)} />
                  </td>
                  <td className="py-2 text-slate-500">{r.id}</td>
                  <td className="py-2 font-medium">
                    {r.strategy}
                    {r.queue && <span className="ml-2 rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] text-amber-400">대기열</span>}
                  </td>
                  <td className="py-2 text-right tabular-nums">{fmt(r.users)}</td>
                  <td className="py-2 text-right tabular-nums">{fmt(r.stock)}</td>
                  <td className="py-2 text-right tabular-nums">{fmt(r.tps)}</td>
                  <td className={`py-2 text-right tabular-nums ${r.p99 > 500 ? 'text-amber-400' : ''}`}>{fmt(r.p99)}ms</td>
                  <td className="py-2 text-right tabular-nums text-slate-400">{r.durationSec}s</td>
                  <td className="py-2 pl-6">
                    {ok ? <Badge color="#22c55e">통과</Badge> : <Badge color="#ef4444">초과 {r.overIssued} · 중복 {r.duplicate}</Badge>}
                  </td>
                  <td className="py-2 text-right text-xs text-slate-500">{r.at}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </Card>

      <div className="grid grid-cols-3 gap-4">
        <Card title="TPS (높을수록 좋음)">
          <CompareChart data={compared} keys={[{ key: 'TPS', color: '#38bdf8' }]} />
        </Card>
        <Card title="p99 지연시간 ms (낮을수록 좋음)">
          <CompareChart data={compared} keys={[{ key: 'p99', color: '#f59e0b' }]} />
        </Card>
        <Card title="정합성 위반 건수">
          <CompareChart
            data={compared}
            keys={[
              { key: '초과발급', color: '#ef4444' },
              { key: '중복발급', color: '#f472b6' },
            ]}
            legend
          />
        </Card>
      </div>
    </div>
  )
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
