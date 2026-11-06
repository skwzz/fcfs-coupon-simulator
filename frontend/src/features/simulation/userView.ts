import type { QueueView } from '../../components/QueueScreen'
import { S } from '../../sim/types'

// 가상 사용자의 현재 상태를 "그 사용자가 보는 화면"으로 변환
export function toQueueView(status: number, rank: number, totalWaiting: number, admitPerSecond: number): QueueView {
  switch (status) {
    case S.IDLE:
      return { kind: 'idle' }
    case S.REQUESTING:
      return { kind: 'requesting' }
    case S.WAITING:
      // rank는 내 순번(1부터). 화면에는 "내 앞에 rank - 1명"
      return {
        kind: 'waiting',
        rank: Math.max(0, rank - 1),
        behind: Math.max(0, totalWaiting - rank),
        etaSec: Math.ceil(rank / admitPerSecond),
        progress: totalWaiting > 0 ? 1 - rank / totalWaiting : 0,
      }
    case S.ADMITTED:
      return { kind: 'admitted' }
    case S.ISSUED:
      return { kind: 'issued' }
    case S.ACCEPTED:
      return { kind: 'issued', note: 'DB 반영 대기 중' }
    case S.SOLD_OUT:
      return { kind: 'sold_out' }
    case S.DUPLICATE:
      return { kind: 'duplicate' }
    case S.ABANDONED:
      return { kind: 'error', reason: '대기 중 이탈' }
    default:
      return { kind: 'error' }
  }
}
