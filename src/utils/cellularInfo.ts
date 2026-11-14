import { registerPlugin } from '@capacitor/core';

export interface CellularInfo {
  available: boolean;
  signalDbm: number | null;
  signalLevel: number | null;
  carrierName: string;
  lineName: string;
  lineNumber: string;
  networkType: string;
  simState: string;
}

interface CellularInfoPlugin {
  getInfo(): Promise<CellularInfo>;
}

const CellularInfoBridge = registerPlugin<CellularInfoPlugin>('CellularInfo');

export const EMPTY_CELLULAR_INFO: CellularInfo = {
  available: false,
  signalDbm: null,
  signalLevel: null,
  carrierName: '',
  lineName: '',
  lineNumber: '',
  networkType: '',
  simState: '',
};

export async function readCellularInfo(): Promise<CellularInfo> {
  try {
    return await CellularInfoBridge.getInfo();
  } catch {
    return EMPTY_CELLULAR_INFO;
  }
}

export default CellularInfoBridge;

export function getSignalLabel(level: number | null): string {
  if (level === null) return 'Unavailable';
  if (level >= 4) return 'Excellent';
  if (level >= 3) return 'Good';
  if (level >= 2) return 'Fair';
  if (level >= 1) return 'Weak';
  return 'No Signal';
}
