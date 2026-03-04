import { WebPlugin } from '@capacitor/core';
import type { AudioTriggerNativePlugin, AudioTriggerEvent } from './audioTriggerNative';

export class AudioTriggerNativeWeb extends WebPlugin implements AudioTriggerNativePlugin {
  private mediaRecorder: MediaRecorder | null = null;
  private audioStream: MediaStream | null = null;
  private audioChunks: Blob[] = [];
  private isRecordingActive = false;
  private sessionToken: string | null = null;
  private emailUsuario: string | null = null;
  private origemGravacao: string = 'botao_manual';
  private recordingStartTime: number = 0;
  private segmentIndex: number = 1;
  private uploadInterval: ReturnType<typeof setInterval> | null = null;

  async start(): Promise<{ success: boolean; alreadyRunning?: boolean }> {
    console.log('[AudioTriggerNativeWeb] start() - Web fallback, no-op');
    return { success: false };
  }

  async getDeviceId(): Promise<{ deviceId: string }> {
    console.log('[AudioTriggerNativeWeb] getDeviceId() - Web fallback');
    return { deviceId: 'web-device' };
  }

  async setDeviceId(options: { deviceId: string }): Promise<{ success: boolean; deviceId: string }> {
    console.log('[AudioTriggerNativeWeb] setDeviceId() - Web fallback', options);
    return { success: false, deviceId: options.deviceId };
  }

  async stop(): Promise<{ success: boolean }> {
    console.log('[AudioTriggerNativeWeb] stop() - Web fallback, no-op');
    return { success: false };
  }

  async isRunning(): Promise<{ isRunning: boolean }> {
    console.log('[AudioTriggerNativeWeb] isRunning() - Web fallback');
    return { isRunning: false };
  }

  async getStatus(): Promise<{ success: boolean }> {
    console.log('[AudioTriggerNativeWeb] getStatus() - Web fallback');
    return { success: false };
  }

  async startRecording(options?: {
    sessionToken?: string;
    emailUsuario?: string;
    origemGravacao?: string
  }): Promise<{ success: boolean }> {
    try {
      console.log('[AudioTriggerNativeWeb] 🎤 startRecording called', options);

      if (this.isRecordingActive) {
        console.warn('[AudioTriggerNativeWeb] Recording already active');
        return { success: false };
      }

      // Store credentials
      this.sessionToken = options?.sessionToken || null;
      this.emailUsuario = options?.emailUsuario || null;
      this.origemGravacao = options?.origemGravacao || 'botao_manual';
      this.segmentIndex = 1;

      // Request microphone permission
      console.log('[AudioTriggerNativeWeb] Requesting microphone access...');
      this.audioStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        }
      });

      console.log('[AudioTriggerNativeWeb] ✅ Microphone access granted');

      // Create MediaRecorder
      const mimeType = this.getSupportedMimeType();
      console.log('[AudioTriggerNativeWeb] Using MIME type:', mimeType);

      this.mediaRecorder = new MediaRecorder(this.audioStream, {
        mimeType,
        audioBitsPerSecond: 128000,
      });

      this.audioChunks = [];
      this.recordingStartTime = Date.now();

      // Handle data available
      this.mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          console.log('[AudioTriggerNativeWeb] Data available:', event.data.size, 'bytes');
          this.audioChunks.push(event.data);
        }
      };

      // Handle recording stop
      this.mediaRecorder.onstop = async () => {
        console.log('[AudioTriggerNativeWeb] MediaRecorder stopped');
        await this.processRecording();
      };

      // Handle errors
      this.mediaRecorder.onerror = (event: Event) => {
        console.error('[AudioTriggerNativeWeb] MediaRecorder error:', event);
        this.cleanup();
      };

      // Start recording
      this.mediaRecorder.start(10000); // Capture data every 10 seconds
      this.isRecordingActive = true;

      console.log('[AudioTriggerNativeWeb] ✅ Recording started');

      // Notify recording started
      this.notifyListeners('audioTriggerEvent', {
        event: 'nativeRecordingStarted',
        origemGravacao: this.origemGravacao,
        timestamp: Date.now(),
      });

      // Start periodic segment upload (every 30 seconds)
      this.uploadInterval = setInterval(() => {
        this.uploadSegment();
      }, 30000);

      return { success: true };

    } catch (error) {
      console.error('[AudioTriggerNativeWeb] ❌ Error starting recording:', error);
      this.cleanup();
      return { success: false };
    }
  }

  async stopRecording(): Promise<{ success: boolean }> {
    try {
      console.log('[AudioTriggerNativeWeb] 🛑 stopRecording called');

      if (!this.isRecordingActive || !this.mediaRecorder) {
        console.warn('[AudioTriggerNativeWeb] No active recording');
        return { success: false };
      }

      // Stop MediaRecorder
      if (this.mediaRecorder.state !== 'inactive') {
        this.mediaRecorder.stop();
      }

      // Stop upload interval
      if (this.uploadInterval) {
        clearInterval(this.uploadInterval);
        this.uploadInterval = null;
      }

      this.isRecordingActive = false;

      console.log('[AudioTriggerNativeWeb] ✅ Recording stopped');

      // Notify recording stopped
      this.notifyListeners('audioTriggerEvent', {
        event: 'nativeRecordingStopped',
        timestamp: Date.now(),
      });

      return { success: true };

    } catch (error) {
      console.error('[AudioTriggerNativeWeb] ❌ Error stopping recording:', error);
      this.cleanup();
      return { success: false };
    }
  }

  async updateConfig(options: { config: any }): Promise<{ success: boolean }> {
    console.log('[AudioTriggerNativeWeb] updateConfig() - Web fallback, no-op', options);
    return { success: false };
  }

  async reportStatus(options: { status: string; isMonitoring: boolean; motivo: string }): Promise<{ success: boolean }> {
    console.log('[AudioTriggerNativeWeb] reportStatus() - Web fallback, no-op', options);
    return { success: false };
  }

  async stopManualRecording(): Promise<{ success: boolean; wasRecording?: boolean }> {
    console.log('[AudioTriggerNativeWeb] stopManualRecording() - Web fallback');
    return this.stopRecording().then(r => ({ ...r, wasRecording: false }));
  }

  async getPendingRecordings(): Promise<{ recordings: any[] }> {
    console.log('[AudioTriggerNativeWeb] getPendingRecordings() - Web fallback');
    return { recordings: [] };
  }

  async uploadRecording(options: { id: string }): Promise<{ success: boolean }> {
    console.log('[AudioTriggerNativeWeb] uploadRecording() - Web fallback', options);
    return { success: false };
  }

  async deleteRecording(options: { id: string }): Promise<{ success: boolean }> {
    console.log('[AudioTriggerNativeWeb] deleteRecording() - Web fallback', options);
    return { success: false };
  }

  private getSupportedMimeType(): string {
    const types = [
      'audio/webm;codecs=opus',
      'audio/webm',
      'audio/ogg;codecs=opus',
      'audio/mp4',
    ];

    for (const type of types) {
      if (MediaRecorder.isTypeSupported(type)) {
        return type;
      }
    }

    return ''; // Browser will use default
  }

  private async processRecording() {
    try {
      console.log('[AudioTriggerNativeWeb] Processing recording...');

      if (this.audioChunks.length === 0) {
        console.warn('[AudioTriggerNativeWeb] No audio chunks to process');
        return;
      }

      const audioBlob = new Blob(this.audioChunks, { type: 'audio/webm' });
      const duration = Math.floor((Date.now() - this.recordingStartTime) / 1000);

      console.log('[AudioTriggerNativeWeb] Audio blob size:', audioBlob.size, 'bytes');
      console.log('[AudioTriggerNativeWeb] Duration:', duration, 'seconds');

      // Upload final segment
      await this.uploadSegment(true);

      this.cleanup();

    } catch (error) {
      console.error('[AudioTriggerNativeWeb] Error processing recording:', error);
      this.cleanup();
    }
  }

  private async uploadSegment(isFinal: boolean = false) {
    try {
      if (this.audioChunks.length === 0) {
        console.log('[AudioTriggerNativeWeb] No chunks to upload');
        return;
      }

      console.log('[AudioTriggerNativeWeb] 📤 Uploading segment', this.segmentIndex);

      const audioBlob = new Blob(this.audioChunks, { type: 'audio/webm' });
      const duration = Math.floor((Date.now() - this.recordingStartTime) / 1000);

      // Convert blob to base64
      const base64Audio = await this.blobToBase64(audioBlob);

      // TODO: Send to backend
      // For now, just log
      console.log('[AudioTriggerNativeWeb] Segment ready:', {
        segmentIndex: this.segmentIndex,
        size: audioBlob.size,
        duration,
        origemGravacao: this.origemGravacao,
        isFinal,
      });

      // Notify progress
      this.notifyListeners('audioTriggerEvent', {
        event: 'nativeRecordingProgress',
        segmentIndex: this.segmentIndex,
        timestamp: Date.now(),
      });

      // Clear chunks for next segment
      this.audioChunks = [];
      this.segmentIndex++;
      this.recordingStartTime = Date.now();

    } catch (error) {
      console.error('[AudioTriggerNativeWeb] Error uploading segment:', error);
    }
  }

  private blobToBase64(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onloadend = () => {
        const base64 = (reader.result as string).split(',')[1];
        resolve(base64);
      };
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }

  private cleanup() {
    console.log('[AudioTriggerNativeWeb] Cleaning up...');

    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      this.mediaRecorder.stop();
    }

    if (this.audioStream) {
      this.audioStream.getTracks().forEach(track => {
        track.stop();
        console.log('[AudioTriggerNativeWeb] Stopped track:', track.label);
      });
      this.audioStream = null;
    }

    if (this.uploadInterval) {
      clearInterval(this.uploadInterval);
      this.uploadInterval = null;
    }

    this.mediaRecorder = null;
    this.audioChunks = [];
    this.isRecordingActive = false;
  }
}
