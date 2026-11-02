import type { ReactNode } from 'react'

export function Card({ title, right, className = '', children }: { title?: ReactNode; right?: ReactNode; className?: string; children: ReactNode }) {
  return (
    <section className={`rounded-xl border border-slate-800 bg-slate-900/60 ${className}`}>
      {title && (
        <header className="flex items-center justify-between border-b border-slate-800 px-4 py-2.5">
          <h2 className="text-sm font-semibold text-slate-300">{title}</h2>
          {right}
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  )
}

export function Kpi({ label, value, sub, tone = 'default' }: { label: string; value: ReactNode; sub?: ReactNode; tone?: 'default' | 'good' | 'warn' | 'bad' }) {
  const toneClass = { default: 'text-slate-100', good: 'text-emerald-400', warn: 'text-amber-400', bad: 'text-rose-400' }[tone]
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 px-4 py-3">
      <div className="text-xs text-slate-400">{label}</div>
      <div className={`mt-1 text-2xl font-bold tabular-nums ${toneClass}`}>{value}</div>
      {sub && <div className="mt-0.5 text-xs text-slate-500 tabular-nums">{sub}</div>}
    </div>
  )
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs text-slate-400">{label}</span>
      {children}
    </label>
  )
}

export const inputClass =
  'w-full rounded-md border border-slate-700 bg-slate-950 px-2.5 py-1.5 text-sm text-slate-100 outline-none focus:border-sky-500 disabled:opacity-50'

export function Button({
  children,
  onClick,
  variant = 'default',
  disabled,
  className = '',
}: {
  children: ReactNode
  onClick?: () => void
  variant?: 'primary' | 'danger' | 'default'
  disabled?: boolean
  className?: string
}) {
  const v = {
    primary: 'bg-sky-500 text-white hover:bg-sky-400',
    danger: 'bg-rose-500/90 text-white hover:bg-rose-400',
    default: 'border border-slate-700 bg-slate-800 text-slate-200 hover:bg-slate-700',
  }[variant]
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`rounded-md px-3 py-1.5 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-40 ${v} ${className}`}
    >
      {children}
    </button>
  )
}

export function Badge({ children, color }: { children: ReactNode; color: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium" style={{ backgroundColor: `${color}26`, color }}>
      <span className="size-1.5 rounded-full" style={{ backgroundColor: color }} />
      {children}
    </span>
  )
}

export const fmt = (n: number) => n.toLocaleString('ko-KR')
