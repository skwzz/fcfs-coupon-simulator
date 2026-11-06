import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { QueueScreen, type QueueView } from '../../components/QueueScreen'
import { Button, Card, fmt } from '../../components/ui'
import type { RunSummary } from '../../sim/types'

/** coupon-api 대기열 응답 (docs/04-api-spec.md 3장) */
interface QueueStatus {
  status: 'WAITING' | 'ADMITTED' | 'SOLD_OUT' | 'EXPIRED' | 'NOT_IN_QUEUE'
  rank?: number
  totalWaiting?: number
  estimatedWaitSec?: number
  token?: string
  expiresInSec?: number
}

interface EventInfo {
  id: number
  totalQuantity: number
  strategy: string
  queueEnabled: boolean
  admitPerSecond: number
}

interface LogEntry {
  at: number
  text: string
}

// 가상 사용자(1부터 N)와 겹치지 않는 id. 1인 1매라 같은 id로는 한 번만 받을 수 있다
const newUserId = () => 9_000_000 + Math.floor(Math.random() * 1_000_000)

/**
 * 직접 참여: 돌고 있는 시뮬레이션(대기열 사용)의 이벤트에 브라우저 사용자 본인이 실제 API로 줄을 선다.
 * 가상 사용자들과 같은 Redis 대기열에 들어가므로, 그들 뒤에서 순번이 줄어드는 것을 보고 입장하면 진짜 경쟁 결과를 받는다.
 */
export function LiveJoinPage({ active }: { active: boolean }) {
  const [run, setRun] = useState<RunSummary | null>(null)
  const [event, setEvent] = useState<EventInfo | null>(null)
  const [stockLeft, setStockLeft] = useState<number | null>(null)
  const [userId, setUserId] = useState(newUserId)
  const [view, setView] = useState<QueueView>({ kind: 'idle' })
  const [open, setOpen] = useState(false)
  const [lastStatus, setLastStatus] = useState<QueueStatus | null>(null)
  const [log, setLog] = useState<LogEntry[]>([])
  const esRef = useRef<EventSource | null>(null)
  const joinedAt = useRef(0)
  const firstRank = useRef(0)

  const addLog = useCallback((text: string) => {
    const t = joinedAt.current ? Date.now() - joinedAt.current : 0
    setLog((l) => [...l, { at: t, text }])
  }, [])

  // 탭을 보고 있을 때만, 대기열을 켠 실행 중인 시뮬레이션을 찾는다
  useEffect(() => {
    if (!active) return
    let cancelled = false
    const load = async () => {
      try {
        const runs: RunSummary[] = await fetch('/api/simulations?limit=20').then((r) => r.json())
        const target = runs.find((r) => r.queueEnabled && r.state === 'RUNNING') ?? null
        if (cancelled) return
        setRun((prev) => (prev?.runId === target?.runId && prev?.state === target?.state ? prev : target))
      } catch {
        // simulator가 꺼져 있으면 다음 주기에 다시
      }
    }
    load()
    const timer = window.setInterval(load, 3000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [active])

  // 이벤트 정보와 남은 재고 (DB 기준)
  useEffect(() => {
    if (!active || !run) return
    let cancelled = false
    fetch(`/api/events/${run.eventId}`)
      .then((r) => (r.ok ? r.json() : null))
      .then((e) => !cancelled && setEvent(e))
      .catch(() => {})
    const loadStock = () =>
      fetch(`/api/events/${run.eventId}/verification`)
        .then((r) => (r.ok ? r.json() : null))
        .then((v) => !cancelled && v && setStockLeft(Math.max(0, v.totalQuantity - v.issuedInDb)))
        .catch(() => {})
    loadStock()
    const timer = window.setInterval(loadStock, 2000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [active, run])

  const closeStream = () => {
    esRef.current?.close()
    esRef.current = null
  }
  useEffect(() => closeStream, [])

  const issue = useCallback(
    async (eventId: number, token: string) => {
      setView({ kind: 'admitted' })
      const t0 = performance.now()
      try {
        const res = await fetch(`/api/events/${eventId}/issue`, {
          method: 'POST',
          headers: { 'X-User-Id': String(userId), 'X-Request-Id': crypto.randomUUID(), 'X-Queue-Token': token },
        })
        const body = await res.json().catch(() => null)
        const ms = Math.round(performance.now() - t0)
        const result: string = body?.result ?? `HTTP_${res.status}`
        addLog(`발급 응답 ${result}${body?.reason ? ` (${body.reason})` : ''} · ${ms}ms`)
        if (result === 'ISSUED') setView({ kind: 'issued' })
        else if (result === 'SOLD_OUT') setView({ kind: 'sold_out' })
        else if (result === 'DUPLICATE') setView({ kind: 'duplicate' })
        else setView({ kind: 'error', reason: body?.reason ?? result })
      } catch (e) {
        addLog(`발급 요청 실패: ${e instanceof Error ? e.message : String(e)}`)
        setView({ kind: 'error', reason: '서버에 연결할 수 없습니다' })
      }
    },
    [userId, addLog],
  )

  const onStatus = useCallback(
    (eventId: number, s: QueueStatus) => {
      setLastStatus(s)
      switch (s.status) {
        case 'WAITING': {
          const rank = s.rank ?? 0
          const total = s.totalWaiting ?? rank
          const start = Math.max(firstRank.current, 1)
          setView({ kind: 'waiting', rank: Math.max(0, rank - 1), behind: Math.max(0, total - rank), etaSec: s.estimatedWaitSec ?? 0, progress: start > 1 ? 1 - (rank - 1) / (start - 1) : 1 })
          return
        }
        case 'ADMITTED':
          closeStream()
          addLog(`입장 · 대기 ${((Date.now() - joinedAt.current) / 1000).toFixed(1)}초 · 토큰 ${s.expiresInSec}초 유효`)
          void issue(eventId, s.token!)
          return
        case 'SOLD_OUT':
          closeStream()
          addLog('대기 중 매진 통지')
          setView({ kind: 'sold_out' })
          return
        default:
          closeStream()
          addLog(s.status === 'EXPIRED' ? '입장 토큰 만료' : '대기열에 없음')
          setView({ kind: 'error', reason: s.status === 'EXPIRED' ? '입장 시간이 지났어요. 다시 줄을 서 주세요' : '대기열에서 빠졌어요' })
      }
    },
    [addLog, issue],
  )

  const join = async () => {
    if (!run) return
    const eventId = run.eventId
    setOpen(true)
    setLog([])
    joinedAt.current = Date.now()
    firstRank.current = 0
    setView({ kind: 'requesting' })
    try {
      const res = await fetch(`/api/events/${eventId}/queue`, { method: 'POST', headers: { 'X-User-Id': String(userId) } })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const s: QueueStatus = await res.json()
      if (s.status === 'WAITING') {
        firstRank.current = s.rank ?? 0
        addLog(`줄 섬 · 순번 ${fmt(s.rank ?? 0)} / 대기 ${fmt(s.totalWaiting ?? 0)}명`)
      }
      onStatus(eventId, s)
      if (s.status !== 'WAITING') return
      // 순번은 서버가 SSE로 보내 준다 (EventSource는 헤더를 못 붙여서 userId를 쿼리로)
      const es = new EventSource(`/api/events/${eventId}/queue/me/stream?userId=${userId}`)
      esRef.current = es
      es.onmessage = (e) => onStatus(eventId, JSON.parse(e.data))
      es.onerror = () => {
        // 서버가 정상적으로 닫으면(입장·매진) 이미 closeStream 했다. 여기 오면 연결이 끊긴 것이고 EventSource가 다시 붙는다
        if (esRef.current === es && es.readyState === EventSource.CLOSED) {
          addLog('순번 연결이 끊겼습니다')
          setView({ kind: 'error', reason: '순번 연결이 끊겼어요' })
        }
      }
    } catch (e) {
      addLog(`줄 서기 실패: ${e instanceof Error ? e.message : String(e)}`)
      setView({ kind: 'error', reason: '줄을 서지 못했어요' })
    }
  }

  const cancel = () => {
    closeStream()
    if (run && view.kind === 'waiting') {
      void fetch(`/api/events/${run.eventId}/queue/me`, { method: 'DELETE', headers: { 'X-User-Id': String(userId) } })
    }
    setOpen(false)
    setView({ kind: 'idle' })
  }

  const finished = view.kind === 'issued' || view.kind === 'sold_out' || view.kind === 'duplicate' || view.kind === 'error'
  const total = event?.totalQuantity ?? run?.stock ?? 0
  const left = stockLeft ?? total

  return (
    <div className="grid grid-cols-[1fr_320px] gap-6">
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
              <div className="text-xs text-sky-400">선착순 {fmt(total)}명</div>
              <h1 className="mt-1 text-xl font-bold">블랙프라이데이 50% 할인 쿠폰</h1>
              <p className="mt-1 text-sm text-slate-400">최대 30,000원 · 1인 1매 · 발급일로부터 7일</p>
            </div>
            <div>
              <div className="mb-1 flex justify-between text-xs">
                <span className="text-slate-400">남은 수량</span>
                <span className="font-semibold tabular-nums text-rose-400">{run ? `${fmt(left)}장` : '-'}</span>
              </div>
              <div className="h-2 overflow-hidden rounded-full bg-slate-800">
                <div className="h-full rounded-full bg-rose-500 transition-all" style={{ width: `${total > 0 ? (left / total) * 100 : 0}%` }} />
              </div>
            </div>
            <button
              onClick={join}
              disabled={open || !run}
              className="w-full rounded-xl bg-sky-500 py-3.5 text-base font-bold text-white transition hover:bg-sky-400 disabled:opacity-50"
            >
              🎟 쿠폰 받기
            </button>
            <p className="text-center text-xs text-slate-500">
              {run ? (lastStatus?.totalWaiting != null ? `지금 ${fmt(lastStatus.totalWaiting)}명이 기다리는 중` : `가상 사용자 ${fmt(run.userCount)}명과 함께 참여`) : '대기열을 켠 시뮬레이션이 실행 중일 때 참여할 수 있습니다'}
            </p>
          </div>
        </div>
      </div>

      {/* 오른쪽: 배경 시뮬레이션 상태 + 내 기록 */}
      <aside className="space-y-4">
        <Card title="배경 시뮬레이션">
          {run ? (
            <>
              <dl className="space-y-2 text-sm">
                <Row k="실행" v={`run #${run.runId} · event #${run.eventId}`} />
                <Row k="전략" v={run.strategy} />
                <Row k="가상 사용자" v={`${fmt(run.userCount)}명 · ${run.behaviorPreset}`} />
                <Row k="입장 속도" v={event ? `${fmt(event.admitPerSecond)}명/초` : '-'} />
                <Row k="대기 중" v={<span className="text-amber-400">{lastStatus?.totalWaiting != null ? `${fmt(lastStatus.totalWaiting)}명` : '참여하면 표시'}</span>} />
              </dl>
              <div className="mt-3 flex items-center gap-2 text-xs text-emerald-400">
                <span className="size-2 animate-pulse rounded-full bg-emerald-400" /> 실행 중
              </div>
            </>
          ) : (
            <p className="text-xs leading-relaxed text-slate-400">
              실행 중인 대기열 시뮬레이션이 없습니다. 시뮬레이션 탭에서 <b className="text-slate-200">대기열 사용</b>을 켜고 시작하면, 가상 사용자들과 같은 줄에 설 수 있습니다.
              <br />
              <span className="text-slate-500">예: 사용자 10,000명 · 재고 3,000 · 초당 입장 100명</span>
            </p>
          )}
        </Card>

        <Card title="나" right={<span className="text-xs tabular-nums text-slate-500">user #{userId}</span>}>
          {log.length === 0 ? (
            <p className="text-xs text-slate-500">쿠폰 받기를 누르면 내 요청 기록이 여기 남습니다.</p>
          ) : (
            <ol className="space-y-1.5 text-xs">
              {log.map((e, i) => (
                <li key={i} className="flex gap-2">
                  <span className="w-12 shrink-0 tabular-nums text-slate-500">{(e.at / 1000).toFixed(1)}s</span>
                  <span className="text-slate-300">{e.text}</span>
                </li>
              ))}
            </ol>
          )}
          <Button className="mt-3 w-full" disabled={open} onClick={() => setUserId(newUserId())}>
            새 사용자로 바꾸기
          </Button>
          <p className="mt-2 text-[11px] leading-4 text-slate-500">1인 1매라 같은 사용자로 다시 받으면 중복입니다.</p>
        </Card>
      </aside>

      {open && (
        <div className="fixed inset-0 z-30 flex items-center justify-center bg-black/60 backdrop-blur-sm">
          <div className="w-80 overflow-hidden rounded-2xl border border-slate-700 bg-slate-900 shadow-2xl">
            <QueueScreen view={view} onCancel={cancel} />
            {finished && (
              <div className="border-t border-slate-800 p-3">
                <button onClick={cancel} className="w-full rounded-lg bg-slate-800 py-2 text-sm hover:bg-slate-700">
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
    <div className="flex justify-between gap-3">
      <dt className="text-slate-400">{k}</dt>
      <dd className="text-right tabular-nums">{v}</dd>
    </div>
  )
}
