import { registerPlugin } from '@capacitor/core';

export interface AudioTriggerNativePlugin {
  /**
   * Start native audio trigger service
   */
  start(options?: { config?: any }): Promise<{ success: boolean }>;

  /**
   * Stop native audio trigger service
   */
  stop(): Promise<{ success: boolean }>;

  /**
   * Check if native audio trigger is running
   */
  isRunning(): Promise<{ isRunning: boolean }>;

  /**
   * Get current status from native (triggers calibrationStatus broadcast)
   */
  getStatus(): Promise<{ success: boolean }>;

  /**
   * Start native recording manually
   */
  startRecording(options?: { sessionToken?: string; emailUsuario?: string; origemGravacao?: string }): Promise<{ success: boolean }>;

  /**
   * Stop native recording manually
   */
  stopRecording(): Promise<{ success: boolean }>;

  /**
   * Update native audio trigger configuration dynamically
   */
  updateConfig(options: { config: any }): Promise<{ success: boolean }>;

  /**
   * Add listener for audio trigger events
   */
  addListener(
    eventName: 'audioTriggerEvent',
    listenerFunc: (event: AudioTriggerEvent) => void
  ): Promise<any>;

  /**
   * Remove all listeners
   */
  removeAllListeners(): Promise<void>;
}

export interface AudioTriggerEvent {
  event: 'discussionDetected' | 'discussionEnded' | 'nativeRecordingStarted' | 'nativeRecordingStopped' | 'nativeRecordingProgress' | 'nativeUploadProgress';
  reason?: string;
  sessionId?: string;
  origemGravacao?: string;
  startedAt?: number;
  segmentIndex?: number;
  pending?: number;
  success?: number;
  failure?: number;
  timestamp: number;
}

export interface PipelineDiagnostics {
  pipeline?: string;
  enrollment?: string;
  hasStaleTemplates?: boolean;
}

export const AudioTriggerNative = registerPlugin<AudioTriggerNativePlugin & {
  getPipelineDiagnostics(): Promise<PipelineDiagnostics>;
}>('AudioTriggerNative', {
  web: () => import('../plugins/audioTriggerNativeWeb').then(m => new m.AudioTriggerNativeWeb()),
});
