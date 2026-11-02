import { useCallback, useEffect, useRef, useState } from 'react'
import { S, STATUSES, type SimConfig, type Status, type Tick, type TimelineEntry } from './types'

// 백엔드 없이 화면을 확인하기 위한 가짜 시뮬레이션. 200ms마다 SSE tick이 온다고 가정하고 상태를 흉내 낸다.
const TICK_MS = 200

interface Engine {
  config: SimConfig
  status: Uint8Array
  rank: Int32Array
  queue: number[]
  queueHead: number
  stockLeft: number
  issued: number
  t: number
  ticks: Tick[]
  timelines: Map<number, TimelineEntry[]>
  running: boolean
  done: boolean
}

function createEngine(config: SimConfig): Engine {
  return {
    config,
    status: new Uint8Array(config.userCount),
    rank: new Int32Array(config.userCount).fill(-1),
    queue: [],
    queueHead: 0,
    stockLeft: config.stock,
    issued: 0,
    t: 0,
    ticks: [],
    timelines: new Map(),
    running: false,
    done: false,
  }
}

function setStatus(e: Engine, id: number, s: number, rank?: number, note?: string) {
  e.status[id] = s
  let tl = e.timelines.get(id)
  if (!tl) {
    tl = [{ t: 0, status: 'IDLE' }]
    e.timelines.set(id, tl)
  }
  tl.push({ t: e.t, status: STATUSES[s], rank, note })
}

function step(e: Engine) {
  const { config } = e
  e.t += TICK_MS
  const n = config.userCount
  let completed = 0

  // 램프업: 시간에 비례해 출발
  const startedTarget = config.rampUpMs === 0 ? n : Math.min(n, Math.ceil((e.t / config.rampUpMs) * n))
  for (let id = 0; id < startedTarget; id++) {
    if (e.status[id] === S.IDLE) setStatus(e, id, S.REQUESTING)
  }

  // 오버셀 가능 여부 (NAIVE / 다중 인스턴스 SYNCHRONIZED 흉내)
  const unsafe = config.strategy === 'NAIVE'
  const overLimit = unsafe ? Math.round(config.stock * 1.37) : config.stock
  const throughput =
    { NAIVE: 2600, SYNCHRONIZED: 700, DB_PESSIMISTIC: 600, DB_OPTIMISTIC: 450, REDIS_LOCK: 900, REDIS_ATOMIC: 2400, REDIS_KAFKA: 4200 }[
      config.strategy
    ] * (TICK_MS / 1000)

  const tryIssue = (id: number) => {
    completed++
    if (Math.random() < 0.003) return setStatus(e, id, S.ERROR, undefined, 'LOCK_TIMEOUT')
    if (config.requestsPerUser > 1 && Math.random() < 0.08) return setStatus(e, id, S.DUPLICATE)
    if (e.issued < overLimit) {
      e.issued++
      e.stockLeft = Math.max(0, config.stock - e.issued)
      return setStatus(e, id, S.ISSUED, undefined, config.strategy === 'REDIS_KAFKA' ? 'ACCEPTED (DB 반영 대기)' : undefined)
    }
    setStatus(e, id, S.SOLD_OUT)
  }

  if (config.queueEnabled) {
    // 요청 중 → 대기열 진입
    let entered = 0
    for (let id = 0; id < n && entered < 4000; id++) {
      if (e.status[id] === S.REQUESTING && Math.random() < 0.7) {
        e.queue.push(id)
        entered++
        setStatus(e, id, S.WAITING, e.queue.length - e.queueHead)
      }
    }
    // 입장한 사용자 → 발급
    for (let id = 0; id < n; id++) if (e.status[id] === S.ADMITTED && Math.random() < 0.8) tryIssue(id)
    // 초당 admitPerSecond 만큼 입장
    const admit = Math.max(1, Math.round(config.admitPerSecond * (TICK_MS / 1000)))
    for (let i = 0; i < admit && e.queueHead < e.queue.length; i++) {
      const id = e.queue[e.queueHead++]
      setStatus(e, id, S.ADMITTED, undefined, '입장 토큰 발급')
    }
    // 재고 소진 → 대기자 일괄 매진
    if (e.issued >= overLimit) {
      for (let i = e.queueHead; i < e.queue.length; i++) setStatus(e, e.queue[i], S.SOLD_OUT, undefined, '재고 소진 일괄 통지')
      e.queueHead = e.queue.length
    }
    // 순번 갱신 (타임라인은 1초에 한 번만 기록)
    for (let i = e.queueHead; i < e.queue.length; i++) {
      const id = e.queue[i]
      e.rank[id] = i - e.queueHead + 1
      if (e.t % 1000 === 0) e.timelines.get(id)?.push({ t: e.t, status: 'WAITING', rank: e.rank[id] })
    }
  } else {
    let budget = throughput * (0.8 + Math.random() * 0.4)
    for (let id = 0; id < n && budget > 0; id++) {
      if (e.status[id] === S.REQUESTING) {
        tryIssue(id)
        budget--
      }
    }
  }

  const waiting = e.queue.length - e.queueHead
  const base = { NAIVE: 8, SYNCHRONIZED: 40, DB_PESSIMISTIC: 55, DB_OPTIMISTIC: 70, REDIS_LOCK: 35, REDIS_ATOMIC: 9, REDIS_KAFKA: 4 }[
    config.strategy
  ]
  const jitter = () => 0.8 + Math.random() * 0.4
  e.ticks.push({
    t: e.t,
    tps: Math.round((completed * 1000) / TICK_MS),
    p50: Math.round(base * jitter()),
    p95: Math.round(base * 4 * jitter()),
    p99: Math.round(base * 9 * jitter()),
    queueLength: waiting,
  })
  if (e.ticks.length > 150) e.ticks.shift()

  let pending = 0
  for (let id = 0; id < n; id++) if (e.status[id] <= S.ADMITTED) pending++
  if (pending === 0) {
    e.running = false
    e.done = true
  }
}

export function useMockSimulation(config: SimConfig) {
  const engineRef = useRef<Engine>(createEngine(config))
  const [, setVersion] = useState(0)

  const reset = useCallback(() => {
    engineRef.current = createEngine(config)
    setVersion((v) => v + 1)
  }, [config])

  // 설정이 바뀌면 실행 중이 아닐 때만 초기화
  useEffect(() => {
    if (!engineRef.current.running) reset()
  }, [reset])

  useEffect(() => {
    const timer = setInterval(() => {
      const e = engineRef.current
      if (!e.running) return
      step(e)
      setVersion((v) => v + 1)
    }, TICK_MS)
    return () => clearInterval(timer)
  }, [])

  const start = useCallback(() => {
    if (engineRef.current.done) engineRef.current = createEngine(config)
    engineRef.current.running = true
    setVersion((v) => v + 1)
  }, [config])

  const stop = useCallback(() => {
    engineRef.current.running = false
    setVersion((v) => v + 1)
  }, [])

  const e = engineRef.current
  const counts = Object.fromEntries(STATUSES.map((s) => [s, 0])) as Record<Status, number>
  for (let id = 0; id < e.status.length; id++) counts[STATUSES[e.status[id]]]++

  return {
    engine: e,
    counts,
    start,
    stop,
    reset,
  }
}
