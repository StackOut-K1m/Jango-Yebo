# 잔고예보 모바일 앱

Expo(React Native) 기반 **Android 전용** 앱. iOS는 이번 프로젝트 범위 밖이다.

웹(`frontend/`)과 API 레이어·타입·포맷터를 `@gagehereum/core`로 공유한다. UI는 공유하지 않는다 — CSS·DOM·ECharts·`<table>`을 쓸 수 없다.

## 개발 — Expo Go (기본)

```bash
npm run start -w mobile
```

에뮬레이터나 실제 폰의 Expo Go 앱으로 접속한다. **Gradle·JDK·네이티브 빌드가 필요 없다.**

지금 쓰는 모듈(`expo-router`, `expo-secure-store`, `react-native-screens`, `react-native-safe-area-context`, `react-native-gesture-handler`)은 전부 Expo Go에 포함돼 있다. 평소 개발은 이 방식으로 충분하다.

## 네이티브 빌드 — 필요할 때만

```bash
npm run android -w mobile
```

`mobile/android/`를 생성하고 Gradle로 APK를 빌드해 설치한다. 첫 실행은 15분 안팎.

**언제 필요한가**

- Expo Go에 없는 네이티브 모듈을 추가할 때 (예: 앱 차트 라이브러리)
- 로컬에서 APK를 만들어야 할 때 (배포용은 EAS Build 권장 — 클라우드 Linux라 아래 제약이 없다)

### Windows에서 걸리는 것 두 가지

**1. `JAVA_HOME` 미설정**

```
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

Android Studio 번들 JDK를 쓰면 된다. 환경 변수에 추가:

```
JAVA_HOME = C:\Program Files\Android\Android Studio\jbr
```

**2. 경로 260자 제한**

```
ninja: error: Filename longer than 260 characters
```

React Native가 C++를 컴파일할 때 소스 경로를 오브젝트 파일명에 통째로 박는다. 그 결과 경로가 370자쯤 된다.

**저장소를 짧은 경로로 옮겨도 해결되지 않는다.** 관리자 PowerShell에서 긴 경로를 허용해야 한다:

```powershell
New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" -Name "LongPathsEnabled" -Value 1 -PropertyType DWORD -Force
```

적용 후 재부팅. Windows 10 1607 이상에서 지원한다.

macOS·Linux에는 이 제한이 없다.

## 에뮬레이터

Android Studio → Device Manager에서 실행한다. Android Studio로 **프로젝트를 열 필요는 없다** — 에뮬레이터만 띄우고 나머지는 터미널에서 한다.

RAM 2GB짜리 AVD는 Metro까지 붙으면 답답하다. 4GB 권장.

`mobile/android/`는 자동 생성물이라 gitignore 대상이다. 네이티브 설정을 직접 봐야 할 때만 그 폴더를 Android Studio로 연다.

## 환경 변수

`.env.development`의 `EXPO_PUBLIC_API_BASE_URL`.

앱에는 웹 같은 개발 서버 프록시가 없어 백엔드 주소를 직접 쓴다. **Android 에뮬레이터에서 호스트 PC의 `localhost`는 `10.0.2.2`다.**

로컬 백엔드를 띄우지 않거나 실기기로 붙을 때는 `.env.development.local`을 만들어 배포 서버를 가리킨다:

```
EXPO_PUBLIC_API_BASE_URL=https://portfolio.example.invalid/api/v1
```

`.env*.local`은 gitignore 대상이라 팀원 설정끼리 부딪히지 않는다.

## 로그인 유지

Access Token은 30분이면 만료된다. 앱은 그때 로그인 화면으로 돌아가지 않고 **Refresh Token으로 조용히 갱신**한다 — 공유 코어의 `refreshMode: 'body'`가 401을 받아 갱신하고 원요청을 다시 보낸다(공통규약 2.1).

- 웹은 Refresh Token을 `HttpOnly` 쿠키로 받지만 **앱에는 쿠키 저장소가 없다.** 그래서 인증 요청에 `X-Client-Type: app`을 붙여 본문으로 주고받는다.
- Refresh Token은 `expo-secure-store`에 넣는다(`lib/tokenStore.ts`). Android Keystore로 암호화되므로 `AsyncStorage`와 달리 평문으로 남지 않는다. Access Token은 메모리에만 둔다.
- **앱을 껐다 켜도 로그인이 유지된다.** Access Token이 없어 첫 호출이 401이고, 코어가 그것을 받아 갱신한 뒤 재시도한다.
- Refresh Token은 14일짜리다. 14일 동안 앱을 안 열면 다시 로그인한다.
- 주입은 `lib/core.ts`의 `configureAppCore()`가 하고 `app/_layout.tsx`가 첫 렌더 전에 부른다.

## workspaces 주의

`metro.config.js`가 npm workspaces를 처리한다. Metro는 기본적으로 `mobile/` 안만 보기 때문에, 이 설정이 없으면 `packages/core`를 찾지 못한다.

- `watchFolders` — 저장소 전체를 감시. `packages/core`를 고치면 앱이 갱신된다
- `nodeModulesPaths` — `mobile/node_modules` → 루트 순으로 탐색
- `disableHierarchicalLookup` — 같은 패키지가 두 번 로드되는 것을 막는다

패키지를 설치할 때는 버전을 SDK에 맞추기 위해 `npx expo install`을 쓴다(`npm install`이 아니라).
