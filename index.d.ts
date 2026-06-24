declare module "@generacclean/react-native-mqtt-mtls" {
  import { ReactNode } from "react";

  export interface MqttMessage {
    topic: string;
    message: string;
    qos: number;
    isBinary?: boolean;
  }

  export interface MqttCertificates {
    clientCert: string;
    privateKeyAlias: string;
    rootCa: string;
    useHardwareKey: boolean;
  }

  export interface MqttConfig {
    broker: string;
    clientId: string;
    /**
     * Whether to connect as an admin user with full permissions.
     * Defaults to true. Set to false only if SNI hostname verification is required.
     */
    isAdminUser?: boolean;
    sniHostname?: string;
    brokerIp?: string;
    brokerCommonName?: string;
    certificates: MqttCertificates;
    onMessage?: (message: MqttMessage) => void;
    onConnect?: () => void;
    onConnectionLost?: (error: string) => void;
    onReconnect?: () => void;
    onError?: (error: string) => void;
  }

  export interface MqttContextType {
    isConnected: boolean;
    error: string | null;
    connect: (config: MqttConfig) => Promise<void>;
    disconnect: () => Promise<void>;
    subscribe: (topic: string, qos?: number) => Promise<void>;
    unsubscribe: (topic: string) => Promise<void>;
    publish: (
      topic: string,
      message: string,
      qos?: number,
      retained?: boolean,
    ) => Promise<void>;
  }

  export interface MqttProviderProps {
    children: ReactNode;
  }

  export const MqttProvider: React.FC<MqttProviderProps>;
  export function useMqtt(): MqttContextType;

  /**
   * Singleton MQTT Manager for imperative API usage
   */
  export class MqttManager {
    static readonly Instance: MqttManager;
    connect(config: MqttConfig): Promise<void>;
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
      certificates: MqttCertificates,
      sniHostname: string | null,
      brokerIp: string | null,
      brokerCommonName: string | null,
      isAdminUser: boolean,
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

  const MqttModule: MqttModuleType;
  export default MqttModule;
}
