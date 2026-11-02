import { useMemo, useState, type ReactNode } from 'react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Badge, Button, Card, Field, Kpi, fmt, inputClass } from '../../components/ui'
import { QueueScreen } from '../../components/QueueScreen'
import { STATUSES, STATUS_META, STRATEGIES, type SimConfig, type Status } from '../../mock/types'
import { useMockSimulation } from '../../mock/useMockSimulation'
import { GridLegend, UserGrid } from './UserGrid'
import { toQueueView } from './userView'

const DEFAULT_CONFIG: SimConfig = {
  strategy: 'REDIS_ATOMIC',
  stock: 1000,
  userCount: 3000,
  requestsPerUser: 1,
  rampUpMs: 0,
  queueEnabled: true,
  admitPerSecond: 100,
  notifyMode: 'SSE',
}

export function SimulationPage() {
  const [config, setConfig] = useState<SimConfig>(DEFAULT_CONFIG)
  const { engine, counts, start, stop, reset } = useMockSimulation(config)
  const [selected, setSelected] = useState<number | null>(null)
  const [pinned, setPinned] = useState<number[]>([0, 1499, 2999])

  const last = engine.ticks.at(-1)
  const waiting = counts.WAITING
  const finished = counts.ISSUED + counts.SOLD_OUT + counts.DUPLICATE + counts.ERROR
  const overIssued = Math.max(0, counts.ISSUED - config.stock)
  const chartData = useMemo(() => engine.ticks.map((t) => ({ ...t, sec: (t.t / 1000).toFixed(1) })), [engine.t])

  const set = <K extends keyof SimConfig>(k: K, v: SimConfig[K]) => setConfig((c) => ({ ...c, [k]: v }))
  const locked = engine.running

  return (
    <div className="grid grid-cols-[260px_1fr] gap-4">
      {/* 설정 패널 */}
      <aside className="space-y-4">
        <Card title="시뮬레이션 설정">
          <div className="space-y-3">
            <Field label="발급 전략">
              <select className={inputClass} value={config.strategy} disabled={locked} onChange={(e) => set('strategy', e.target.value as SimConfig['strategy'])}>
                {STRATEGIES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </Field>
            <div className="grid grid-cols-2 gap-2">
              <Field label="재고">
                <input type="number" className={inputClass} value={config.stock} disabled={locked} onChange={(e) => set('stock', +e.target.value)} />
              </Field>
              <Field label="사용자 수">
                <input type="number" className={inputClass} value={config.userCount} disabled={locked} onChange={(e) => set('userCount', Math.min(10000, +e.target.value))} />
              </Field>
              <Field label="1인당 요청">
                <input type="number" className={inputClass} value={config.requestsPerUser} disabled={locked} onChange={(e) => set('requestsPerUser', +e.target.value)} />
              </Field>
              <Field label="램프업 (ms)">
                <input type="number" className={inputClass} value={config.rampUpMs} disabled={locked} onChange={(e) => set('rampUpMs', +e.target.value)} />
              </Field>
            </div>

            <div className="rounded-lg border border-slate-800 p-3">
              <label className="flex items-center justify-between text-sm">
                <span>대기열 사용</span>
                <input type="checkbox" className="size-4 accent-sky-500" checked={config.queueEnabled} disabled={locked} onChange={(e) => set('queueEnabled', e.target.checked)} />
              </label>
              {config.queueEnabled && (
                <div className="mt-3 space-y-3">
                  <Field label="초당 입장 인원">
                    <input type="number" className={inputClass} value={config.admitPerSecond} disabled={locked} onChange={(e) => set('admitPerSecond', +e.target.value)} />
                  </Field>
                  <Field label="순번 알림 방식">
                    <div className="grid grid-cols-2 gap-1 rounded-md bg-slate-950 p-1">
                      {(['SSE', 'POLLING'] as const).map((m) => (
                        <button
                          key={m}
                          disabled={locked}
                          onClick={() => set('notifyMode', m)}
                          className={`rounded px-2 py-1 text-xs ${config.notifyMode === m ? 'bg-slate-700 text-white' : 'text-slate-400'}`}
                        >
                          {m === 'SSE' ? 'SSE push' : '폴링'}
                        </button>
                      ))}
                    </div>
                  </Field>
                </div>
              )}
            </div>

            <div className="grid grid-cols-2 gap-2 pt-1">
              {engine.running ? (
                <Button variant="danger" onClick={stop} className="col-span-2">
                  ■ 중단
                </Button>
              ) : (
                <Button variant="primary" onClick={start} className="col-span-2">
                  ▶ {engine.t > 0 && !engine.done ? '계속' : '시작'}
                </Button>
              )}
              <Button onClick={reset} disabled={locked} className="col-span-2">
                ↺ 초기화
              </Button>
            </div>
          </div>
        </Card>

        <Card title="결과 분포">
          <ResultBar counts={counts} total={config.userCount} />
        </Card>
      </aside>

      {/* 메인 영역 */}
      <div className="min-w-0 space-y-4">
        <div className="grid grid-cols-5 gap-3">
          <Kpi label="남은 재고" value={`${fmt(engine.stockLeft)} / ${fmt(config.stock)}`} sub={`발급 ${fmt(counts.ISSUED)}건`} tone={engine.stockLeft === 0 ? 'bad' : 'default'} />
          <Kpi label="TPS" value={fmt(last?.tps ?? 0)} sub="최근 200ms 기준" />
          <Kpi label="지연시간 p99" value={`${last?.p99 ?? 0}ms`} sub={`p50 ${last?.p50 ?? 0} · p95 ${last?.p95 ?? 0}`} tone={(last?.p99 ?? 0) > 300 ? 'warn' : 'default'} />
          <Kpi label="대기열" value={fmt(waiting)} sub={config.queueEnabled ? `${config.admitPerSecond}/s 입장` : '대기열 꺼짐'} tone={waiting > 0 ? 'warn' : 'default'} />
          <Kpi label="진행" value={`${Math.round((finished / config.userCount) * 100)}%`} sub={`${(engine.t / 1000).toFixed(1)}초 경과`} />
        </div>

        <div className="grid grid-cols-[1fr_220px] gap-4">
          <Card
            title={`가상 사용자 ${fmt(config.userCount)}명`}
            right={<span className="text-xs text-slate-500">셀을 클릭하면 사용자 상세</span>}
          >
            <UserGrid
              status={engine.status}
              rank={engine.rank}
              version={engine.t}
              selected={selected}
              pinned={pinned}
              onSelect={setSelected}
            />
            <div className="mt-3 border-t border-slate-800 pt-3">
              <GridLegend />
            </div>
          </Card>

          {/* 고정한 사용자가 보는 화면 */}
          <div className="space-y-3">
            <div className="text-xs font-semibold text-slate-400">사용자 화면 미리보기</div>
            {pinned.map((id) => (
              <PhoneView key={id} id={id} onUnpin={() => setPinned((p) => p.filter((x) => x !== id))}>
                <QueueScreen compact view={toQueueView(engine.status[id], engine.rank[id], waiting, config.admitPerSecond)} />
              </PhoneView>
            ))}
            {pinned.length === 0 && <div className="rounded-xl border border-dashed border-slate-800 p-4 text-center text-xs text-slate-500">사용자 상세에서 📌 고정</div>}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <Card title="TPS">
            <Chart data={chartData} lines={[{ key: 'tps', color: '#38bdf8', name: 'TPS' }]} />
          </Card>
          <Card title="지연시간 (ms)">
            <Chart
              data={chartData}
              lines={[
                { key: 'p50', color: '#22c55e', name: 'p50' },
                { key: 'p95', color: '#f59e0b', name: 'p95' },
                { key: 'p99', color: '#ef4444', name: 'p99' },
              ]}
            />
          </Card>
        </div>

        {engine.done && (
          <div
            className={`flex items-center justify-between rounded-xl border px-5 py-4 ${
              overIssued > 0 ? 'border-rose-500/40 bg-rose-500/10' : 'border-emerald-500/40 bg-emerald-500/10'
            }`}
          >
            <div>
              <div className={`text-lg font-bold ${overIssued > 0 ? 'text-rose-400' : 'text-emerald-400'}`}>
                {overIssued > 0 ? '❌ 정합성 검증 실패' : '✅ 정합성 검증 통과'}
              </div>
              <div className="mt-0.5 text-sm text-slate-300">
                DB 발급 {fmt(counts.ISSUED)}건 / 재고 {fmt(config.stock)} · 초과 발급 {fmt(overIssued)}건 · 중복 발급 {overIssued > 0 ? 12 : 0}건
              </div>
            </div>
            <div className="text-right text-xs text-slate-400">
              총 {(engine.t / 1000).toFixed(1)}초 · 전략 {config.strategy}
            </div>
          </div>
        )}
      </div>

      {selected != null && (
        <UserDrawer
          id={selected}
          timeline={engine.timelines.get(selected) ?? [{ t: 0, status: 'IDLE' }]}
          pinned={pinned.includes(selected)}
          onPin={() => setPinned((p) => (p.includes(selected) ? p.filter((x) => x !== selected) : [...p.slice(-2), selected]))}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  )
}

function ResultBar({ counts, total }: { counts: Record<Status, number>; total: number }) {
  return (
    <div>
      <div className="flex h-3 overflow-hidden rounded-full bg-slate-800">
        {STATUSES.filter((s) => s !== 'IDLE').map((s) => (
          <div key={s} style={{ width: `${(counts[s] / total) * 100}%`, backgroundColor: STATUS_META[s].color }} className="transition-all duration-200" />
        ))}
      </div>
      <dl className="mt-3 space-y-1.5">
        {STATUSES.map((s) => (
          <div key={s} className="flex items-center justify-between text-xs">
            <dt className="flex items-center gap-2 text-slate-400">
              <span className="size-2 rounded-sm" style={{ backgroundColor: STATUS_META[s].color }} />
              {STATUS_META[s].label}
            </dt>
            <dd className="tabular-nums text-slate-200">{fmt(counts[s])}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

function Chart({ data, lines }: { data: object[]; lines: { key: string; color: string; name: string }[] }) {
  return (
    <div className="h-44">
      <ResponsiveContainer>
        <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -16 }}>
          <CartesianGrid stroke="#1e293b" vertical={false} />
          <XAxis dataKey="sec" tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} axisLine={false} minTickGap={30} />
          <YAxis tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} axisLine={false} />
          <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 8, fontSize: 12 }} />
          {lines.map((l) => (
            <Line key={l.key} type="monotone" dataKey={l.key} name={l.name} stroke={l.color} dot={false} strokeWidth={2} isAnimationActive={false} />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

function PhoneView({ id, onUnpin, children }: { id: number; onUnpin: () => void; children: ReactNode }) {
  return (
    <div className="overflow-hidden rounded-[18px] border-4 border-slate-700 bg-slate-950 shadow-lg">
      <div className="flex items-center justify-between bg-slate-900 px-3 py-1 text-[10px] text-slate-500">
        <span>user #{id + 1}</span>
        <button onClick={onUnpin} className="hover:text-slate-200" title="고정 해제">
          ✕
        </button>
      </div>
      {children}
    </div>
  )
}

function UserDrawer({
  id,
  timeline,
  pinned,
  onPin,
  onClose,
}: {
  id: number
  timeline: { t: number; status: Status; rank?: number; note?: string }[]
  pinned: boolean
  onPin: () => void
  onClose: () => void
}) {
  const current = timeline.at(-1)!
  return (
    <div className="fixed inset-y-0 right-0 z-20 flex w-80 flex-col border-l border-slate-800 bg-slate-900 shadow-2xl">
      <header className="flex items-center justify-between border-b border-slate-800 px-4 py-3">
        <div>
          <div className="text-sm font-semibold">user #{id + 1}</div>
          <div className="mt-1">
            <Badge color={STATUS_META[current.status].color}>{STATUS_META[current.status].label}</Badge>
          </div>
        </div>
        <div className="flex gap-2">
          <Button onClick={onPin}>{pinned ? '📌 해제' : '📌 고정'}</Button>
          <Button onClick={onClose}>✕</Button>
        </div>
      </header>
      <ol className="flex-1 overflow-y-auto px-4 py-3">
        {timeline.map((e, i) => (
          <li key={i} className="relative flex gap-3 pb-4 pl-4">
            <span className="absolute top-1.5 left-0 size-2 rounded-full" style={{ backgroundColor: STATUS_META[e.status].color }} />
            {i < timeline.length - 1 && <span className="absolute top-4 left-[3px] h-full w-px bg-slate-700" />}
            <span className="w-14 shrink-0 text-xs tabular-nums text-slate-500">{e.t < 1000 ? `${e.t}ms` : `${(e.t / 1000).toFixed(1)}s`}</span>
            <span className="text-xs">
              <span style={{ color: STATUS_META[e.status].color }}>{e.status}</span>
              {e.rank != null && <span className="ml-1.5 text-slate-300">순번 {fmt(e.rank)}</span>}
              {e.note && <div className="mt-0.5 text-slate-500">{e.note}</div>}
            </span>
          </li>
        ))}
      </ol>
    </div>
  )
}
