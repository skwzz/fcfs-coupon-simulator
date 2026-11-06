import { useMemo } from 'react'
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, fmt } from '../../components/ui'
import type { DoneMsg, TickMsg } from '../../sim/types'

const COLORS = ['#38bdf8', '#f59e0b', '#22c55e', '#a78bfa']

/**
 * 인스턴스별로 요청이 어떻게 나뉘었는지(X-Served-By)와 서버 측 지표(HikariCP, CPU, 힙).
 * 힙은 대기열 SSE 연결이 메모리를 얼마나 쓰는지 볼 때 쓴다 (연결 하나에 약 100KB).
 * 개발 모드는 coupon-api-local 하나, 실측 모드는 coupon-api-1/2 두 대가 보인다.
 */
export function ServerPanel({ ticks, done }: { ticks: TickMsg[]; done: DoneMsg | null }) {
  const last = ticks.at(-1)
  const servedBy = done?.servedBy ?? last?.servedBy ?? {}
  const server = last?.server ?? {}
  const instances = [...new Set([...Object.keys(servedBy), ...Object.keys(server), ...Object.keys(done?.serverPeak ?? {})])].sort()
  const total = Object.values(servedBy).reduce((a, b) => a + b, 0)

  const pendingData = useMemo(
    () =>
      ticks
        .filter((_, i) => i % 5 === 4) // 서버 지표는 1초마다 바뀌므로 1초에 한 점
        .map((t) => ({ sec: (t.t / 1000).toFixed(0), ...Object.fromEntries(Object.entries(t.server ?? {}).map(([k, v]) => [k, v.hikariPending])) })),
    [ticks],
  )

  if (instances.length === 0) {
    return (
      <Card title="서버 (인스턴스별)">
        <div className="py-6 text-center text-xs text-slate-500">실행하면 인스턴스별 처리 비율과 DB 커넥션·CPU 지표가 여기 나옵니다.</div>
      </Card>
    )
  }

  return (
    <Card title="서버 (인스턴스별)" right={<span className="text-xs text-slate-500">처리 비율은 X-Served-By 응답 헤더 기준</span>}>
      <div className="grid grid-cols-[1fr_1fr] gap-6">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-slate-400">
              <th className="pb-2">인스턴스</th>
              <th className="pb-2">처리 비율</th>
              <th className="pb-2 text-right">DB 커넥션 사용 / 대기</th>
              <th className="pb-2 text-right">CPU</th>
              <th className="pb-2 text-right">힙</th>
            </tr>
          </thead>
          <tbody>
            {instances.map((name, i) => {
              const count = servedBy[name] ?? 0
              const ratio = total > 0 ? count / total : 0
              const now = server[name]
              const peak = done?.serverPeak?.[name]
              return (
                <tr key={name} className="border-t border-slate-800/60">
                  <td className="py-2 font-medium">
                    <span className="mr-2 inline-block size-2 rounded-full" style={{ backgroundColor: COLORS[i % COLORS.length] }} />
                    {name}
                  </td>
                  <td className="py-2">
                    <div className="flex items-center gap-2">
                      <div className="h-2 w-24 overflow-hidden rounded-full bg-slate-800">
                        <div className="h-full rounded-full" style={{ width: `${ratio * 100}%`, backgroundColor: COLORS[i % COLORS.length] }} />
                      </div>
                      <span className="text-xs tabular-nums text-slate-300">
                        {Math.round(ratio * 100)}% · {fmt(count)}
                      </span>
                    </div>
                  </td>
                  <td className="py-2 text-right tabular-nums">
                    {done && peak ? (
                      <span className="text-xs text-slate-400">
                        최대 대기 <b className={peak.hikariPending > 0 ? 'text-amber-400' : ''}>{fmt(peak.hikariPending)}</b>
                      </span>
                    ) : now ? (
                      <>
                        {now.hikariActive} / <span className={now.hikariPending > 0 ? 'text-amber-400' : ''}>{fmt(now.hikariPending)}</span>
                      </>
                    ) : (
                      '-'
                    )}
                  </td>
                  <td className="py-2 text-right tabular-nums">
                    {done && peak ? <span className="text-xs text-slate-400">최대 {peak.cpuPercent}%</span> : now ? `${now.cpuPercent}%` : '-'}
                  </td>
                  <td className="py-2 text-right tabular-nums">
                    {done && peak ? (
                      <span className="text-xs text-slate-400">{peak.heapUsedMb != null ? `최대 ${fmt(peak.heapUsedMb)}MB` : '-'}</span>
                    ) : now?.heapUsedMb != null ? (
                      `${fmt(now.heapUsedMb)}MB`
                    ) : (
                      '-'
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
        <div>
          <div className="mb-1 text-xs text-slate-400">DB 커넥션을 기다리는 요청 수 (Hikari pending)</div>
          <div className="h-36">
            <ResponsiveContainer>
              <LineChart data={pendingData} margin={{ top: 4, right: 8, bottom: 0, left: -12 }}>
                <CartesianGrid stroke="#1e293b" vertical={false} />
                <XAxis dataKey="sec" unit="s" tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} axisLine={false} minTickGap={30} />
                <YAxis tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} axisLine={false} allowDecimals={false} />
                <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 8, fontSize: 12 }} />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                {instances.map((name, i) => (
                  <Line key={name} type="monotone" dataKey={name} stroke={COLORS[i % COLORS.length]} dot={false} strokeWidth={2} isAnimationActive={false} />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>
      </div>
    </Card>
  )
}
