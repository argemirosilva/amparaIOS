import { registerPlugin } from '@capacitor/core';

export interface AudioTriggerNativePlugin {
  /**
   * Get device ID from native Keychain (iOS) or persistent storage
   */
  getDeviceId(): Promise<{ deviceId: string }>;

  /**
   * Set device ID in native Keychain (iOS) - forces replacement
   */
  setDeviceId(options: { deviceId: string }): Promise<{ success: boolean; deviceId: string }>;

  /**
   * Start native audio trigger service
   */
  start(options?: { config?: any }): Promise<{ success: boolean; alreadyRunning?: boolean }>;

  /**
   * Stop native audio trigger service
   */
  stop(): Promise<{ success: boolean }>;

  /**
   * Check if native audio trigger is running
   */
  isRunning(): Promise<{ isRunning: boolean }>;

  /**
   * Start native recording manually
   */
  startRecording(options?: { sessionToken?: string; emailUsuario?: string; origemGravacao?: string }): Promise<{ success: boolean }>;

  /**
   * Stop native recording manually
   */
  stopRecording(): Promise<{ success: boolean }>;

  /**
   * Stop native recording manually (iOS only, alias for stopRecording)
   */
  stopManualRecording(): Promise<{ success: boolean; wasRecording?: boolean }>;

  /**
   * Update native audio trigger configuration dynamically
   */
  updateConfig(options: { config: any }): Promise<{ success: boolean }>;

  /**
   * Atualizar parâmetros individuais de detecção em tempo real (painel de tuning)
   */
  updateTuning(params: Record<string, number>): Promise<{ success: boolean }>;

  /**
   * Obter status atual do serviço nativo (dispara broadcast de calibrationStatus)
   */
  getStatus(): Promise<{ success: boolean }>;

  /**
   * Obter gravações pendentes de upload
   */
  getPendingRecordings(): Promise<{ recordings: any[] }>;

  /**
   * Fazer upload de uma gravação pendente
   */
  uploadRecording(options: { id: string }): Promise<{ success: boolean }>;

  /**
   * Deletar uma gravação pendente
   */
  deleteRecording(options: { id: string }): Promise<{ success: boolean }>;

  /**
   * Add listener for audio trigger events
   */
  addListener(
    eventName: 'audioTriggerEvent',
    listenerFunc: (event: AudioTriggerEvent) => void
  ): Promise<any>;

  /**
   * Add listener for recording countdown events
   */
  addListener(
    eventName: 'recordingCountdown',
    listenerFunc: (data: {
      remainingSeconds: number;
      timeoutType: string;
      isRecording: boolean;
    }) => void,
  ): Promise<any>;

  /**
   * Reportar mudança de status de monitoramento ao servidor via plugin nativo
   */
  reportStatus(options: { status: string; isMonitoring: boolean; motivo: string }): Promise<{ success: boolean }>;

  /**
   * Salvar preferência de notificações de eventos (salvo localmente no dispositivo)
   */
  setNotificationPreference(options: { enabled: boolean }): Promise<{ success: boolean }>;

  /**
   * Obter preferência de notificações de eventos
   */
  getNotificationPreference(): Promise<{ enabled: boolean }>;

  // ========== Pipeline Híbrido — Enrollment (SPEC v3) ==========

  /**
   * Iniciar enrollment de uma frase personalizada
   */
  startEnrollment(options: {
    phraseId: string;
    type: 'OPERATIONAL' | 'CONTEXTUAL' | 'GENERIC';
  }): Promise<{ success: boolean; message: string }>;

  /**
   * Gravar e adicionar amostra de áudio ao enrollment em andamento
   */
  addEnrollmentSample(options?: {
    durationMs?: number;
  }): Promise<{
    success: boolean;
    message: string;
    currentSamples: number;
    minRequired: number;
    maxAllowed: number;
  }>;

  /**
   * Finalizar enrollment: valida consistência, calcula thresholds, persiste
   */
  finishEnrollment(): Promise<{ success: boolean; message: string }>;

  /**
   * Cancelar enrollment em andamento
   */
  cancelEnrollment(): Promise<{ success: boolean }>;

  /**
   * Remover frase cadastrada
   */
  removePhrase(options: { phraseId: string }): Promise<{ success: boolean }>;

  /**
   * Obter diagnósticos do pipeline e enrollment
   */
  getPipelineDiagnostics(): Promise<{
    pipeline?: string;
    enrollment?: string;
    hasStaleTemplates?: boolean;
  }>;

  /**
   * Remove all listeners
   */
  removeAllListeners(): Promise<void>;
}

// Extensão nativa
export interface NativeRecordingStartedEvent {
  event: 'nativeRecordingStarted';
  sessionId: string;
  origemGravacao?: string;
  startedAt: number;
}
export interface NativeRecordingStoppedEvent {
  event: 'nativeRecordingStopped';
  sessionId: string;
}
export interface PhraseDetectedEvent {
  event: 'phraseDetected';
  phraseId: string;
  confidence: number;
  phraseType: string;
}

export type AudioTriggerEvent =
  | {
      event: 'discussionDetected' | 'discussionEnded' | 'nativeRecordingProgress' | 'nativeUploadProgress' | 'fgsNotEligible' | 'calibrationStatus' | 'recordingState' | 'audioMetrics';
      reason?: string;
      sessionId?: string;
      origemGravacao?: string;
      startedAt?: number;
      segmentIndex?: number;
      pending?: number;
      success?: number;
      failure?: number;
      isCalibrated?: boolean;
      isRecording?: boolean;
      timestamp: number;
      [key: string]: unknown;
    }
  | NativeRecordingStartedEvent
  | NativeRecordingStoppedEvent
  | PhraseDetectedEvent;

export const AudioTriggerNative = registerPlugin<AudioTriggerNativePlugin>('AudioTriggerNative', {
  web: () => import('./audioTriggerNativeWeb').then(m => new m.AudioTriggerNativeWeb()),
});
