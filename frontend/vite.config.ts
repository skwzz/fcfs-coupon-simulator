import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// 개발 서버가 API를 대신 전달해서 브라우저는 같은 origin만 본다 (CORS 설정 불필요).
// 순서가 중요하다: /api/simulations를 /api보다 먼저 둔다.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api/simulations': 'http://localhost:8090',
      '/api': 'http://localhost:8080',
      '/health/api': { target: 'http://localhost:8080', rewrite: () => '/actuator/health' },
      '/health/simulator': { target: 'http://localhost:8090', rewrite: () => '/actuator/health' },
    },
  },
})
