import { useContext } from 'react';
import { MqttContext } from './MqttContext';
import type { MqttContextType } from './types';

export const useMqtt = (): MqttContextType => {
  const context = useContext(MqttContext);
  
  if (!context) {
    throw new Error('useMqtt must be used within MqttProvider');
  }
  
  return context;
};
