import type { QueueView } from '../../components/QueueScreen'
import { S } from '../../mock/types'

// 가상 사용자의 현재 상태를 "그 사용자가 보는 화면"으로 변환
export function toQueueView(status: number, rank: number, totalWaiting: number, admitPerSecond: number): QueueView {
  switch (status) {
    case S.IDLE:
      return { kind: 'idle' }
    case S.REQUESTING:
      return { kind: 'requesting' }
    case S.WAITING:
      return {
        kind: 'waiting',
        rank,
        behind: Math.max(0, totalWaiting - rank),
        etaSec: Math.ceil(rank / admitPerSecond),
        progress: totalWaiting > 0 ? 1 - rank / totalWaiting : 0,
      }
    case S.ADMITTED:
      return { kind: 'admitted' }
    case S.ISSUED:
      return { kind: 'issued' }
    case S.SOLD_OUT:
      return { kind: 'sold_out' }
    case S.DUPLICATE:
      return { kind: 'duplicate' }
    default:
      return { kind: 'error', reason: 'LOCK_TIMEOUT' }
  }
}
