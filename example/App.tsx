import React from 'react';
import { SafeAreaView, StyleSheet } from 'react-native';
import { MqttProvider } from 'react-native-mqtt-mtls';
import MqttScreen from './MqttScreen';

export default function App() {
  return (
    <SafeAreaView style={styles.container}>
      <MqttProvider>
        <MqttScreen />
      </MqttProvider>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
  },
});
