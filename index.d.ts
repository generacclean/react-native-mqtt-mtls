import { ReactNode } from "react";

// Re-export types from single source of truth (src/types.ts)
// This prevents type drift and ensures runtime behavior matches type definitions
export type {
  MqttMessage,
  MqttCertificates,
  MqttConfig,
  MqttContextType,
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

/**
 * Native MQTT Module interface
 * Parameter order matches iOS/Android native implementations:
 * broker, clientId, certificates, sniHostname, brokerIp, brokerCommonName, isAdminUser, successCallback, errorCallback
 */
export interface MqttModuleType {
  connect(
    broker: string,
    clientId: string,
    certificates: import("./src/types").MqttCertificates,
    sniHostname: string | null,
    brokerIp: string | null,
    brokerCommonName: string | null,
    isAdminUser: boolean,
    keystorePath: string | null,
    keystorePassword: string | null,
    keystoreFormat: string | null,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;
  disconnect(
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;
  subscribe(
    topic: string,
    qos: number,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;
  unsubscribe(
    topic: string,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;
  publish(
    topic: string,
    message: string,
    qos: number,
    retained: boolean,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;
  isConnected(callback: (isConnected: boolean) => void): void;
}

declare const MqttModule: MqttModuleType;
export default MqttModule;
