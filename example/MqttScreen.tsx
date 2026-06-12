import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  Button,
  TextInput,
  ScrollView,
  StyleSheet,
  Alert,
} from 'react-native';
import { useMqtt } from 'react-native-mqtt-mtls';

const MqttScreen = () => {
  const { isConnected, error, connect, disconnect, publish, subscribe } = useMqtt();
  const [messages, setMessages] = useState<string[]>([]);
  const [messageText, setMessageText] = useState('');
  const [topic, setTopic] = useState('test/topic');

  useEffect(() => {
    // Auto-connect on component mount
    handleConnect();

    // Cleanup on unmount
    return () => {
      if (isConnected) {
        disconnect();
      }
    };
  }, []);

  const handleConnect = async () => {
    try {
      await connect({
        broker: 'ssl://mqtt.example.com:8883',
        clientId: `mqtt-client-${Date.now()}`,
        sniHostname: "mqtt.example.com",
        certificates: {
          // Replace with your actual certificates
          clientCert: `-----BEGIN CERTIFICATE-----
          YOUR_CLIENT_CERTIFICATE_HERE
          -----END CERTIFICATE-----`,
          privateKeyAlias: 'my-mqtt-key',
          rootCa: `-----BEGIN CERTIFICATE-----
          YOUR_ROOT_CA_CERTIFICATE_HERE
          -----END CERTIFICATE-----`,
        },
        onMessage: (message) => {
          const text = `[${message.topic}] ${message.message}`;
          console.log('Received:', text);
          setMessages((prev) => [text, ...prev]);
        },
        onConnect: () => {
          console.log('Connected successfully');
          Alert.alert('Success', 'Connected to MQTT broker');
        },
        onConnectionLost: (err) => {
          console.log('Connection lost:', err);
          Alert.alert('Disconnected', err);
        },
        onReconnect: () => {
          console.log('Reconnected');
        },
        onError: (err) => {
          console.error('MQTT error:', err);
          Alert.alert('Error', err);
        },
      });

      // Subscribe to default topic after connecting
      await subscribe(topic, 1);
    } catch (err: any) {
      console.error('Connection failed:', err);
      Alert.alert('Connection Failed', err.message);
    }
  };

  const handleDisconnect = async () => {
    try {
      await disconnect();
      Alert.alert('Success', 'Disconnected from MQTT broker');
    } catch (err: any) {
      Alert.alert('Error', err.message);
    }
  };

  const handleSubscribe = async () => {
    if (!topic.trim()) {
      Alert.alert('Error', 'Please enter a topic');
      return;
    }

    try {
      await subscribe(topic, 1);
      Alert.alert('Success', `Subscribed to ${topic}`);
    } catch (err: any) {
      Alert.alert('Error', err.message);
    }
  };

  const handlePublish = async () => {
    if (!topic.trim() || !messageText.trim()) {
      Alert.alert('Error', 'Please enter both topic and message');
      return;
    }

    try {
      await publish(topic, messageText, 1);
      setMessageText('');
      Alert.alert('Success', 'Message published');
    } catch (err: any) {
      Alert.alert('Error', err.message);
    }
  };

  return (
    <ScrollView style={styles.container}>
      <View style={styles.section}>
        <Text style={styles.title}>MQTT Connection</Text>
        <View style={styles.statusRow}>
          <Text style={styles.label}>Status:</Text>
          <Text
            style={[
              styles.status,
              isConnected ? styles.connected : styles.disconnected,
            ]}
          >
            {isConnected ? 'Connected' : 'Disconnected'}
          </Text>
        </View>
        {error && <Text style={styles.error}>{error}</Text>}
        <View style={styles.buttonRow}>
          <Button
            title="Connect"
            onPress={handleConnect}
            disabled={isConnected}
          />
          <Button
            title="Disconnect"
            onPress={handleDisconnect}
            disabled={!isConnected}
          />
        </View>
      </View>

      <View style={styles.section}>
        <Text style={styles.title}>Subscribe to Topic</Text>
        <TextInput
          style={styles.input}
          placeholder="Topic (e.g., test/topic)"
          value={topic}
          onChangeText={setTopic}
        />
        <Button
          title="Subscribe"
          onPress={handleSubscribe}
          disabled={!isConnected}
        />
      </View>

      <View style={styles.section}>
        <Text style={styles.title}>Publish Message</Text>
        <TextInput
          style={styles.input}
          placeholder="Topic"
          value={topic}
          onChangeText={setTopic}
        />
        <TextInput
          style={[styles.input, styles.messageInput]}
          placeholder="Message"
          value={messageText}
          onChangeText={setMessageText}
          multiline
        />
        <Button
          title="Publish"
          onPress={handlePublish}
          disabled={!isConnected}
        />
      </View>

      <View style={styles.section}>
        <Text style={styles.title}>Received Messages</Text>
        {messages.length === 0 ? (
          <Text style={styles.noMessages}>No messages yet</Text>
        ) : (
          messages.map((msg, index) => (
            <Text key={index} style={styles.message}>
              {msg}
            </Text>
          ))
        )}
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
  },
  section: {
    marginBottom: 30,
  },
  title: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 10,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 10,
  },
  label: {
    fontSize: 16,
    marginRight: 10,
  },
  status: {
    fontSize: 16,
    fontWeight: 'bold',
  },
  connected: {
    color: 'green',
  },
  disconnected: {
    color: 'red',
  },
  error: {
    color: 'red',
    marginBottom: 10,
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
  },
  input: {
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 5,
    padding: 10,
    marginBottom: 10,
  },
  messageInput: {
    minHeight: 80,
    textAlignVertical: 'top',
  },
  noMessages: {
    fontStyle: 'italic',
    color: '#666',
  },
  message: {
    padding: 10,
    backgroundColor: '#f0f0f0',
    borderRadius: 5,
    marginBottom: 5,
  },
});

export default MqttScreen;
