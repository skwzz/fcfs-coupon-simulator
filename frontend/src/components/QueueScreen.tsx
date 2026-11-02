import type { ReactNode } from 'react'
import { fmt } from './ui'

export type QueueView =
  | { kind: 'idle' }
  | { kind: 'requesting' }
  | { kind: 'waiting'; rank: number; behind: number; etaSec: number; progress: number }
  | { kind: 'admitted' }
  | { kind: 'issued'; note?: string }
  | { kind: 'sold_out' }
  | { kind: 'duplicate' }
  | { kind: 'error'; reason?: string }

// 사용자가 실제로 보게 될 화면. "직접 참여" 모달과 미니 폰 뷰에서 같이 쓴다.
export function QueueScreen({ view, compact = false, onCancel }: { view: QueueView; compact?: boolean; onCancel?: () => void }) {
  const big = compact ? 'text-2xl' : 'text-4xl'
  const pad = compact ? 'px-3 py-4' : 'px-8 py-8'

  switch (view.kind) {
    case 'idle':
      return <Center pad={pad} icon="🎟" title="쿠폰 오픈 대기" sub="버튼을 눌러 참여하세요" compact={compact} />
    case 'requesting':
      return <Center pad={pad} icon={<Spinner />} title="요청 중..." sub="잠시만 기다려 주세요" compact={compact} />
    case 'waiting':
      return (
        <div className={`${pad} text-center`}>
          <div className={compact ? 'text-xl' : 'text-3xl'}>⏳</div>
          <div className={`mt-2 font-semibold text-slate-100 ${compact ? 'text-xs' : 'text-lg'}`}>접속 대기 중입니다</div>
          <div className="mt-3 text-xs text-slate-400">내 앞에</div>
          <div className={`${big} font-extrabold tabular-nums text-amber-400`}>
            {fmt(view.rank)}
            <span className={compact ? 'text-sm' : 'text-xl'}>명</span>
          </div>
          <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-slate-800">
            <div className="h-full rounded-full bg-amber-400 transition-all duration-300" style={{ width: `${Math.round(view.progress * 100)}%` }} />
          </div>
          <div className="mt-1.5 flex justify-between text-[11px] text-slate-500 tabular-nums">
            <span>예상 {view.etaSec}초</span>
            <span>뒤에 {fmt(view.behind)}명</span>
          </div>
          {!compact && (
            <>
              <p className="mt-5 text-xs leading-relaxed text-slate-500">
                새로고침하거나 창을 닫으면
                <br />
                대기 순번이 뒤로 밀립니다.
              </p>
              {onCancel && (
                <button onClick={onCancel} className="mt-4 text-xs text-slate-400 underline underline-offset-2 hover:text-slate-200">
                  대기 취소
                </button>
              )}
            </>
          )}
        </div>
      )
    case 'admitted':
      return <Center pad={pad} icon="🚪" title="입장했습니다" sub="쿠폰을 발급하는 중..." compact={compact} accent="text-violet-400" />
    case 'issued':
      return <Center pad={pad} icon="🎉" title="쿠폰 발급 완료!" sub={view.note ?? '마이페이지에서 확인하세요'} compact={compact} accent="text-emerald-400" />
    case 'sold_out':
      return <Center pad={pad} icon="😢" title="선착순 마감" sub="준비된 쿠폰이 모두 소진되었어요" compact={compact} accent="text-slate-300" />
    case 'duplicate':
      return <Center pad={pad} icon="✋" title="이미 받은 쿠폰" sub="1인 1매만 발급됩니다" compact={compact} accent="text-pink-400" />
    case 'error':
      return <Center pad={pad} icon="⚠️" title="일시적인 오류" sub={view.reason ?? '잠시 후 다시 시도해 주세요'} compact={compact} accent="text-rose-400" />
  }
}

function Center({
  pad,
  icon,
  title,
  sub,
  compact,
  accent = 'text-slate-100',
}: {
  pad: string
  icon: ReactNode
  title: string
  sub: string
  compact: boolean
  accent?: string
}) {
  return (
    <div className={`${pad} flex flex-col items-center text-center`}>
      <div className={compact ? 'text-2xl' : 'text-5xl'}>{icon}</div>
      <div className={`mt-2 font-bold ${accent} ${compact ? 'text-sm' : 'text-xl'}`}>{title}</div>
      <div className={`mt-1 text-slate-400 ${compact ? 'text-[11px]' : 'text-sm'}`}>{sub}</div>
    </div>
  )
}

function Spinner() {
  return <div className="mx-auto size-8 animate-spin rounded-full border-2 border-slate-700 border-t-sky-400" />
}
