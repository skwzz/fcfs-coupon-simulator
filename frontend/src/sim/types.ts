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
  { value: 'REDIS_LOCK', label: 'Redis 분산 락' },
  { value: 'REDIS_ATOMIC', label: 'Redis Lua (원자적)' },
  { value: 'REDIS_KAFKA', label: 'Redis + Kafka (비동기)', phase: 6 },
] as const

export type Strategy = (typeof STRATEGIES)[number]['value']

export const BEHAVIORS = [
  { value: 'IDEAL', label: 'IDEAL (1회 요청)' },
  { value: 'REALISTIC', label: 'REALISTIC (일반 사용자)' },
  { value: 'AGGRESSIVE', label: 'AGGRESSIVE (매크로·조급)' },
] as const

export type Behavior = (typeof BEHAVIORS)[number]['value']

/** 행동 모델 세부 값. 백엔드 BehaviorModel과 같은 의미 */
export interface BehaviorDetail {
  thinkMinMs: number
  thinkMaxMs: number
  /** 연타하는 사용자 비율 0~1 */
  multiClickRatio: number
  clicks: number
  maxRetries: number
  backoffMs: number
  jitter: boolean
  /** 클라이언트가 응답을 기다리는 최대 시간 */
  timeoutMs: number
  /** 대기 중 새로고침 (분당 평균). 새로고침하면 맨 뒤로 다시 선다 */
  refreshPerMinute: number
  /** 이 시간만큼 기다리면 절반이 떠난다. 0이면 이탈 없음 */
  abandonMedianSec: number
  /** 폴링 모드에서 순번 확인 간격과 흔들림 (±) */
  pollIntervalMs: number
  pollJitterMs: number
}

/** 프리셋 기본값 (simulator BehaviorModel.of와 같게 유지) */
export const BEHAVIOR_PRESETS: Record<Behavior, BehaviorDetail> = {
  IDEAL: { thinkMinMs: 0, thinkMaxMs: 0, multiClickRatio: 0, clicks: 1, maxRetries: 0, backoffMs: 0, jitter: false, timeoutMs: 30000, refreshPerMinute: 0, abandonMedianSec: 0, pollIntervalMs: 1000, pollJitterMs: 0 },
  REALISTIC: { thinkMinMs: 0, thinkMaxMs: 300, multiClickRatio: 0.2, clicks: 3, maxRetries: 2, backoffMs: 500, jitter: true, timeoutMs: 10000, refreshPerMinute: 0.1, abandonMedianSec: 60, pollIntervalMs: 1000, pollJitterMs: 300 },
  AGGRESSIVE: { thinkMinMs: 0, thinkMaxMs: 0, multiClickRatio: 0.5, clicks: 3, maxRetries: 3, backoffMs: 0, jitter: false, timeoutMs: 3000, refreshPerMinute: 0.5, abandonMedianSec: 0, pollIntervalMs: 1000, pollJitterMs: 300 },
}

/** DB 카운터(issued_quantity)로 재고를 세는 전략. Redis 전략은 카운터 대신 Redis 재고를 쓴다 */
export const COUNTER_STRATEGIES: Strategy[] = ['NAIVE', 'SYNCHRONIZED', 'DB_PESSIMISTIC', 'DB_OPTIMISTIC', 'REDIS_LOCK']

export interface SimConfig {
  strategy: Strategy
  stock: number
  userCount: number
  rampUpMs: number
  behavior: Behavior
  behaviorDetail: BehaviorDetail
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
  /** 누적 재시도 수 */
  retries: number
  /** 누적 요청 수 (연타·재시도 포함) */
  requests: number
  /** 응답 결과별 누적 수 */
  responses: Record<string, number>
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
  /** 사용 중인 힙 MB (Phase 5부터, 이전 기록은 없음) */
  heapUsedMb?: number | null
}

export interface UsersMsg {
  /** [userId, status, rank, attempts] */
  changes: [number, Status, number | null, number?][]
}

export interface Verification {
  totalQuantity: number
  issuedInDb: number
  issuedQuantityCounter: number
  overIssued: number
  duplicateIssued: number
  passed: boolean
  /** Redis 남은 재고 (Phase 4 이전 기록은 null) */
  redisStock: number | null
  redisIssuedUsers: number | null
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
  requests: number | null
  retries: number | null
  responses: Record<string, number> | null
  /** 에러 사유별 응답 수 */
  errors: Record<string, number> | null
  /** 대기열 지표 (대기열을 쓴 실행만) */
  queue?: QueueStats | null
}

export interface QueueStats {
  /** 줄 서기 요청 수 (새로고침으로 다시 선 것 포함) */
  enters: number
  /** 폴링 요청 수 (SSE 모드는 0) */
  polls: number
  refreshes: number
  abandoned: number
  admitted: number
  /** 줄 서기·순번 확인 실패 수 */
  errors: number
  /** 줄 선 뒤 입장하기까지 */
  waitMs: Latency
  admitPerSecond: number
  notifyMode: 'SSE' | 'POLLING'
}

export interface TimelineEntry {
  t: number
  status: Status
  rank?: number | null
  /** 그 사용자의 몇 번째 요청인지 (연타·재시도 포함) */
  attempt?: number | null
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
  requests: number | null
  retries: number | null
  error: string | null
}

export interface SeriesPoint {
  t: number
  tps: number
  latencyMs: Latency
  queueLength: number
  hikariPending: number | null
  retries: number | null
}

export interface Comparison {
  summary: RunSummary
  series: SeriesPoint[] | null
}
