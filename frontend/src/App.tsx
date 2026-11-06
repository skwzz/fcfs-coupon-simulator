import { useState } from 'react'
import { HistoryPage } from './features/history/HistoryPage'
import { LiveJoinPage } from './features/live-join/LiveJoinPage'
import { SimulationPage } from './features/simulation/SimulationPage'
import { HealthDots } from './components/HealthDots'

const TABS = [
  { key: 'simulation', label: '시뮬레이션' },
  { key: 'live', label: '직접 참여', mock: true },
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
          </div>
          <nav className="flex gap-1">
            {TABS.map((t) => (
              <button
                key={t.key}
                onClick={() => setTab(t.key)}
                className={`rounded-md px-3 py-1.5 text-sm transition ${tab === t.key ? 'bg-slate-800 text-white' : 'text-slate-400 hover:text-slate-200'}`}
              >
                {t.label}
                {'mock' in t && <span className="ml-1.5 rounded bg-amber-500/15 px-1 py-0.5 text-[9px] font-semibold text-amber-400">목업</span>}
              </button>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-4 text-xs text-slate-500">
            <HealthDots />
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-[1440px] px-6 py-5">
        {/* 탭을 옮겨도 화면을 없애지 않고 숨기기만 한다. 없애면 실행 중인 시뮬레이션의 SSE 연결과 화면 상태가 사라진다 */}
        <div hidden={tab !== 'simulation'}>
          <SimulationPage />
        </div>
        <div hidden={tab !== 'live'}>
          <LiveJoinPage />
        </div>
        {/* 실행 기록은 들어올 때마다 새로 불러오도록 매번 다시 만든다 */}
        {tab === 'history' && <HistoryPage />}
      </main>
    </div>
  )
}
