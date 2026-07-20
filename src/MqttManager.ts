import {
  NativeEventEmitter,
  EmitterSubscription,
  Platform,
} from "react-native";
import MqttModule from "./MqttModule";
import type { MqttConfig, MqttMessage } from "./types";

/**
 * Prefix marker to distinguish intentional Base64-encoded binary messages
 * from plain text that happens to be valid Base64 (e.g., JSON strings).
 * Must match the prefix checked in native Android and iOS modules.
 */
const BINARY_MARKER = "B64:";

/**
 * Singleton MQTT Manager for imperative API usage.
 * Wraps the native MqttModule and provides a simple connect/disconnect/pub/sub interface.
 *
 * Use this for non-React code (managers, services, singletons).
 * For React components, use MqttProvider + useMqtt hook instead.
 */
export class MqttManager {
  private static _instance: MqttManager;
  private eventEmitter: NativeEventEmitter | null = null;
  private subscriptions: EmitterSubscription[] = [];
  private config: MqttConfig | null = null;
  private _isConnected: boolean = false;

  private constructor() {
    this.setupEventEmitter();
    // Skip cleanup in test environment (process.env.NODE_ENV === 'test')
    if (process.env.NODE_ENV !== "test") {
      this.performInitialCleanup();
    }
  }

  public static get Instance(): MqttManager {
    return this._instance ?? (this._instance = new this());
  }

  private performInitialCleanup(): void {
    if (typeof MqttModule.cleanup === "function") {
      console.log("[MqttManager] Performing initial cleanup...");
      MqttModule.cleanup(
        (success) => {
          console.log("[MqttManager] Initial cleanup successful:", success);
        },
        (error) => {
          console.log("[MqttManager] Cleanup error (non-critical):", error);
        },
      );
    } else {
      console.log(
        "[MqttManager] cleanup method not available, using disconnect fallback...",
      );
      MqttModule.disconnect(
        (success) => {
          console.log("[MqttManager] Disconnect fallback successful:", success);
        },
        (error) => {
          console.log(
            "[MqttManager] Disconnect fallback error (non-critical):",
            error,
          );
        },
      );
    }
  }

  private setupEventEmitter(): void {
    this.eventEmitter = new NativeEventEmitter(MqttModule as any);

    this.subscriptions.push(
      this.eventEmitter.addListener("MqttConnected", (message) => {
        console.log("[MqttManager] MQTT Connected:", message);
        this._isConnected = true;
        if (this.config?.onConnect) {
          this.config.onConnect();
        }
      }),
    );

    this.subscriptions.push(
      this.eventEmitter.addListener("MqttDisconnected", (message) => {
        console.log("[MqttManager] MQTT Disconnected:", message);
        this._isConnected = false;
        if (this.config?.onConnectionLost) {
          this.config.onConnectionLost(message);
        }
      }),
    );

    this.subscriptions.push(
      this.eventEmitter.addListener("MqttMessage", (data) => {
        try {
          const parsedData = typeof data === "string" ? JSON.parse(data) : data;

          // Decode Base64 binary messages and return as Uint8Array
          // Uint8Array is the correct type for binary data and works directly with
          // TextDecoder, Buffer.from(), and protobuf parsers without conversion
          if (parsedData.isBinary && parsedData.message) {
            try {
              const binaryString = atob(parsedData.message);
              const bytes = new Uint8Array(binaryString.length);
              for (let i = 0; i < binaryString.length; i++) {
                bytes[i] = binaryString.charCodeAt(i);
              }

              // Return Uint8Array directly - no .slice() copy needed since bytes.buffer
              // is already a dedicated, exactly-sized ArrayBuffer
              parsedData.message = bytes;
              console.log(
                "[MqttManager] Message received:",
                parsedData.topic,
                "(",
                bytes.length,
                "bytes, Uint8Array)",
              );
            } catch (decodeErr) {
              console.error(
                "[MqttManager] Failed to decode Base64 message:",
                decodeErr,
              );
              // Keep original message on decode failure
            }
          } else {
            console.log(
              "[MqttManager] Message received:",
              parsedData.topic,
              "(text)",
            );
          }

          if (this.config?.onMessage) {
            this.config.onMessage(parsedData);
          }
        } catch (err) {
          console.error("[MqttManager] Failed to parse MQTT message:", err);
        }
      }),
    );

    this.subscriptions.push(
      this.eventEmitter.addListener("MqttDeliveryComplete", (message) => {
        console.log("[MqttManager] MQTT Delivery Complete:", message);
      }),
    );
  }

  /**
   * Connect to MQTT broker with mTLS certificates
   */
  public async connect(config: MqttConfig): Promise<void> {
    this.config = config;

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
          config.isAdminUser ?? false ? null : config.brokerCommonName ?? null,
          config.isAdminUser ?? false,
          config.keystorePath ?? null,
          config.keystorePassword ?? null,
          config.keystoreFormat ?? null,
          (success: string) => {
            console.log("[MqttManager] Connect success:", success);
            resolve();
          },
          (error: string) => {
            console.error("[MqttManager] Connect error:", error);
            if (config.onError) {
              config.onError(error);
            }
            reject(new Error(error));
          },
        );
      } else {
        // iOS: no keystore parameters needed
        MqttModule.connect(
          config.broker,
          config.clientId,
          config.certificates,
          config.isAdminUser ?? false ? null : config.sniHostname ?? null,
          config.brokerIp ?? null,
          config.isAdminUser ?? false ? null : config.brokerCommonName ?? null,
          config.isAdminUser ?? false,
          (success) => {
            console.log("[MqttManager] Connect success:", success);
            resolve();
          },
          (error) => {
            console.error("[MqttManager] Connect error:", error);
            if (config.onError) {
              config.onError(error);
            }
            reject(new Error(error));
          },
        );
      }
    });
  }

  /**
   * Disconnect from MQTT broker
   */
  public async disconnect(): Promise<void> {
    return new Promise((resolve, reject) => {
      MqttModule.disconnect(
        (success) => {
          console.log("[MqttManager] Disconnect success:", success);
          this._isConnected = false;
          this.config = null;
          resolve();
        },
        (error) => {
          console.error("[MqttManager] Disconnect error:", error);
          reject(new Error(error));
        },
      );
    });
  }

  /**
   * Subscribe to MQTT topic
   */
  public async subscribe(topic: string, qos: number = 1): Promise<void> {
    return new Promise((resolve, reject) => {
      MqttModule.subscribe(
        topic,
        qos,
        (success) => {
          console.log("[MqttManager] Subscribe success:", success);
          resolve();
        },
        (error) => {
          console.error("[MqttManager] Subscribe error:", error);
          reject(new Error(error));
        },
      );
    });
  }

  /**
   * Unsubscribe from MQTT topic
   */
  public async unsubscribe(topic: string): Promise<void> {
    return new Promise((resolve, reject) => {
      MqttModule.unsubscribe(
        topic,
        (success) => {
          console.log("[MqttManager] Unsubscribe success:", success);
          resolve();
        },
        (error) => {
          console.error("[MqttManager] Unsubscribe error:", error);
          reject(new Error(error));
        },
      );
    });
  }

  /**
   * Publish message to MQTT topic
   */
  public async publish(
    topic: string,
    message: string | Uint8Array | ArrayBuffer,
    qos: number = 1,
    retained: boolean = false,
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      let publishMessage: string | Uint8Array = message as any;

      // Check if Buffer is available in the environment
      const isBuffer =
        typeof Buffer !== "undefined" && Buffer.isBuffer(message);

      // Handle binary data by converting to Base64 for the React Native bridge
      if (
        message instanceof Uint8Array ||
        message instanceof ArrayBuffer ||
        isBuffer
      ) {
        let bytes: Uint8Array;

        if (message instanceof ArrayBuffer) {
          bytes = new Uint8Array(message);
        } else if (isBuffer) {
          bytes = new Uint8Array(message);
        } else {
          bytes = message;
        }

        // Convert to Base64 and prefix with marker to distinguish from plain text
        // that happens to be valid Base64 (e.g., JSON strings)
        let binary = "";
        const len = bytes.byteLength;
        for (let i = 0; i < len; i++) {
          binary += String.fromCharCode(bytes[i]);
        }
        // Prefix with marker to indicate this is intentional Base64-encoded binary
        // Note: Publish path uses B64: prefix to signal intent on the wire,
        // while receive path uses isBinary flag based on UTF-8 validity check.
        // This asymmetry allows the receiver to handle messages from any publisher.
        publishMessage = BINARY_MARKER + btoa(binary);

        console.log("[MqttManager] Converted binary to Base64 with marker:", {
          topic,
          originalByteLength: len,
          base64Length: publishMessage.length,
        });
      } else if (typeof message !== "string") {
        publishMessage = JSON.stringify(message);
      }

      MqttModule.publish(
        topic,
        publishMessage,
        qos,
        retained,
        (success) => {
          console.log("[MqttManager] Publish success:", success);
          resolve();
        },
        (error) => {
          console.error("[MqttManager] Publish error:", error);
          reject(new Error(error));
        },
      );
    });
  }

  /**
   * Check if connected to MQTT broker
   */
  public isConnected(): boolean {
    return this._isConnected;
  }

  /**
   * Cleanup - should only be called on app shutdown
   */
  public cleanup(): void {
    console.log("[MqttManager] Cleaning up...");
    this.subscriptions.forEach((sub) => sub.remove());
    this.subscriptions = [];

    if (typeof MqttModule.cleanup === "function") {
      MqttModule.cleanup(
        () => {},
        () => {},
      );
    } else {
      MqttModule.disconnect(
        () => {},
        () => {},
      );
    }

    this._isConnected = false;
    this.config = null;
  }
}
