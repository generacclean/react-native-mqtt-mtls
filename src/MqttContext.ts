import { createContext } from 'react';
import type { MqttContextType } from './types';

export const MqttContext = createContext<MqttContextType | null>(null);
