import { useCallback, useEffect, useRef, useState } from 'react'
import { S, type DoneMsg, type InitMsg, type RunSummary, type SimConfig, type TickMsg, type UserDetail, type UsersMsg } from './types'

// 차트에 남길 tick 수 (200ms × 300 = 60초)
const MAX_TICKS = 300

// 새로고침해도 마지막 실행에 다시 붙기 위해 runId만 브라우저에 남긴다
const RUN_KEY = 'fcfs.currentRunId'

export function rememberedRunId(): number | null {
  try {
    const v = localStorage.getItem(RUN_KEY)
    return v ? Number(v) : null
  } catch {
    return null
  }
}

function rememberRun(runId: number | null) {
  try {
    if (runId == null) localStorage.removeItem(RUN_KEY)
    else localStorage.setItem(RUN_KEY, String(runId))
  } catch {
    // 저장소를 못 써도 실행에는 지장 없음
  }
}

export type Phase = 'idle' | 'starting' | 'running' | 'stopping' | 'done'

export interface RunData {
  phase: Phase
  runId: number | null
  eventId: number | null
  userCount: number
  stock: number
  status: Uint8Array
  rank: Int32Array
  /** 사용자별 요청 수 (연타·재시도 포함, 255에서 멈춤) */
  attempts: Uint8Array
  ticks: TickMsg[]
  done: DoneMsg | null
  error: string | null
}

function emptyRun(userCount: number, stock: number): RunData {
  return {
    phase: 'idle',
    runId: null,
    eventId: null,
    userCount,
    stock,
    status: new Uint8Array(userCount),
    rank: new Int32Array(userCount).fill(-1),
    attempts: new Uint8Array(userCount),
    ticks: [],
    done: null,
    error: null,
  }
}

async function errorMessage(res: Response) {
  try {
    const body = await res.json()
    return body.detail ?? body.title ?? `HTTP ${res.status}`
  } catch {
    return `HTTP ${res.status}`
  }
}

/**
 * simulator와 연결. 시작은 REST, 진행 상황은 SSE로 받는다.
 * 사용자 상태는 typed array에 직접 쓰고 version만 올려서 다시 그린다 (사용자 1만 명을 state 객체로 들고 있지 않기 위해).
 */
export function useSimulation(config: SimConfig) {
  const runRef = useRef<RunData>(emptyRun(config.userCount, config.stock))
  const esRef = useRef<EventSource | null>(null)
  const [version, setVersion] = useState(0)
  const bump = useCallback(() => setVersion((v) => v + 1), [])

  // 실행 전에는 설정을 바꾸면 그리드 크기도 바로 바뀌게
  useEffect(() => {
    if (runRef.current.phase === 'idle') {
      runRef.current = emptyRun(config.userCount, config.stock)
      bump()
    }
  }, [config.userCount, config.stock, bump])

  useEffect(() => () => esRef.current?.close(), [])

  const connect = useCallback(
    (runId: number) => {
      esRef.current?.close()
      const es = new EventSource(`/api/simulations/${runId}/stream`)
      esRef.current = es

      es.addEventListener('init', (e) => {
        const msg: InitMsg = JSON.parse((e as MessageEvent).data)
        const r = runRef.current
        r.eventId = msg.eventId
        r.stock = msg.stock
        if (r.userCount !== msg.userCount) {
          r.userCount = msg.userCount
          r.status = new Uint8Array(msg.userCount)
          r.rank = new Int32Array(msg.userCount).fill(-1)
          r.attempts = new Uint8Array(msg.userCount)
        }
        bump()
      })
      es.addEventListener('users', (e) => {
        const msg: UsersMsg = JSON.parse((e as MessageEvent).data)
        const r = runRef.current
        for (const [userId, status, rank, attempts] of msg.changes) {
          r.status[userId - 1] = S[status]
          r.rank[userId - 1] = rank ?? -1
          r.attempts[userId - 1] = Math.min(255, attempts ?? 0)
        }
        // tick과 같은 주기로 오므로 다시 그리기는 tick에서 한 번만
      })
      es.addEventListener('tick', (e) => {
        const msg: TickMsg = JSON.parse((e as MessageEvent).data)
        const r = runRef.current
        r.ticks = r.ticks.length >= MAX_TICKS ? [...r.ticks.slice(1), msg] : [...r.ticks, msg]
        bump()
      })
      es.addEventListener('done', (e) => {
        const msg: DoneMsg = JSON.parse((e as MessageEvent).data)
        es.close()
        runRef.current = { ...runRef.current, phase: 'done', done: msg }
        bump()
      })
      // 연결이 끊기면 EventSource가 알아서 재연결하고, 서버가 init + 전체 상태를 다시 보낸다
    },
    [bump],
  )

  const start = useCallback(async () => {
    esRef.current?.close()
    runRef.current = { ...emptyRun(config.userCount, config.stock), phase: 'starting' }
    bump()
    try {
      const res = await fetch('/api/simulations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          stock: config.stock,
          strategy: config.strategy,
          userCount: config.userCount,
          rampUpMs: config.rampUpMs,
          queueEnabled: config.queueEnabled,
          admitPerSecond: config.queueEnabled ? config.admitPerSecond : undefined,
          queueNotifyMode: config.queueEnabled ? config.notifyMode : undefined,
          behavior: {
            preset: config.behavior,
            thinkTimeMs: [config.behaviorDetail.thinkMinMs, config.behaviorDetail.thinkMaxMs],
            multiClick: { ratio: config.behaviorDetail.multiClickRatio, clicks: config.behaviorDetail.clicks },
            retry: { maxRetries: config.behaviorDetail.maxRetries, backoffMs: config.behaviorDetail.backoffMs, jitter: config.behaviorDetail.jitter },
            timeoutMs: config.behaviorDetail.timeoutMs,
            refreshWhileWaiting: { ratePerMinute: config.behaviorDetail.refreshPerMinute },
            abandon: { medianWaitSec: config.behaviorDetail.abandonMedianSec },
            polling: { intervalMs: config.behaviorDetail.pollIntervalMs, jitterMs: config.behaviorDetail.pollJitterMs },
          },
        }),
      })
      if (!res.ok) throw new Error(await errorMessage(res))
      const { runId, eventId } = await res.json()
      runRef.current = { ...runRef.current, phase: 'running', runId, eventId }
      rememberRun(runId)
      bump()
      connect(runId)
    } catch (e) {
      runRef.current = { ...runRef.current, phase: 'idle', error: e instanceof Error ? e.message : String(e) }
      bump()
    }
  }, [config, bump, connect])

  const stop = useCallback(async () => {
    const { runId } = runRef.current
    if (runId == null) return
    runRef.current = { ...runRef.current, phase: 'stopping' }
    bump()
    await fetch(`/api/simulations/${runId}/stop`, { method: 'POST' })
  }, [bump])

  const reset = useCallback(() => {
    esRef.current?.close()
    runRef.current = emptyRun(config.userCount, config.stock)
    rememberRun(null)
    bump()
  }, [config.userCount, config.stock, bump])

  /**
   * 이미 시작된 실행에 다시 붙는다 (새로고침 후 등). 서버가 init과 현재 사용자 상태 전체를 먼저 보내 주므로 그리드는 그대로 복원된다.
   * 끝난 실행이면 done까지 받고 결과 화면이 된다. 차트는 다시 붙은 시점부터 그려진다.
   */
  const attach = useCallback(
    (summary: RunSummary) => {
      runRef.current = { ...emptyRun(summary.userCount, summary.stock), phase: 'running', runId: summary.runId, eventId: summary.eventId }
      bump()
      connect(summary.runId)
    },
    [bump, connect],
  )

  return { run: runRef.current, version, start, stop, reset, attach }
}

/** 선택한 사용자의 타임라인. 그 사용자의 상태가 바뀔 때마다 다시 가져온다. */
export function useUserDetail(runId: number | null, index: number | null, statusCode: number | undefined) {
  const [detail, setDetail] = useState<UserDetail | null>(null)
  useEffect(() => {
    if (runId == null || index == null) {
      setDetail(null)
      return
    }
    let cancelled = false
    fetch(`/api/simulations/${runId}/users/${index + 1}`)
      .then((res) => (res.ok ? res.json() : null))
      .then((d) => !cancelled && setDetail(d))
      .catch(() => !cancelled && setDetail(null))
    return () => {
      cancelled = true
    }
  }, [runId, index, statusCode])
  return detail
}
