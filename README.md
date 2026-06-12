# react-native-mqtt-mtls

A React Native MQTT client with mTLS (mutual TLS) support using a Context Provider pattern for shared connection state across your entire app.

## Features

- ✅ **mTLS Support**: Full mutual TLS authentication with client certificates
- ✅ **Context Provider Pattern**: Single MQTT client shared across all screens
- ✅ **Connection Persistence**: Connection survives navigation between screens
- ✅ **Android KeyStore Integration**: Secure private key storage using hardware-backed security
- ✅ **TypeScript**: Full TypeScript support with type definitions
- ✅ **Event-driven**: Subscribe to connection events and messages
- ✅ **QoS Support**: Support for MQTT QoS levels 0, 1, and 2

## Why Context Provider?

Unlike hook-based approaches that can create multiple MQTT client instances, the Context Provider pattern ensures:

1. **Single Client Instance**: One MQTT client for the entire app
2. **No Duplicate Connections**: Prevents "client already exists" errors
3. **Shared State**: All screens see the same connection status
4. **Proper Lifecycle**: Client is cleaned up only when the app closes

## Installation

```bash
npm install react-native-mqtt-mtls
# or
yarn add react-native-mqtt-mtls
```

### Android Setup

1. Add to your `android/app/build.gradle`:

```gradle
dependencies {
    implementation project(':react-native-mqtt-mtls')
}
```

2. Add to your `android/settings.gradle`:

```gradle
include ':react-native-mqtt-mtls'
project(':react-native-mqtt-mtls').projectDir = new File(rootProject.projectDir, '../node_modules/react-native-mqtt-mtls/android')
```

3. Register the package in `MainApplication.java`:

```java
import com.reactnativemqttmtls.MqttPackage;

public class MainApplication extends Application implements ReactApplication {
    @Override
    protected List<ReactPackage> getPackages() {
        return Arrays.<ReactPackage>asList(
            new MainReactPackage(),
            new MqttPackage()  // Add this line
        );
    }
}
```

4. Add required permissions to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

## Usage

### 1. Wrap Your App with MqttProvider

```tsx
import React from 'react';
import { MqttProvider } from 'react-native-mqtt-mtls';
import { NavigationContainer } from '@react-navigation/native';
import AppNavigator from './navigation/AppNavigator';

export default function App() {
  return (
    <MqttProvider>
      <NavigationContainer>
        <AppNavigator />
      </NavigationContainer>
    </MqttProvider>
  );
}
```

### 2. Use MQTT in Any Screen

```tsx
import React, { useEffect, useState } from 'react';
import { View, Text, Button, StyleSheet } from 'react-native';
import { useMqtt } from 'react-native-mqtt-mtls';

const ChatScreen = () => {
  const { isConnected, connect, disconnect, publish, subscribe } = useMqtt();
  const [messages, setMessages] = useState<string[]>([]);

  useEffect(() => {
    // Connect to MQTT broker on component mount
    const connectToMqtt = async () => {
      try {
        await connect({
          broker: 'ssl://mqtt.example.com:8883',
          clientId: 'my-app-client-123',
          certificates: {
            clientCert: '-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----',
            privateKeyAlias: 'my-key-alias', // Key stored in Android KeyStore
            rootCa: '-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----',
          },
          onMessage: (message) => {
            console.log('Received:', message.topic, message.message);
            setMessages((prev) => [...prev, `${message.topic}: ${message.message}`]);
          },
          onConnect: () => {
            console.log('Connected to MQTT broker');
          },
          onConnectionLost: (error) => {
            console.log('Connection lost:', error);
          },
          onReconnect: () => {
            console.log('Reconnected to MQTT broker');
          },
          onError: (error) => {
            console.error('MQTT error:', error);
          },
        });

        // Subscribe to a topic after connecting
        await subscribe('chat/room1', 1);
      } catch (error) {
        console.error('Failed to connect:', error);
      }
    };

    connectToMqtt();

    // Cleanup on unmount
    return () => {
      disconnect();
    };
  }, []);

  const sendMessage = async () => {
    try {
      await publish('chat/room1', 'Hello from React Native!', 1);
    } catch (error) {
      console.error('Failed to publish:', error);
    }
  };

  return (
    <View style={styles.container}>
      <Text>Status: {isConnected ? 'Connected' : 'Disconnected'}</Text>
      <Button title="Send Message" onPress={sendMessage} disabled={!isConnected} />
      {messages.map((msg, index) => (
        <Text key={index}>{msg}</Text>
      ))}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
  },
});

export default ChatScreen;
```

## API Reference

### `MqttProvider`

Wrap your app with this provider to enable MQTT functionality.

```tsx
<MqttProvider>
  <App />
</MqttProvider>
```

### `useMqtt()`

Hook to access MQTT context. Must be used within `MqttProvider`.

#### Returns

```typescript
interface MqttContextType {
  isConnected: boolean;
  error: string | null;
  connect: (config: MqttConfig) => Promise<void>;
  disconnect: () => Promise<void>;
  subscribe: (topic: string, qos?: number) => Promise<void>;
  unsubscribe: (topic: string) => Promise<void>;
  publish: (topic: string, message: string, qos?: number) => Promise<void>;
}
```

### `connect(config: MqttConfig)`

Connects to the MQTT broker with mTLS.

```typescript
interface MqttConfig {
  broker: string; // e.g., 'ssl://mqtt.example.com:8883'
  clientId: string;
  certificates: {
    clientCert: string; // PEM format
    privateKeyAlias: string; // Alias in Android KeyStore
    rootCa: string; // PEM format
  };
  onMessage?: (message: MqttMessage) => void;
  onConnect?: () => void;
  onConnectionLost?: (error: string) => void;
  onReconnect?: () => void;
  onError?: (error: string) => void;
}
```

### `disconnect()`

Disconnects from the MQTT broker.

### `subscribe(topic: string, qos?: number)`

Subscribes to a topic. QoS defaults to 1 if not specified.

### `unsubscribe(topic: string)`

Unsubscribes from a topic.

### `publish(topic: string, message: string, qos?: number)`

Publishes a message to a topic. QoS defaults to 1 if not specified.

## Certificate Management

### Storing Private Key in Android KeyStore

Before using this library, you need to import your private key into the Android KeyStore:

```java
// Example: Import private key into Android KeyStore
KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
keyStore.load(null);

// Load your private key and certificate
PrivateKey privateKey = ...; // Load from PEM or PKCS12
Certificate[] certChain = ...; // Your certificate chain

// Store in Android KeyStore
keyStore.setKeyEntry(
    "my-key-alias",  // This is the alias you'll use in the app
    privateKey,
    null,
    certChain
);
```

Then use `"my-key-alias"` as the `privateKeyAlias` in your MQTT config.

## Certificate Format

Certificates should be in PEM format:

```
-----BEGIN CERTIFICATE-----
MIIDXTCCAkWgAwIBAgIJAKL0UG+mRqS...
...
-----END CERTIFICATE-----
```

## Troubleshooting

### "MqttModule: A client already exists"

This error occurs when trying to create multiple MQTT clients. The Context Provider pattern prevents this issue by ensuring only one client exists for the entire app.

### Connection Issues

1. Verify your broker URL and port
2. Ensure certificates are in correct PEM format
3. Check that private key is properly stored in Android KeyStore
4. Verify network connectivity and firewall rules

### Certificate Errors

1. Ensure certificate chain is complete (client cert → intermediate CA → root CA)
2. Verify certificates haven't expired
3. Check that private key matches the client certificate

## Example App

See the `example/` directory for a complete working example.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

MIT

## Author

Created for secure MQTT communication in React Native applications.
