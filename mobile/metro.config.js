// npm workspaces 대응. 기본값은 mobile/ 안만 보기 때문에
// packages/core 를 그대로 두면 Metro가 못 찾는다.
const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '..');

const config = getDefaultConfig(projectRoot);

// 저장소 전체를 감시해야 packages/core 를 고쳤을 때 앱이 갱신된다.
config.watchFolders = [workspaceRoot];

// 의존성은 mobile/node_modules 를 먼저 보고, 없으면 루트로 올라간다(호이스팅된 패키지).
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(workspaceRoot, 'node_modules'),
];

// 상위 디렉터리를 무한정 거슬러 올라가지 않게 막는다. 같은 패키지가 두 번 로드되는 사고를 예방한다.
config.resolver.disableHierarchicalLookup = true;

module.exports = config;
