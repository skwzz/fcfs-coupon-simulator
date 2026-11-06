import { useEffect, useState } from 'react'

type Health = Record<string, boolean | null>

// coupon-api와 simulator의 Actuator health를 5초마다 확인. MySQL·Redis는 coupon-api health의 구성요소로 본다
async function check(): Promise<Health> {
  const get = async (url: string) => {
    try {
      const res = await fetch(url)
      return (await res.json()) as { status: string; components?: Record<string, { status: string }> }
    } catch {
      return null
    }
  }
  const [api, sim] = await Promise.all([get('/health/api'), get('/health/simulator')])
  const up = (s?: string) => (s == null ? null : s === 'UP')
  return {
    simulator: up(sim?.status) ?? false,
    'coupon-api': up(api?.status) ?? false,
    MySQL: api ? up(api.components?.db?.status) : null,
    Redis: api ? up(api.components?.redis?.status) : null,
  }
}

export function HealthDots() {
  const [health, setHealth] = useState<Health>({})
  useEffect(() => {
    let alive = true
    const run = () => check().then((h) => alive && setHealth(h))
    run()
    const timer = setInterval(run, 5000)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [])
  return (
    <>
      {Object.entries(health).map(([name, ok]) => (
        <span key={name} className="flex items-center gap-1.5" title={ok == null ? '확인 불가' : ok ? 'UP' : 'DOWN'}>
          <span className={`size-1.5 rounded-full ${ok == null ? 'bg-slate-600' : ok ? 'bg-emerald-400' : 'bg-rose-500'}`} />
          {name}
        </span>
      ))}
    </>
  )
}
