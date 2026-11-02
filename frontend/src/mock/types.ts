export const STATUSES = [
  'IDLE',
  'REQUESTING',
  'WAITING',
  'ADMITTED',
  'ISSUED',
  'SOLD_OUT',
  'DUPLICATE',
  'ERROR',
] as const

export type Status = (typeof STATUSES)[number]

export const S = {
  IDLE: 0,
  REQUESTING: 1,
  WAITING: 2,
  ADMITTED: 3,
  ISSUED: 4,
  SOLD_OUT: 5,
  DUPLICATE: 6,
  ERROR: 7,
} as const

export const STATUS_META: Record<Status, { label: string; color: string }> = {
  IDLE: { label: '대기 전', color: '#1e293b' },
  REQUESTING: { label: '요청 중', color: '#38bdf8' },
  WAITING: { label: '대기열', color: '#f59e0b' },
  ADMITTED: { label: '입장', color: '#a78bfa' },
  ISSUED: { label: '발급 성공', color: '#22c55e' },
  SOLD_OUT: { label: '매진', color: '#475569' },
  DUPLICATE: { label: '중복', color: '#f472b6' },
  ERROR: { label: '에러', color: '#ef4444' },
}

export const STRATEGIES = [
  { value: 'NAIVE', label: 'NAIVE (락 없음)' },
  { value: 'SYNCHRONIZED', label: 'SYNCHRONIZED' },
  { value: 'DB_PESSIMISTIC', label: 'DB 비관적 락' },
  { value: 'DB_OPTIMISTIC', label: 'DB 낙관적 락' },
  { value: 'REDIS_LOCK', label: 'Redis 분산 락' },
  { value: 'REDIS_ATOMIC', label: 'Redis Lua (원자적)' },
  { value: 'REDIS_KAFKA', label: 'Redis + Kafka (비동기)' },
] as const

export type Strategy = (typeof STRATEGIES)[number]['value']

export interface SimConfig {
  strategy: Strategy
  stock: number
  userCount: number
  requestsPerUser: number
  rampUpMs: number
  queueEnabled: boolean
  admitPerSecond: number
  notifyMode: 'SSE' | 'POLLING'
}

export interface Tick {
  t: number
  tps: number
  p50: number
  p95: number
  p99: number
  queueLength: number
}

export interface TimelineEntry {
  t: number
  status: Status
  rank?: number
  note?: string
}
