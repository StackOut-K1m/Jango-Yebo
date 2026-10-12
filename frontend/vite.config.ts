import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');

  return {
    // Tailwind 는 새 화면에만 쓴다. preflight 는 styles/tailwind.css 에서 일부러 뺐다.
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        // tsconfig.json의 paths와 항상 같이 고쳐야 한다.
        // 한쪽만 고치면 에디터와 런타임 중 하나가 깨진다.
        //
        // 공유 코어는 별칭을 두지 않는다. workspaces 심볼릭 링크로
        // '@jangoyebo/core' 가 그냥 해석되며, 앱(Metro)도 같은 이름을 쓴다.
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      proxy: {
        '/api': {
          target: env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080',
          changeOrigin: true,
          secure: false,
          // 배포 서버로 프록시를 돌리면 Refresh Token 쿠키의 도메인이
          // portfolio.example.invalid 로 와서 브라우저가 버린다. localhost 로 고쳐 준다.
          cookieDomainRewrite: 'localhost',
        },
      },
    },
  };
});
