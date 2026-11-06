import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Badge, Button, Card, Field, Kpi, fmt, inputClass } from '../../components/ui'
import { QueueScreen } from '../../components/QueueScreen'
import { BEHAVIORS, DISPLAY_ORDER, STATUSES, STATUS_META, STRATEGIES, type DoneMsg, type RunSummary, type SimConfig, type Status, type TimelineEntry } from '../../sim/types'
import { rememberedRunId, useSimulation, useUserDetail } from '../../sim/useSimulation'
import { ServerPanel } from './ServerPanel'
import { GridLegend, UserGrid } from './UserGrid'
import { toQueueView } from './userView'

const DEFAULT_CONFIG: SimConfig = {
  strategy: 'NAIVE',
  stock: 100,
  userCount: 3000,
  rampUpMs: 0,
  behavior: 'IDEAL',
  queueEnabled: false,
  admitPerSecond: 100,
  notifyMode: 'SSE',
}

const defaultPins = (n: number) => [...new Set([0, Math.floor(n / 2), n - 1])]

export function SimulationPage() {
  const [config, setConfig] = useState<SimConfig>(DEFAULT_CONFIG)
  const { run, version, start, stop, reset, attach } = useSimulation(config)

  // 새로고침했을 때 마지막 실행(진행 중이든 끝났든)을 다시 불러온다
  useEffect(() => {
    const runId = rememberedRunId()
    if (runId == null) return
    fetch(`/api/simulations/${runId}`)
      .then((res) => (res.ok ? res.json() : null))
      .then((summary: RunSummary | null) => {
        if (!summary) return
        setConfig((c) => ({
          ...c,
          strategy: summary.strategy,
          stock: summary.stock,
          userCount: summary.userCount,
          rampUpMs: summary.rampUpMs,
          behavior: summary.behaviorPreset,
        }))
        attach(summary)
      })
      .catch(() => {})
  }, [attach])
  const [selected, setSelected] = useState<number | null>(null)
  const [pinned, setPinned] = useState<number[]>(defaultPins(DEFAULT_CONFIG.userCount))

  useEffect(() => {
    setPinned(defaultPins(run.userCount))
    setSelected(null)
  }, [run.userCount])

  const counts = useMemo(() => {
    const c = Object.fromEntries(STATUSES.map((s) => [s, 0])) as Record<Status, number>
    for (let i = 0; i < run.status.length; i++) c[STATUSES[run.status[i]]]++
    return c
  }, [run.status, version])

  const last = run.ticks.at(-1)
  const lastLatency = [...run.ticks].reverse().find((t) => t.latencyMs.p99 != null)?.latencyMs
  const issued = counts.ISSUED + counts.ACCEPTED
  const finished = issued + counts.SOLD_OUT + counts.DUPLICATE + counts.ERROR + counts.ABANDONED
  const elapsed = last?.t ?? 0
  const chartData = useMemo(
    () => run.ticks.map((t) => ({ sec: (t.t / 1000).toFixed(1), tps: t.tps, p50: t.latencyMs.p50, p95: t.latencyMs.p95, p99: t.latencyMs.p99 })),
    [run.ticks],
  )

  const set = <K extends keyof SimConfig>(k: K, v: SimConfig[K]) => setConfig((c) => ({ ...c, [k]: v }))
  const busy = run.phase === 'starting' || run.phase === 'running' || run.phase === 'stopping'

  return (
    <div className="grid grid-cols-[260px_1fr] gap-4">
      {/* 설정 패널 */}
      <aside className="space-y-4">
        <Card title="시뮬레이션 설정">
          <div className="space-y-3">
            <Field label="발급 전략">
              <select className={inputClass} value={config.strategy} disabled={busy} onChange={(e) => set('strategy', e.target.value as SimConfig['strategy'])}>
                {STRATEGIES.map((s) => (
                  <option key={s.value} value={s.value} disabled={'phase' in s}>
                    {s.label}
                    {'phase' in s ? ` (Phase ${s.phase})` : ''}
                  </option>
                ))}
              </select>
            </Field>
            <div className="grid grid-cols-2 gap-2">
              <Field label="재고">
                <input type="number" className={inputClass} value={config.stock} disabled={busy} onChange={(e) => set('stock', Math.max(1, +e.target.value))} />
              </Field>
              <Field label="사용자 수">
                <input type="number" className={inputClass} value={config.userCount} disabled={busy} onChange={(e) => set('userCount', Math.min(10000, Math.max(1, +e.target.value)))} />
              </Field>
              <Field label="행동 모델">
                <select className={inputClass} value={config.behavior} disabled={busy} onChange={(e) => set('behavior', e.target.value as SimConfig['behavior'])}>
                  {BEHAVIORS.map((b) => (
                    <option key={b.value} value={b.value} disabled={'phase' in b}>
                      {b.label}
                      {'phase' in b ? ` (Phase ${b.phase})` : ''}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="램프업 (ms)">
                <input type="number" className={inputClass} value={config.rampUpMs} disabled={busy} onChange={(e) => set('rampUpMs', Math.max(0, +e.target.value))} />
              </Field>
            </div>

            <div className="rounded-lg border border-slate-800 p-3 opacity-60">
              <label className="flex items-center justify-between text-sm">
                <span>
                  대기열 사용 <span className="text-xs text-slate-500">(Phase 5)</span>
                </span>
                <input type="checkbox" className="size-4 accent-sky-500" checked={false} disabled />
              </label>
            </div>

            {run.error && <div className="rounded-md border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">{run.error}</div>}

            <div className="grid grid-cols-2 gap-2 pt-1">
              {busy ? (
                <Button variant="danger" onClick={stop} disabled={run.phase !== 'running'} className="col-span-2">
                  {run.phase === 'starting' ? '이벤트 준비 중…' : run.phase === 'stopping' ? '중단하는 중…' : '■ 중단'}
                </Button>
              ) : (
                <Button variant="primary" onClick={start} className="col-span-2">
                  ▶ {run.phase === 'done' ? '다시 시작' : '시작'}
                </Button>
              )}
              <Button onClick={reset} disabled={busy} className="col-span-2">
                ↺ 초기화
              </Button>
            </div>
            {run.runId != null && (
              <div className="text-center text-[11px] text-slate-500">
                run #{run.runId} · event #{run.eventId}
              </div>
            )}
          </div>
        </Card>

        <Card title="결과 분포">
          <ResultBar counts={counts} total={run.userCount} />
        </Card>
      </aside>

      {/* 메인 영역 */}
      <div className="min-w-0 space-y-4">
        <div className="grid grid-cols-5 gap-3">
          <Kpi
            label="남은 재고"
            value={`${fmt(last?.stockLeft ?? run.stock)} / ${fmt(run.stock)}`}
            sub={`성공 응답 ${fmt(issued)}건`}
            tone={issued > run.stock ? 'bad' : (last?.stockLeft ?? run.stock) === 0 ? 'warn' : 'default'}
          />
          <Kpi label="TPS" value={fmt(last?.tps ?? 0)} sub="최근 1초 완료 건수" />
          <Kpi
            label="지연시간 p99"
            value={lastLatency?.p99 != null ? `${fmt(Math.round(lastLatency.p99))}ms` : '-'}
            sub={lastLatency ? `p50 ${fmt(Math.round(lastLatency.p50 ?? 0))} · p95 ${fmt(Math.round(lastLatency.p95 ?? 0))}` : '구간별 측정'}
            tone={(lastLatency?.p99 ?? 0) > 1000 ? 'warn' : 'default'}
          />
          <Kpi label="요청 중" value={fmt(counts.REQUESTING)} sub="응답을 기다리는 사용자" tone={counts.REQUESTING > 0 ? 'warn' : 'default'} />
          <Kpi label="진행" value={`${Math.round((finished / run.userCount) * 100)}%`} sub={`${(elapsed / 1000).toFixed(1)}초 경과`} />
        </div>

        <div className="grid grid-cols-[1fr_220px] gap-4">
          <Card title={`가상 사용자 ${fmt(run.userCount)}명`} right={<span className="text-xs text-slate-500">셀을 클릭하면 사용자 상세</span>}>
            <UserGrid status={run.status} rank={run.rank} version={version} selected={selected} pinned={pinned} onSelect={setSelected} />
            <div className="mt-3 border-t border-slate-800 pt-3">
              <GridLegend />
            </div>
          </Card>

          {/* 고정한 사용자가 보는 화면 */}
          <div className="space-y-3">
            <div className="text-xs font-semibold text-slate-400">사용자 화면 미리보기</div>
            {pinned
              .filter((id) => id < run.userCount)
              .map((id) => (
                <PhoneView key={id} id={id} onUnpin={() => setPinned((p) => p.filter((x) => x !== id))}>
                  <QueueScreen compact view={toQueueView(run.status[id], run.rank[id], counts.WAITING, config.admitPerSecond)} />
                </PhoneView>
              ))}
            {pinned.length === 0 && <div className="rounded-xl border border-dashed border-slate-800 p-4 text-center text-xs text-slate-500">사용자 상세에서 📌 고정</div>}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <Card title="TPS">
            <Chart data={chartData} lines={[{ key: 'tps', color: '#38bdf8', name: 'TPS' }]} />
          </Card>
          <Card title="지연시간 (ms, 200ms 구간별)">
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

        <ServerPanel ticks={run.ticks} done={run.done} />

        {run.done && <ResultBanner done={run.done} strategy={config.strategy} />}
      </div>

      {selected != null && (
        <UserDrawer
          id={selected}
          runId={run.runId}
          statusCode={run.status[selected]}
          pinned={pinned.includes(selected)}
          onPin={() => setPinned((p) => (p.includes(selected) ? p.filter((x) => x !== selected) : [...p.slice(-2), selected]))}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  )
}

function ResultBanner({ done, strategy }: { done: DoneMsg; strategy: string }) {
  const v = done.verification
  const ok = v?.passed ?? false
  const ms = (n: number | null) => (n == null ? '-' : `${fmt(Math.round(n))}ms`)
  return (
    <div className={`rounded-xl border px-5 py-4 ${ok ? 'border-emerald-500/40 bg-emerald-500/10' : 'border-rose-500/40 bg-rose-500/10'}`}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className={`text-lg font-bold ${ok ? 'text-emerald-400' : 'text-rose-400'}`}>
            {v == null ? '⚠️ 정합성 검증 실패 (조회 불가)' : ok ? '✅ 정합성 검증 통과' : '❌ 정합성 검증 실패'}
            {done.state === 'STOPPED' && <span className="ml-2 text-sm font-normal text-slate-400">(중단됨)</span>}
          </div>
          {v ? (
            <div className="mt-0.5 text-sm text-slate-300">
              DB 발급 {fmt(v.issuedInDb)}건 / 재고 {fmt(v.totalQuantity)} · 초과 발급 <b>{fmt(v.overIssued)}</b>건 · 중복 발급 <b>{fmt(v.duplicateIssued)}</b>건
              <span className="text-slate-500"> · 발급 카운터 {fmt(v.issuedQuantityCounter)}</span>
            </div>
          ) : (
            <div className="mt-0.5 text-sm text-slate-300">{done.error}</div>
          )}
          {v && v.issuedQuantityCounter !== v.issuedInDb && (
            <div className="mt-1 text-xs text-slate-400">카운터와 실제 발급 행 수가 다릅니다. 동시에 읽은 값에 +1을 덮어써서 갱신이 사라졌기 때문입니다 (lost update).</div>
          )}
        </div>
        <div className="shrink-0 text-right text-xs leading-5 text-slate-400">
          <div>
            전략 {strategy} · 총 {(done.durationMs / 1000).toFixed(1)}초 · 평균 {fmt(done.avgTps)} TPS
          </div>
          <div>
            지연 p50 {ms(done.latencyMs.p50)} · p95 {ms(done.latencyMs.p95)} · p99 {ms(done.latencyMs.p99)} · max {ms(done.latencyMs.max)}
          </div>
        </div>
      </div>
    </div>
  )
}

function ResultBar({ counts, total }: { counts: Record<Status, number>; total: number }) {
  return (
    <div>
      <div className="flex h-3 overflow-hidden rounded-full bg-slate-800">
        {DISPLAY_ORDER.filter((s) => s !== 'IDLE').map((s) => (
          <div key={s} style={{ width: `${(counts[s] / total) * 100}%`, backgroundColor: STATUS_META[s].color }} className="transition-all duration-200" />
        ))}
      </div>
      <dl className="mt-3 space-y-1.5">
        {DISPLAY_ORDER.map((s) => (
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
        <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -8 }}>
          <CartesianGrid stroke="#1e293b" vertical={false} />
          <XAxis dataKey="sec" tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} axisLine={false} minTickGap={30} />
          <YAxis tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} axisLine={false} />
          <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 8, fontSize: 12 }} />
          {lines.map((l) => (
            <Line key={l.key} type="monotone" dataKey={l.key} name={l.name} stroke={l.color} dot={false} strokeWidth={2} isAnimationActive={false} connectNulls />
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
  runId,
  statusCode,
  pinned,
  onPin,
  onClose,
}: {
  id: number
  runId: number | null
  statusCode: number
  pinned: boolean
  onPin: () => void
  onClose: () => void
}) {
  const detail = useUserDetail(runId, id, statusCode)
  const timeline: TimelineEntry[] = detail?.timeline ?? [{ t: 0, status: 'IDLE' }]
  const current = STATUSES[statusCode]
  return (
    <div className="fixed inset-y-0 right-0 z-20 flex w-80 flex-col border-l border-slate-800 bg-slate-900 shadow-2xl">
      <header className="flex items-center justify-between border-b border-slate-800 px-4 py-3">
        <div>
          <div className="text-sm font-semibold">user #{id + 1}</div>
          <div className="mt-1">
            <Badge color={STATUS_META[current].color}>{STATUS_META[current].label}</Badge>
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
