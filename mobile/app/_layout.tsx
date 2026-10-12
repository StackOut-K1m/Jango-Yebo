import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { configureAppCore } from '@/lib/core';

// 화면이 그려지기 전에 끝나야 한다. 첫 렌더에서 API를 부르는 화면이 있을 수 있다.
configureAppCore();

const queryClient = new QueryClient();

export default function RootLayout() {
  return (
    <QueryClientProvider client={queryClient}>
      <StatusBar style="dark" />
      <Stack />
    </QueryClientProvider>
  );
}
