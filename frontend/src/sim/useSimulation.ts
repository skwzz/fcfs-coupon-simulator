import { useCallback, useEffect, useRef, useState } from 'react'
import { S, type DoneMsg, type InitMsg, type SimConfig, type TickMsg, type UserDetail, type UsersMsg } from './types'

// 차트에 남길 tick 수 (200ms × 300 = 60초)
const MAX_TICKS = 300

export type Phase = 'idle' | 'starting' | 'running' | 'stopping' | 'done'

export interface RunData {
  phase: Phase
  runId: number | null
  eventId: number | null
  userCount: number
  stock: number
  status: Uint8Array
  rank: Int32Array
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
        }
        bump()
      })
      es.addEventListener('users', (e) => {
        const msg: UsersMsg = JSON.parse((e as MessageEvent).data)
        const r = runRef.current
        for (const [userId, status, rank] of msg.changes) {
          r.status[userId - 1] = S[status]
          r.rank[userId - 1] = rank ?? -1
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
          behavior: { preset: config.behavior },
        }),
      })
      if (!res.ok) throw new Error(await errorMessage(res))
      const { runId, eventId } = await res.json()
      runRef.current = { ...runRef.current, phase: 'running', runId, eventId }
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
    bump()
  }, [config.userCount, config.stock, bump])

  return { run: runRef.current, version, start, stop, reset }
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
