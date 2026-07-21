import React, {
  useState,
  useEffect,
  useCallback,
  useRef,
  ReactNode,
} from "react";
import {
  NativeEventEmitter,
  EmitterSubscription,
  Platform,
} from "react-native";
import { MqttContext } from "./MqttContext";
import MqttModule from "./MqttModule";
import type { MqttConfig, MqttMessage } from "./types";

interface MqttProviderProps {
  children: ReactNode;
}

export const MqttProvider = ({ children }: MqttProviderProps) => {
  const [isConnected, setIsConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const configRef = useRef<MqttConfig | null>(null);
  const eventEmitterRef = useRef<NativeEventEmitter | null>(null);

  useEffect(() => {
    // Cleanup any stale MQTT connections from previous app sessions
    if (typeof MqttModule.cleanup === "function") {
      console.log("MqttProvider: Performing initial cleanup...");
      MqttModule.cleanup(
        (success) => {
          console.log("MqttProvider: Initial cleanup successful:", success);
        },
        (error) => {
          console.log("MqttProvider: Cleanup error (non-critical):", error);
        },
      );
    } else {
      console.log(
        "MqttProvider: cleanup method not available, using disconnect fallback...",
      );
      MqttModule.disconnect(
        (success) => {
          console.log("MqttProvider: Disconnect fallback successful:", success);
        },
        (error) => {
          console.log(
            "MqttProvider: Disconnect fallback error (non-critical):",
            error,
          );
        },
      );
    }

    // Create event emitter for MQTT events
    eventEmitterRef.current = new NativeEventEmitter(MqttModule as any);

    const subscriptions: EmitterSubscription[] = [];

    // Subscribe to MQTT events
    subscriptions.push(
      eventEmitterRef.current.addListener(
        "MqttConnected",
        (message: string) => {
          console.log("MQTT Connected:", message);
          setIsConnected(true);
          setError(null);
          if (configRef.current?.onConnect) {
            configRef.current.onConnect();
          }
        },
      ),
    );

    subscriptions.push(
      eventEmitterRef.current.addListener(
        "MqttDisconnected",
        (message: string) => {
          console.log("MQTT Disconnected:", message);
          setIsConnected(false);
          if (configRef.current?.onConnectionLost) {
            configRef.current.onConnectionLost(message);
          }
        },
      ),
    );

    subscriptions.push(
      eventEmitterRef.current.addListener("MqttMessage", (data: any) => {
        try {
          const parsedData = typeof data === "string" ? JSON.parse(data) : data;

          // Decode Base64 binary messages to Uint8Array
          if (parsedData.isBinary && parsedData.message) {
            try {
              const binaryString = atob(parsedData.message);
              const bytes = new Uint8Array(binaryString.length);
              for (let i = 0; i < binaryString.length; i++) {
                bytes[i] = binaryString.charCodeAt(i);
              }

              // Return Uint8Array directly - matches MqttManager behavior
              parsedData.message = bytes;
              console.log(
                "📨 Message received:",
                parsedData.topic,
                "(",
                bytes.length,
                "bytes)",
              );
            } catch (decodeErr) {
              console.error("Failed to decode Base64 message:", decodeErr);
              // Keep original message if decode fails
            }
          } else {
            // Plain text message
            console.log("📨 Message received:", parsedData.topic, "(text)");
          }

          if (configRef.current?.onMessage) {
            configRef.current.onMessage(parsedData);
          }
        } catch (err) {
          console.error("Failed to parse MQTT message:", err);
        }
      }),
    );

    subscriptions.push(
      eventEmitterRef.current.addListener(
        "MqttDeliveryComplete",
        (message: string) => {
          console.log("MQTT Delivery Complete:", message);
        },
      ),
    );

    // Cleanup on unmount
    return () => {
      console.log("MqttProvider: Unmounting, cleaning up subscriptions...");
      subscriptions.forEach((sub) => sub.remove());

      // Use cleanup if available, otherwise fall back to disconnect
      if (typeof MqttModule.cleanup === "function") {
        MqttModule.cleanup(
          () => {},
          () => {},
        );
      } else {
        console.log("MqttProvider: Using disconnect as cleanup fallback");
        MqttModule.disconnect(
          () => {},
          () => {},
        );
      }
    };
  }, []);

  const connect = useCallback(async (config: MqttConfig): Promise<void> => {
    try {
      configRef.current = config;
      return new Promise((resolve, reject) => {
        // On Android, keystore parameters are passed to control PKCS12 file loading
        // On iOS, keys are loaded from Keychain using only the alias - keystore params are ignored
        if (Platform.OS === "android") {
          // Android signature includes keystore params for PKCS12 file control
          MqttModule.connect(
            config.broker,
            config.clientId,
            config.certificates,
            config.isAdminUser ?? false ? null : config.sniHostname ?? null,
            config.brokerIp ?? null,
            config.isAdminUser ?? false
              ? null
              : config.brokerCommonName ?? null,
            config.isAdminUser ?? false,
            config.keystorePath ?? null,
            config.keystorePassword ?? null,
            config.keystoreFormat ?? null,
            (success: string) => {
              console.log("Connect success:", success);
              resolve();
            },
            (error: string) => {
              console.error("Connect error:", error);
              setError(error);
              if (config.onError) {
                config.onError(error);
              }
              reject(error);
            },
          );
        } else {
          // iOS signature - no keystore params
          MqttModule.connect(
            config.broker,
            config.clientId,
            config.certificates,
            config.isAdminUser ?? false ? null : config.sniHostname ?? null,
            config.brokerIp ?? null,
            config.isAdminUser ?? false
              ? null
              : config.brokerCommonName ?? null,
            config.isAdminUser ?? false,
            (success: string) => {
              console.log("Connect success:", success);
              resolve();
            },
            (error: string) => {
              console.error("Connect error:", error);
              setError(error);
              if (config.onError) {
                config.onError(error);
              }
              reject(error);
            },
          );
        }
      });
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      setError(errorMessage);
      throw err;
    }
  }, []);

  const disconnect = useCallback(async (): Promise<void> => {
    return new Promise((resolve, reject) => {
      MqttModule.disconnect(
        (success) => {
          console.log("Disconnect success:", success);
          setIsConnected(false);
          configRef.current = null;
          resolve();
        },
        (error) => {
          console.error("Disconnect error:", error);
          reject(error);
        },
      );
    });
  }, []);

  const subscribe = useCallback(
    async (topic: string, qos = 1): Promise<void> => {
      return new Promise((resolve, reject) => {
        MqttModule.subscribe(
          topic,
          qos,
          (success: string) => {
            console.log("Subscribe success:", success);
            resolve();
          },
          (error: string) => {
            console.error("Subscribe error:", error);
            reject(error);
          },
        );
      });
    },
    [],
  );

  const unsubscribe = useCallback(async (topic: string): Promise<void> => {
    return new Promise((resolve, reject) => {
      MqttModule.unsubscribe(
        topic,
        (success: string) => {
          console.log("Unsubscribe success:", success);
          resolve();
        },
        (error: string) => {
          console.error("Unsubscribe error:", error);
          reject(error);
        },
      );
    });
  }, []);

  const publish = useCallback(
    async (
      topic: string,
      message: string | Uint8Array | ArrayBuffer,
      qos = 1,
      retained = false,
    ): Promise<void> => {
      return new Promise((resolve, reject) => {
        let publishMessage: string | Uint8Array =
          typeof message === "string" ? message : new Uint8Array();

        // Check if Buffer is available in the environment
        const isBuffer =
          typeof Buffer !== "undefined" && Buffer.isBuffer(message);

        // Handle binary data by converting to Base64 for the React Native bridge
        if (
          message instanceof Uint8Array ||
          message instanceof ArrayBuffer ||
          isBuffer
        ) {
          let bytes;

          if (message instanceof ArrayBuffer) {
            bytes = new Uint8Array(message);
          } else if (isBuffer) {
            bytes = new Uint8Array(message);
          } else {
            bytes = message; // Already Uint8Array
          }

          // Convert to Base64 and prefix with marker
          let binary = "";
          const len = bytes.byteLength;
          for (let i = 0; i < len; i++) {
            binary += String.fromCharCode(bytes[i]);
          }
          publishMessage = "B64:" + btoa(binary);

          console.log("Publish: Converted binary protobuf to Base64");
          console.log("  - Topic:", topic);
          console.log("  - Original byte length:", len);
          console.log("  - Base64 string length:", publishMessage.length);
        } else if (typeof message !== "string") {
          publishMessage = JSON.stringify(message);
        }

        MqttModule.publish(
          topic,
          publishMessage as string,
          qos,
          retained,
          (success) => {
            console.log("Publish success:", success);
            resolve();
          },
          (error) => {
            console.error("Publish error:", error);
            reject(error);
          },
        );
      });
    },
    [],
  );

  const value = {
    isConnected,
    error,
    connect,
    disconnect,
    subscribe,
    unsubscribe,
    publish,
  };

  return <MqttContext.Provider value={value}>{children}</MqttContext.Provider>;
};
