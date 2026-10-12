import { formatManwonRange, formatMoney } from '@jangoyebo/core';
import { StyleSheet, Text, View } from 'react-native';

export default function Index() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>잔고예보</Text>
      <Text>공유 코어 연결: {formatMoney(1340000)}</Text>
      <Text>예측 범위 표기: {formatManwonRange(1200000, 1400000)}</Text>
      <Text>값 없음 표기: {formatMoney(undefined)}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 8 },
  title: { fontSize: 24, fontWeight: '700' },
});
