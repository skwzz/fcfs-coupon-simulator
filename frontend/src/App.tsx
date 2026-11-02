import { useState } from 'react'
import { HistoryPage } from './features/history/HistoryPage'
import { LiveJoinPage } from './features/live-join/LiveJoinPage'
import { SimulationPage } from './features/simulation/SimulationPage'

const TABS = [
  { key: 'simulation', label: '시뮬레이션' },
  { key: 'live', label: '직접 참여' },
  { key: 'history', label: '실행 기록 / 비교' },
] as const

type TabKey = (typeof TABS)[number]['key']

export default function App() {
  const [tab, setTab] = useState<TabKey>('simulation')

  return (
    <div className="min-h-full">
      <header className="sticky top-0 z-10 border-b border-slate-800 bg-slate-950/90 backdrop-blur">
        <div className="mx-auto flex max-w-[1440px] items-center gap-8 px-6 py-3">
          <div className="flex items-center gap-2">
            <span className="text-xl">🎟</span>
            <span className="font-bold tracking-tight">FCFS Coupon Simulator</span>
            <span className="rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-semibold text-amber-400">MOCKUP</span>
          </div>
          <nav className="flex gap-1">
            {TABS.map((t) => (
              <button
                key={t.key}
                onClick={() => setTab(t.key)}
                className={`rounded-md px-3 py-1.5 text-sm transition ${tab === t.key ? 'bg-slate-800 text-white' : 'text-slate-400 hover:text-slate-200'}`}
              >
                {t.label}
              </button>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-4 text-xs text-slate-500">
            {['MySQL', 'Redis', 'Kafka'].map((s) => (
              <span key={s} className="flex items-center gap-1.5">
                <span className="size-1.5 rounded-full bg-emerald-400" />
                {s}
              </span>
            ))}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-[1440px] px-6 py-5">
        {tab === 'simulation' && <SimulationPage />}
        {tab === 'live' && <LiveJoinPage />}
        {tab === 'history' && <HistoryPage />}
      </main>
    </div>
  )
}
