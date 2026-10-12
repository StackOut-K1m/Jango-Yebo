import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  // .codex-*/ 는 발표자료 산출물이라 코드 작업 대상이 아니다(CLAUDE.md).
  // outputs/ 는 데이터 생성 산출물이다(gitignore 대상). 소스가 아니므로 검사하지 않는다.
  { ignores: ['**/dist', '**/build', 'backend', 'infra', 'docs', '.codex-*', 'outputs'] },
  js.configs.recommended,
  tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    },
  },
  // scripts/ 는 개발용 Node 스크립트다. 브라우저가 아니라 Node 전역을 쓴다.
  // FormData·Blob 은 Node 18+ 전역이다 — 파일 업로드(multipart)에 쓴다.
  {
    files: ['scripts/**/*.mjs', 'scripts/**/*.js'],
    languageOptions: {
      globals: {
        console: 'readonly',
        process: 'readonly',
        fetch: 'readonly',
        FormData: 'readonly',
        Blob: 'readonly',
      },
    },
  },
  // tools/ 는 개발용 로컬 도구다. 서버는 Node, public/ 은 브라우저에서 돈다.
  {
    files: ['tools/**/*.mjs'],
    languageOptions: {
      globals: {
        console: 'readonly',
        process: 'readonly',
        fetch: 'readonly',
        URL: 'readonly',
        Buffer: 'readonly',
        AbortSignal: 'readonly',
        structuredClone: 'readonly',
        FormData: 'readonly',
        Blob: 'readonly',
        TextEncoder: 'readonly',
      },
    },
  },
  {
    files: ['tools/**/public/**/*.js', 'tools/**/public/**/*.mjs'],
    languageOptions: {
      globals: {
        document: 'readonly',
        window: 'readonly',
        fetch: 'readonly',
        navigator: 'readonly',
        sessionStorage: 'readonly',
        localStorage: 'readonly',
        Blob: 'readonly',
        URL: 'readonly',
        FileReader: 'readonly',
        TextDecoder: 'readonly',
        AbortController: 'readonly',
        setTimeout: 'readonly',
        clearTimeout: 'readonly',
        structuredClone: 'readonly',
      },
    },
  },
  // Metro·Babel 설정은 CommonJS로 써야 한다(도구가 그렇게 읽는다).
  {
    files: ['**/metro.config.js', '**/babel.config.js'],
    languageOptions: {
      sourceType: 'commonjs',
      globals: { require: 'readonly', module: 'writable', __dirname: 'readonly' },
    },
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },
  prettier,
);
