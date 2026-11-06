import { useState } from 'react'
import { Field, inputClass } from '../../components/ui'
import { BEHAVIORS, type Behavior, type BehaviorDetail } from '../../sim/types'

/** 사용자 행동 모델: 프리셋을 고르고, 필요하면 세부 값을 바꾼다. docs/01-requirements.md 3.5 */
export function BehaviorSettings({
  preset,
  detail,
  disabled,
  queueEnabled,
  onPreset,
  onDetail,
}: {
  preset: Behavior
  detail: BehaviorDetail
  disabled: boolean
  /** 대기열을 켰을 때만 대기 중 행동(새로고침, 이탈, 폴링)을 보여준다 */
  queueEnabled: boolean
  onPreset: (p: Behavior) => void
  onDetail: (d: BehaviorDetail) => void
}) {
  const [open, setOpen] = useState(false)
  const set = <K extends keyof BehaviorDetail>(k: K, v: BehaviorDetail[K]) => onDetail({ ...detail, [k]: v })
  const num = (v: string, min: number, max: number) => Math.min(max, Math.max(min, Number(v) || 0))

  return (
    <div className="rounded-lg border border-slate-800 p-3">
      <Field label="사용자 행동 모델">
        <select className={inputClass} value={preset} disabled={disabled} onChange={(e) => onPreset(e.target.value as Behavior)}>
          {BEHAVIORS.map((b) => (
            <option key={b.value} value={b.value}>
              {b.label}
            </option>
          ))}
        </select>
      </Field>
      <div className="mt-2 text-[11px] leading-4 text-slate-500">{summary(detail, queueEnabled)}</div>
      <button className="mt-2 text-xs text-sky-400 hover:text-sky-300" onClick={() => setOpen((o) => !o)}>
        {open ? '▾ 세부 설정 닫기' : '▸ 세부 설정'}
      </button>
      {open && (
        <div className="mt-2 grid grid-cols-2 gap-2">
          <Field label="생각 시간 최소 (ms)">
            <input type="number" className={inputClass} disabled={disabled} value={detail.thinkMinMs} onChange={(e) => set('thinkMinMs', num(e.target.value, 0, detail.thinkMaxMs))} />
          </Field>
          <Field label="생각 시간 최대 (ms)">
            <input type="number" className={inputClass} disabled={disabled} value={detail.thinkMaxMs} onChange={(e) => set('thinkMaxMs', num(e.target.value, detail.thinkMinMs, 60000))} />
          </Field>
          <Field label="연타 비율 (%)">
            <input type="number" className={inputClass} disabled={disabled} value={Math.round(detail.multiClickRatio * 100)} onChange={(e) => set('multiClickRatio', num(e.target.value, 0, 100) / 100)} />
          </Field>
          <Field label="연타 클릭 수">
            <input type="number" className={inputClass} disabled={disabled} value={detail.clicks} onChange={(e) => set('clicks', num(e.target.value, 1, 10))} />
          </Field>
          <Field label="재시도 횟수">
            <input type="number" className={inputClass} disabled={disabled} value={detail.maxRetries} onChange={(e) => set('maxRetries', num(e.target.value, 0, 10))} />
          </Field>
          <Field label="백오프 (ms)">
            <input type="number" className={inputClass} disabled={disabled} value={detail.backoffMs} onChange={(e) => set('backoffMs', num(e.target.value, 0, 10000))} />
          </Field>
          <Field label="타임아웃 (ms)">
            <input type="number" className={inputClass} disabled={disabled} value={detail.timeoutMs} onChange={(e) => set('timeoutMs', num(e.target.value, 100, 60000))} />
          </Field>
          <label className="mt-5 flex items-center gap-2 text-xs text-slate-300">
            <input type="checkbox" className="size-4 accent-sky-500" disabled={disabled} checked={detail.jitter} onChange={(e) => set('jitter', e.target.checked)} />
            지터 (무작위 대기)
          </label>
          {queueEnabled && (
            <>
              <div className="col-span-2 mt-1 border-t border-slate-800 pt-2 text-[11px] text-slate-500">대기 중 행동</div>
              <Field label="새로고침 (분당)">
                <input type="number" step="0.1" className={inputClass} disabled={disabled} value={detail.refreshPerMinute} onChange={(e) => set('refreshPerMinute', Math.min(60, Math.max(0, Number(e.target.value) || 0)))} />
              </Field>
              <Field label="이탈 중앙값 (초, 0=없음)">
                <input type="number" className={inputClass} disabled={disabled} value={detail.abandonMedianSec} onChange={(e) => set('abandonMedianSec', num(e.target.value, 0, 3600))} />
              </Field>
              <Field label="폴링 간격 (ms)">
                <input type="number" className={inputClass} disabled={disabled} value={detail.pollIntervalMs} onChange={(e) => set('pollIntervalMs', num(e.target.value, 100, 60000))} />
              </Field>
              <Field label="폴링 흔들림 (±ms)">
                <input type="number" className={inputClass} disabled={disabled} value={detail.pollJitterMs} onChange={(e) => set('pollJitterMs', num(e.target.value, 0, detail.pollIntervalMs - 1))} />
              </Field>
            </>
          )}
        </div>
      )}
    </div>
  )
}

function summary(d: BehaviorDetail, queueEnabled: boolean) {
  const parts = [
    d.thinkMaxMs > 0 ? `생각 ${d.thinkMinMs}~${d.thinkMaxMs}ms` : '즉시 클릭',
    d.multiClickRatio > 0 && d.clicks > 1 ? `${Math.round(d.multiClickRatio * 100)}%가 ${d.clicks}번 연타` : '연타 없음',
    d.maxRetries > 0 ? `에러 시 ${d.maxRetries}회 재시도${d.backoffMs > 0 ? ` (백오프 ${d.backoffMs}ms${d.jitter ? '+지터' : ''})` : ' (바로)'}` : '재시도 없음',
    `${d.timeoutMs / 1000}초 후 포기`,
  ]
  if (queueEnabled) {
    parts.push(d.refreshPerMinute > 0 ? `대기 중 분당 ${d.refreshPerMinute}회 새로고침` : '새로고침 없음')
    parts.push(d.abandonMedianSec > 0 ? `${d.abandonMedianSec}초 대기 시 절반 이탈` : '끝까지 대기')
  }
  return parts.join(' · ')
}
