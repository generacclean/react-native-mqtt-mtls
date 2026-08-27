import { ReactNode } from "react";

// Re-export types from single source of truth (src/types.ts)
// This prevents type drift and ensures runtime behavior matches type definitions
export type {
  MqttMessage,
  MqttCertificates,
  MqttConfig,
  MqttContextType,
  MqttModuleType,
} from "./src/types";

export interface MqttProviderProps {
  children: ReactNode;
}

export const MqttProvider: React.FC<MqttProviderProps>;
export function useMqtt(): import("./src/types").MqttContextType;

/**
 * Singleton MQTT Manager for imperative API usage
 */
export class MqttManager {
  static readonly Instance: MqttManager;
  connect(config: import("./src/types").MqttConfig): Promise<void>;
  disconnect(): Promise<void>;
  subscribe(topic: string, qos?: number): Promise<void>;
  unsubscribe(topic: string): Promise<void>;
  publish(
    topic: string,
    message: string | Uint8Array | ArrayBuffer,
    qos?: number,
    retained?: boolean,
  ): Promise<void>;
  isConnected(): boolean;
  cleanup(): void;
}

declare const MqttModule: import("./src/types").MqttModuleType;
export default MqttModule;
