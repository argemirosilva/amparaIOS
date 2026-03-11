/**
 * AMPARA Audio Trigger Kit - Types
 * Core types for audio trigger detection system
 */

// Estados da máquina de estados (inclui estados nativos do Java)
export type TriggerState = 'IDLE' | 'PRE_TRIGGER' | 'RECORDING' | 'COOLDOWN'
  | 'MONITORING' | 'CALIBRATING' | 'DISCUSSION_DETECTED' | 'RECORDING_STARTED' | 'DISCUSSION_ENDING';

// Modos de processamento (para economia de bateria)
export type ProcessingMode = 'FULL' | 'LIGHT';

// Classificação de gênero (com viés masculino)
export type GenderClass = 'MALE' | 'FEMALE' | 'UNKNOWN';

// Tipos de eventos
export type AudioTriggerEventType =
  | 'micStarted'
  | 'micStopped'
  | 'speechOn'
  | 'speechOff'
  | 'loudOn'
  | 'loudOff'
  | 'discussionStarted'
  | 'discussionEnded'
  | 'recordingStarted'
  | 'recordingStopped'
  | 'genderChanged'
  | 'stateChanged'
  | 'error';

// Evento de alto nível
export interface AudioTriggerEvent {
  type: AudioTriggerEventType;
  timestamp: number;
  message?: string;
  payload?: Record<string, unknown>;
}

// Métricas por frame (20ms)
export interface FrameMetrics {
  timestamp: number;
  rms: number;
  dbfs: number;
  zcr: number;
  isSpeech: boolean;
  isLoud: boolean;
}

// Métricas agregadas (500ms)
export interface AggregatedMetrics {
  timestamp: number;
  dbfsMedian: number;
  speechRatio: number;
  loudRatio: number;
  speechOnset: boolean; // transição OFF->ON
}

// Métricas da janela de discussão (10s)
export interface DiscussionWindowMetrics {
  speechDensity: number;
  loudDensity: number;
  turnTaking: number;
}

// Métricas de pitch
export interface PitchMetrics {
  f0: number | null;
  confidence: number;
  f0Median2s: number | null;
}

// Métricas completas emitidas a cada 500ms
export interface AudioTriggerMetrics {
  timestamp: number;
  // Frame atual
  dbfsCurrent: number;
  noiseFloorDb: number;
  // Agregação 500ms
  speechRatio: number;
  loudRatio: number;
  // Janela discussão 10s
  speechDensity: number;
  loudDensity: number;
  turnTaking: number;
  score: number;
  // Pitch
  f0Current: number | null;
  f0Median2s: number | null;
  voicingConfidence: number | null;
  // Classificação
  gender: GenderClass;
  // Estados
  speechOn: boolean;
  loudOn: boolean;
  discussionOn: boolean;
  recordingOn: boolean;
  recordingDuration: number;
  state: TriggerState;
  isNoisy: boolean; // Ambiente muito ruidoso - detecção comprometida
  // Dados de silêncio nativos (opcionais, para tela técnica)
  isSilent?: boolean;
  silenceDurationMs?: number;
  silenceTimeoutMs?: number;
  silenceThresholdDb?: number;
  isNativeRecording?: boolean;
  // Timers e contadores do DiscussionDetector (opcionais, para tela técnica)
  timeInStateMs?: number;
  continuousSilenceMs?: number;
  isManualRecording?: boolean;
  startHoldSeconds?: number;
  endHoldSeconds?: number;
  silenceDecaySeconds?: number;
  cooldownSeconds?: number;
  speechDensityMin?: number;
  loudDensityMin?: number;
  speechDensityEnd?: number;
  loudDensityEnd?: number;
  rmsAvg4s?: number;
  zcrAvg4s?: number;
}

// Configuração unificada
export interface AudioTriggerConfig {
  // Modo de processamento
  processingMode?: ProcessingMode;
  // Captura
  sampleRate: number;
  frameMs: number;
  aggregationMs: number;
  // Janelas de tempo
  discussionWindowSeconds: number;
  preTriggerSeconds: number;
  startHoldSeconds: number;
  endHoldSeconds: number;
  cooldownSeconds: number;
  // Noise floor
  noiseFloorLearningRate: number;
  // Thresholds de detecção
  loudDeltaDb: number;
  vadDeltaDb: number;
  // Thresholds de discussão - início
  speechDensityMin: number;
  loudDensityMin: number;
  turnTakingMin: number;
  // Thresholds de discussão - fim
  speechDensityEnd: number;
  loudDensityEnd: number;
  // Pitch/Gender
  maleBiasEnabled: boolean;
  f0LowMale: number;
  f0HighFemale: number;
  voicingConfidenceMin: number;
  // ZCR para VAD
  zcrMinVoice: number;
  zcrMaxVoice: number;
  // Silence decay
  silenceDecaySeconds: number;
  silenceDecayRate: number;
}

// Configuração padrão
export const DEFAULT_CONFIG: AudioTriggerConfig = {
  sampleRate: 44100, // Web Audio API default
  frameMs: 20,
  aggregationMs: 500,
  discussionWindowSeconds: 10,
  preTriggerSeconds: 1,
  startHoldSeconds: 2, // Reduzido para testes rápidos
  endHoldSeconds: 10,
  cooldownSeconds: 10,
  noiseFloorLearningRate: 0.01,
  loudDeltaDb: 10,
  vadDeltaDb: 5,
  speechDensityMin: 0.05, // Muito sensível para teste
  loudDensityMin: 0.03,    // Muito sensível para teste
  turnTakingMin: 1,        // Muito sensível para teste
  speechDensityEnd: 0.15,
  loudDensityEnd: 0.05,
  maleBiasEnabled: true,
  f0LowMale: 170,
  f0HighFemale: 210,
  voicingConfidenceMin: 0.7,
  zcrMinVoice: 0.02,
  zcrMaxVoice: 0.25,
  silenceDecaySeconds: 4,
  silenceDecayRate: 1,
};

// Tipo para erros
export type AudioTriggerErrorType =
  | 'permissionDenied'
  | 'micUnavailable'
  | 'streamError'
  | 'contextError';
