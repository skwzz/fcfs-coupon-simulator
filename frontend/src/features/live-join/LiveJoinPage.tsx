import { useEffect, useRef, useState, type ReactNode } from 'react'
import { QueueScreen, type QueueView } from '../../components/QueueScreen'
import { Card, fmt } from '../../components/ui'

type Outcome = 'issued' | 'sold_out' | 'duplicate'

const START_RANK = 3214
const ADMIT_PER_SEC = 200

// 시뮬레이션이 돌고 있는 상황에서 "나"도 실제 사용자처럼 참여해 보는 화면 (목업)
export function LiveJoinPage() {
  const [view, setView] = useState<QueueView>({ kind: 'idle' })
  const [open, setOpen] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>('issued')
  const [stockLeft, setStockLeft] = useState(412)
  const timer = useRef<number | undefined>(undefined)

  const clear = () => window.clearInterval(timer.current)
  useEffect(() => clear, [])

  const join = () => {
    setOpen(true)
    setView({ kind: 'requesting' })
    let rank = START_RANK
    const total = 8120
    window.setTimeout(() => {
      timer.current = window.setInterval(() => {
        rank = Math.max(0, rank - Math.round(ADMIT_PER_SEC * 0.5 * (0.6 + Math.random() * 0.8)))
        setStockLeft((s) => Math.max(outcome === 'sold_out' ? 0 : 3, s - Math.round(Math.random() * 18)))
        if (rank === 0) {
          clear()
          setView({ kind: 'admitted' })
          window.setTimeout(() => setView({ kind: outcome }), 900)
          return
        }
        setView({ kind: 'waiting', rank, behind: total - START_RANK + Math.round(Math.random() * 300), etaSec: Math.ceil(rank / ADMIT_PER_SEC), progress: 1 - rank / START_RANK })
      }, 500)
    }, 600)
  }

  const close = () => {
    clear()
    setOpen(false)
    setView({ kind: 'idle' })
  }

  const done = view.kind === 'issued' || view.kind === 'sold_out' || view.kind === 'duplicate'

  return (
    <div className="grid grid-cols-[1fr_300px] gap-6">
      {/* 이벤트 페이지 (사용자가 보는 화면) */}
      <div className="flex justify-center">
        <div className="w-full max-w-md overflow-hidden rounded-2xl border border-slate-800 bg-slate-900">
          <div className="flex h-56 items-center justify-center bg-gradient-to-br from-sky-500 via-indigo-500 to-fuchsia-500">
            <div className="text-center text-white">
              <div className="text-sm font-medium opacity-80">11월 단 하루</div>
              <div className="mt-1 text-5xl font-black tracking-tight">50% OFF</div>
              <div className="mt-2 text-sm opacity-90">전 상품 할인 쿠폰</div>
            </div>
          </div>
          <div className="space-y-4 p-6">
            <div>
              <div className="text-xs text-sky-400">선착순 1,000명</div>
              <h1 className="mt-1 text-xl font-bold">블랙프라이데이 50% 할인 쿠폰</h1>
              <p className="mt-1 text-sm text-slate-400">최대 30,000원 · 1인 1매 · 발급일로부터 7일</p>
            </div>
            <div>
              <div className="mb-1 flex justify-between text-xs">
                <span className="text-slate-400">남은 수량</span>
                <span className="font-semibold tabular-nums text-rose-400">{fmt(stockLeft)}장</span>
              </div>
              <div className="h-2 overflow-hidden rounded-full bg-slate-800">
                <div className="h-full rounded-full bg-rose-500 transition-all" style={{ width: `${(stockLeft / 1000) * 100}%` }} />
              </div>
            </div>
            <button
              onClick={join}
              disabled={open}
              className="w-full rounded-xl bg-sky-500 py-3.5 text-base font-bold text-white transition hover:bg-sky-400 disabled:opacity-50"
            >
              🎟 쿠폰 받기
            </button>
            <p className="text-center text-xs text-slate-500">지금 {fmt(8120)}명이 함께 접속 중</p>
          </div>
        </div>
      </div>

      {/* 오른쪽: 배경 시뮬레이션 상태 + 데모 컨트롤 */}
      <aside className="space-y-4">
        <Card title="배경 시뮬레이션">
          <dl className="space-y-2 text-sm">
            <Row k="전략" v="REDIS_ATOMIC + 대기열" />
            <Row k="가상 사용자" v="10,000명" />
            <Row k="대기 중" v={<span className="text-amber-400">{fmt(8120)}명</span>} />
            <Row k="입장 속도" v={`${ADMIT_PER_SEC}명/초`} />
            <Row k="순번 알림" v="SSE push" />
          </dl>
          <div className="mt-3 flex items-center gap-2 text-xs text-emerald-400">
            <span className="size-2 animate-pulse rounded-full bg-emerald-400" /> 실행 중
          </div>
        </Card>
        <Card title="목업 컨트롤">
          <div className="text-xs text-slate-400">입장 후 결과</div>
          <div className="mt-2 grid grid-cols-3 gap-1 rounded-md bg-slate-950 p-1">
            {(
              [
                ['issued', '성공'],
                ['sold_out', '매진'],
                ['duplicate', '중복'],
              ] as const
            ).map(([k, label]) => (
              <button key={k} onClick={() => setOutcome(k)} className={`rounded px-2 py-1 text-xs ${outcome === k ? 'bg-slate-700 text-white' : 'text-slate-400'}`}>
                {label}
              </button>
            ))}
          </div>
          <p className="mt-3 text-xs leading-relaxed text-slate-500">실제 구현에서는 여기서 결과를 고르지 않고, 서버 응답에 따라 결정됩니다.</p>
        </Card>
      </aside>

      {open && (
        <div className="fixed inset-0 z-30 flex items-center justify-center bg-black/60 backdrop-blur-sm">
          <div className="w-80 overflow-hidden rounded-2xl border border-slate-700 bg-slate-900 shadow-2xl">
            <QueueScreen view={view} onCancel={close} />
            {done && (
              <div className="border-t border-slate-800 p-3">
                <button onClick={close} className="w-full rounded-lg bg-slate-800 py-2 text-sm hover:bg-slate-700">
                  확인
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

function Row({ k, v }: { k: string; v: ReactNode }) {
  return (
    <div className="flex justify-between">
      <dt className="text-slate-400">{k}</dt>
      <dd className="tabular-nums">{v}</dd>
    </div>
  )
}
