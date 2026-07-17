import { ActivityIndicator, StyleSheet, View } from 'react-native';

// _layout.tsx guard redirects to the correct role dashboard after auth check.
// This screen is only shown momentarily during that redirect.
export default function IndexScreen() {
  return (
    <View style={styles.loader}>
      <ActivityIndicator size="large" color="#0D9488" />
    </View>
  );
}

const styles = StyleSheet.create({
  loader: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#F9FAFB' },
});
