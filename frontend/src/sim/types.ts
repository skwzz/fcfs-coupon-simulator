// 가상 사용자 상태. 순서는 그리드 색 배열 인덱스로 쓰므로 바꾸지 않는다 (새 상태는 뒤에 추가)
export const STATUSES = [
  'IDLE',
  'REQUESTING',
  'WAITING',
  'ADMITTED',
  'ISSUED',
  'SOLD_OUT',
  'DUPLICATE',
  'ERROR',
  'ACCEPTED',
  'ABANDONED',
] as const

export type Status = (typeof STATUSES)[number]

export const S = Object.fromEntries(STATUSES.map((s, i) => [s, i])) as { [K in Status]: number }

export const STATUS_META: Record<Status, { label: string; color: string }> = {
  IDLE: { label: '대기 전', color: '#1e293b' },
  REQUESTING: { label: '요청 중', color: '#38bdf8' },
  WAITING: { label: '대기열', color: '#f59e0b' },
  ADMITTED: { label: '입장', color: '#a78bfa' },
  ISSUED: { label: '발급 성공', color: '#22c55e' },
  ACCEPTED: { label: '발급 확정(비동기)', color: '#14b8a6' },
  SOLD_OUT: { label: '매진', color: '#475569' },
  DUPLICATE: { label: '중복', color: '#f472b6' },
  ERROR: { label: '에러', color: '#ef4444' },
  ABANDONED: { label: '이탈', color: '#78716c' },
}

/** 범례와 결과 분포에 보여줄 순서 */
export const DISPLAY_ORDER: Status[] = ['IDLE', 'REQUESTING', 'WAITING', 'ADMITTED', 'ISSUED', 'ACCEPTED', 'SOLD_OUT', 'DUPLICATE', 'ERROR', 'ABANDONED']

/** phase: 아직 구현되지 않은 전략은 선택할 수 없게 하고 어느 단계에서 들어오는지 표시 */
export const STRATEGIES = [
  { value: 'NAIVE', label: 'NAIVE (락 없음)' },
  { value: 'SYNCHRONIZED', label: 'SYNCHRONIZED' },
  { value: 'DB_PESSIMISTIC', label: 'DB 비관적 락' },
  { value: 'DB_OPTIMISTIC', label: 'DB 낙관적 락' },
  { value: 'REDIS_LOCK', label: 'Redis 분산 락', phase: 4 },
  { value: 'REDIS_ATOMIC', label: 'Redis Lua (원자적)', phase: 4 },
  { value: 'REDIS_KAFKA', label: 'Redis + Kafka (비동기)', phase: 6 },
] as const

export type Strategy = (typeof STRATEGIES)[number]['value']

export const BEHAVIORS = [
  { value: 'IDEAL', label: 'IDEAL (1회 요청)' },
  { value: 'REALISTIC', label: 'REALISTIC', phase: 4 },
  { value: 'AGGRESSIVE', label: 'AGGRESSIVE', phase: 4 },
] as const

export type Behavior = (typeof BEHAVIORS)[number]['value']

export interface SimConfig {
  strategy: Strategy
  stock: number
  userCount: number
  rampUpMs: number
  behavior: Behavior
  queueEnabled: boolean
  admitPerSecond: number
  notifyMode: 'SSE' | 'POLLING'
}

// ---- simulator SSE / REST 메시지 (docs/04-api-spec.md 4~5장) ----

export interface Latency {
  p50: number | null
  p95: number | null
  p99: number | null
  max: number | null
}

export interface InitMsg {
  runId: number
  eventId: number
  strategy: Strategy
  stock: number
  userCount: number
  state: RunState
  t: number
}

export interface TickMsg {
  t: number
  stockLeft: number
  counts: Record<Status, number>
  tps: number
  latencyMs: Latency
  queueLength: number
  retries: number
  /** 인스턴스별 누적 응답 수 (X-Served-By) */
  servedBy: Record<string, number>
  /** 인스턴스별 최근 서버 지표 */
  server: Record<string, ServerSample>
}

export interface ServerSample {
  hikariActive: number
  hikariPending: number
  /** 컨테이너에 할당된 CPU 기준 % */
  cpuPercent: number
}

export interface UsersMsg {
  changes: [number, Status, number?][]
}

export interface Verification {
  totalQuantity: number
  issuedInDb: number
  issuedQuantityCounter: number
  overIssued: number
  duplicateIssued: number
  passed: boolean
}

export type RunState = 'RUNNING' | 'DONE' | 'STOPPED' | 'FAILED'

export interface DoneMsg {
  state: RunState
  durationMs: number
  counts: Record<Status, number>
  verification: Verification | null
  latencyMs: Latency
  avgTps: number
  error: string | null
  servedBy: Record<string, number> | null
  serverPeak: Record<string, ServerSample> | null
}

export interface TimelineEntry {
  t: number
  status: Status
  rank?: number
  note?: string | null
}

export interface UserDetail {
  userId: number
  status: Status
  timeline: TimelineEntry[]
}

// ---- 실행 기록 (GET /api/simulations, /compare) ----

export interface RunSummary {
  runId: number
  eventId: number
  strategy: Strategy
  userCount: number
  stock: number
  rampUpMs: number
  behaviorPreset: Behavior
  queueEnabled: boolean
  targetBaseUrl: string
  state: RunState
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
  avgTps: number | null
  latencyMs: Latency
  verification: Verification | null
  counts: Partial<Record<Status, number>>
  servedBy: Record<string, number>
  error: string | null
}

export interface SeriesPoint {
  t: number
  tps: number
  latencyMs: Latency
  queueLength: number
  hikariPending: number | null
}

export interface Comparison {
  summary: RunSummary
  series: SeriesPoint[] | null
}
