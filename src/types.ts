export interface MqttMessage {
  topic: string;
  message: string | Uint8Array;
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
