/**
 * Audio Trigger Singleton Service
 * Manages a single AudioTrigger instance that persists across component mounts/unmounts
 * This ensures calibration state is preserved when navigating between screens
 */

import type {
  AudioTriggerConfig,
  AudioTriggerEvent,
  AudioTriggerMetrics,
  TriggerState,
  GenderClass,
  FrameMetrics,
} from '@/types/audioTrigger';
import type { ServerAudioTriggerConfig } from '@/lib/types';
import { DEFAULT_CONFIG } from '@/types/audioTrigger';
import { processFrame, updateNoiseFloor } from '@/services/dspService';
import { estimateF0, getF0Median2s, resetPitchEstimator } from '@/services/pitchService';
import { classifyGender } from '@/services/genderClassifierService';
import { discussionDetector } from '@/services/newDiscussionDetectorService';
import { triggerStateMachine } from '@/services/triggerStateMachine';
import { RingBuffer, calculateMedian } from '@/utils/ringBuffer';
import { getFullConfig, saveConfig, saveServerConfig } from '@/utils/configStorage';
import { serverToClientConfig } from '@/utils/configConverter';
import { AdaptiveNoiseFloor } from '@/lib/AdaptiveNoiseFloor';

// Buffer sizes
const MAX_EVENTS = 100;
const FRAMES_PER_AGGREGATION = 25; // 500ms at 20ms frames

class AudioTriggerSingleton {
  // State
  private isCapturing = false;
  private hasPermission: boolean | null = null;
  private error: string | null = null;
  private config: AudioTriggerConfig;

  // Streams
  private events: AudioTriggerEvent[] = [];
  private metrics: AudioTriggerMetrics | null = null;
  private triggerState: TriggerState = 'IDLE';

  // Buffer de RMS para média de 4 segundos
  private rmsBuffer: number[] = [];
  // Buffer de ZCR para média de 4 segundos
  private zcrBuffer: number[] = [];
  // Limiar ZCR para classificação H/M (configurável via tuning)
  private zcrThreshold: number = 0.12;

  // Audio processing refs
  private audioContext: AudioContext | null = null;
  private analyser: AnalyserNode | null = null;
  private mediaStream: MediaStream | null = null;
  private processor: ScriptProcessorNode | null = null;
  private animationFrame: number | null = null;

  // Processing state
  private noiseFloor: number = -50;
  private frameBuffer: RingBuffer<FrameMetrics> = new RingBuffer(FRAMES_PER_AGGREGATION);
  private lastAggregationTime: number = 0;
  private speechOn: boolean = false;
  private loudOn: boolean = false;
  private currentGender: GenderClass = 'UNKNOWN';
  private discussionOn: boolean = false;

  // Adaptive noise floor
  private adaptiveNoiseFloor: AdaptiveNoiseFloor | null = null;
  private isCalibrated = false;

  // Debug frame counter
  private frameCount: number = 0;
  private aggregationCount: number = 0;

  // State change listeners
  private stateListeners: Array<() => void> = [];

  constructor() {
    console.log('[AudioTriggerSingleton] ✅ SINGLETON INITIALIZED - Calibration will persist across navigation');
    console.log('[AudioTriggerSingleton] 🔄 Build timestamp:', new Date().toISOString());

    // Load config from storage
    this.config = getFullConfig();

    // Set up event callback for state machine
    triggerStateMachine.setEventCallback((event) => this.addEvent(event));
  }



  // Add state change listener
  addStateListener(listener: () => void) {
    this.stateListeners.push(listener);
  }

  removeStateListener(listener: () => void) {
    const index = this.stateListeners.indexOf(listener);
    if (index > -1) {
      this.stateListeners.splice(index, 1);
    }
  }

  private notifyStateChange() {
    this.stateListeners.forEach(listener => {
      try {
        listener();
      } catch (error) {
        console.error('[AudioTriggerSingleton] Error in state listener:', error);
      }
    });
  }

  // Add event
  private addEvent(event: AudioTriggerEvent) {
    this.events = [event, ...this.events].slice(0, MAX_EVENTS);
    this.notifyStateChange();
  }

  // Process audio frame
  private processAudioFrame(samples: Float32Array, sampleRate: number) {
    const now = Date.now();
    const mode = this.config.processingMode || 'FULL';

    this.frameCount++;

    // Log every 50 frames (~1 second at 50fps)
    if (this.frameCount % 50 === 0) {
      const maxSample = Math.max(...Array.from(samples).map(Math.abs));
      console.log(`[AudioTrigger] Frame # ${this.frameCount} | Max sample: ${maxSample.toFixed(4)}`);

      // Log every 250 frames (~5 seconds)
      if (this.frameCount % 250 === 0) {
        console.log(`[AudioTrigger] Processed ${this.frameCount} frames`);
      }
    }

    // First frame processed
    if (this.frameCount === 1) {
      console.log('[AudioTrigger] First frame processed');
    }

    // Adaptive noise floor will be updated in aggregation block (every 500ms)

    // Process based on mode
    if (mode === 'LIGHT') {
      // LIGHT mode: minimal processing for battery saving
      // Just update noise floor, skip detection
      return;
    }

    // FULL mode: complete analysis
    const frameMetrics = processFrame(samples, this.noiseFloor, this.config);
    this.frameBuffer.push({ ...frameMetrics, timestamp: Date.now() });

    // Aggregate every FRAMES_PER_AGGREGATION frames (500ms)
    if (this.frameBuffer.isFull && now - this.lastAggregationTime >= 500) {
      this.lastAggregationTime = now;

      const frames = this.frameBuffer.toArray();

      // Calculate aggregated metrics
      const loudDb = calculateMedian(frames.map(f => f.dbfs));
      const vadDb = calculateMedian(frames.map(f => f.dbfs));
      const speechDensity = frames.filter(f => f.isSpeech).length / frames.length;
      const loudDensity = frames.filter(f => f.isLoud).length / frames.length;

      // Update adaptive noise floor with aggregated loudDb (every 500ms)
      if (this.adaptiveNoiseFloor) {
        this.adaptiveNoiseFloor.addSample(loudDb);
        this.noiseFloor = this.adaptiveNoiseFloor.getNoiseFloor();
      }

      // Estimate F0 and classify gender
      const f0Result = estimateF0(samples, sampleRate);
      const f0Median = getF0Median2s();
      const gender = classifyGender(f0Median, f0Result.confidence, this.speechOn, this.config);

      // Update state
      this.speechOn = speechDensity >= this.config.speechDensityMin;
      this.loudOn = loudDensity >= this.config.loudDensityMin;
      this.currentGender = gender;

      // Discussion detection
      const aggregation = {
        timestamp: now,
        dbfsMedian: loudDb,
        speechRatio: speechDensity,
        loudRatio: loudDensity,
      };
      const discussionResult = discussionDetector.processAggregation(
        aggregation,
        gender,
        this.config
      );

      this.discussionOn = discussionResult.discussionOn;

      // Detect noisy environment
      // Environment is considered noisy if:
      // 1. NoiseFloor is high (> -40 dB) OR
      // 2. Average loudDb is consistently high (> -25 dB)
      const isNoisy = this.noiseFloor > -40 || loudDb > -25;

      // Update metrics
      this.metrics = {
        timestamp: now,
        dbfsCurrent: loudDb,
        noiseFloorDb: this.noiseFloor,
        speechRatio: speechDensity,
        loudRatio: loudDensity,
        speechDensity,
        loudDensity,
        turnTaking: 0,
        score: discussionResult.score,
        f0Current: f0Result.f0,
        f0Median2s: f0Median,
        voicingConfidence: f0Result.confidence,
        gender,
        speechOn: this.speechOn,
        loudOn: this.loudOn,
        discussionOn: this.discussionOn,
        recordingOn: false,
        recordingDuration: 0,
        state: this.triggerState,
        isNoisy,
      };

      // Increment aggregation count
      this.aggregationCount++;

      // Debug log every 10 aggregations (~5 seconds)
      if (this.aggregationCount % 10 === 0) {
        console.log(`[AudioTrigger] Score: ${discussionResult.score} | speechDensity: ${speechDensity.toFixed(2)} | loudDensity: ${loudDensity.toFixed(2)} | speechOn: ${this.speechOn} | loudOn: ${this.loudOn}`);
        console.log(`[AudioTrigger] Config: speechDensityMin=${this.config.speechDensityMin} | loudDensityMin=${this.config.loudDensityMin}`);
        console.log(`[AudioTrigger] discussionOn: ${discussionResult.discussionOn} | v3.0.0`);
      }

      // State machine
      triggerStateMachine.process(speechDensity, this.discussionOn, this.config);

      const newState = triggerStateMachine.state;
      if (newState !== this.triggerState) {
        this.triggerState = newState;
      }

      this.notifyStateChange();
    }
  }

  // Start audio capture
  async start(): Promise<void> {
    if (this.isCapturing) {
      console.log('[AudioTriggerSingleton] ✅ ALREADY CAPTURING - Skipping restart (calibration preserved)');
      return;
    }

    try {
      // Request microphone permission
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: false,
          noiseSuppression: false,
          autoGainControl: false,
        }
      });

      this.hasPermission = true;
      this.mediaStream = stream;
      console.log('[AudioTrigger] Microphone permission granted');

      // Create audio context
      this.audioContext = new AudioContext({ sampleRate: 48000 });
      const source = this.audioContext.createMediaStreamSource(stream);

      // Create analyser
      this.analyser = this.audioContext.createAnalyser();
      this.analyser.fftSize = 2048;
      this.analyser.smoothingTimeConstant = 0;

      // Create script processor for frame-by-frame processing
      const bufferSize = 2048;
      this.processor = this.audioContext.createScriptProcessor(bufferSize, 1, 1);

      this.processor.onaudioprocess = (event) => {
        const inputData = event.inputBuffer.getChannelData(0);
        this.processAudioFrame(inputData, this.audioContext!.sampleRate);
      };

      // Connect nodes
      source.connect(this.analyser);
      this.analyser.connect(this.processor);
      this.processor.connect(this.audioContext.destination);

      console.log('[AudioTrigger] Sample rate:', this.audioContext.sampleRate);
      console.log('[AudioTrigger] Frame size:', bufferSize, 'samples');

      // Initialize adaptive noise floor (only once, preserve across restarts)
      if (!this.adaptiveNoiseFloor) {
        const initialNoiseFloor = this.config.noiseFloorLearningRate ? -50 : -50;
        const learningRate = this.config.noiseFloorLearningRate ?? 0.1;
        this.adaptiveNoiseFloor = new AdaptiveNoiseFloor(initialNoiseFloor, learningRate);
        this.adaptiveNoiseFloor.setCalibrationCallback((calibrated) => {
          console.log('[AudioTrigger] Calibration status changed:', calibrated);
          this.isCalibrated = calibrated;
          this.notifyStateChange();
        });
        console.log('[AudioTrigger] Adaptive noise floor initialized');
      } else {
        const wasCalibrated = this.adaptiveNoiseFloor.isCalibrated();
        console.log(`[AudioTrigger] ✅ REUSING EXISTING ADAPTIVE NOISE FLOOR - Calibration preserved: ${wasCalibrated}`);
        // Update isCalibrated state from existing instance
        this.isCalibrated = wasCalibrated;
      }

      console.log('[AudioTrigger] Starting process loop');
      this.isCapturing = true;
      console.log('[AudioTrigger] Audio capture started successfully');

      this.addEvent({
        type: 'micStarted',
        timestamp: Date.now(),
        message: 'Microfone iniciado',
      });

      this.notifyStateChange();
    } catch (err) {
      console.error('[AudioTrigger] Error starting audio capture:', err);
      this.hasPermission = false;
      this.error = err instanceof Error ? err.message : 'Unknown error';
      this.notifyStateChange();
      throw err;
    }
  }

  // Stop audio capture
  stop(): void {
    if (!this.isCapturing) {
      console.log('[AudioTriggerSingleton] Not capturing');
      return;
    }

    console.log('[AudioTrigger] Stopping audio capture');

    if (this.animationFrame) {
      cancelAnimationFrame(this.animationFrame);
      this.animationFrame = null;
    }

    if (this.processor) {
      this.processor.disconnect();
      this.processor.onaudioprocess = null;
      this.processor = null;
    }

    if (this.analyser) {
      this.analyser.disconnect();
      this.analyser = null;
    }

    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(track => track.stop());
      this.mediaStream = null;
    }

    if (this.audioContext) {
      this.audioContext.close();
      this.audioContext = null;
    }

    this.isCapturing = false;

    this.addEvent({
      type: 'micStopped',
      timestamp: Date.now(),
      message: 'Microfone parado',
    });

    this.notifyStateChange();
  }

  // Reset all state (including calibration)
  reset(): void {
    this.stop();

    // Reset processing state
    this.noiseFloor = -50;
    this.frameBuffer.clear();
    this.lastAggregationTime = 0;
    this.speechOn = false;
    this.loudOn = false;
    this.currentGender = 'UNKNOWN';
    this.frameCount = 0;

    // Reset adaptive noise floor (loses calibration)
    this.adaptiveNoiseFloor = null;
    this.isCalibrated = false;

    // Reset services
    resetPitchEstimator();
    discussionDetector.reset();
    triggerStateMachine.reset();

    // Reset state
    this.metrics = null;
    this.triggerState = 'IDLE';
    this.error = null;

    this.notifyStateChange();
  }

  // Update config
  updateConfig(newConfig: Partial<AudioTriggerConfig>): void {
    this.config = { ...this.config, ...newConfig };
    saveConfig(this.config);
    this.notifyStateChange();
  }

  // Update from server config
  updateServerConfig(serverConfig: ServerAudioTriggerConfig): void {
    const converted = serverToClientConfig(serverConfig);
    this.config = { ...this.config, ...converted };
    saveServerConfig(serverConfig);
    this.notifyStateChange();
  }

  // Set processing mode
  setProcessingMode(mode: 'FULL' | 'LIGHT'): void {
    console.log('[AudioTrigger] Setting processing mode:', mode);
    this.config = { ...this.config, processingMode: mode };
    this.notifyStateChange();
  }

  // Clear events
  clearEvents(): void {
    this.events = [];
    this.notifyStateChange();
  }

  // Copy events to clipboard
  copyEvents(): void {
    const text = this.events
      .map(e => `[${new Date(e.timestamp).toISOString()}] ${e.type}: ${e.message || ''}`)
      .join('\n');
    navigator.clipboard.writeText(text);
  }

  // Getters
  getIsCapturing(): boolean {
    return this.isCapturing;
  }

  getHasPermission(): boolean | null {
    return this.hasPermission;
  }

  getError(): string | null {
    return this.error;
  }

  getEvents(): AudioTriggerEvent[] {
    return this.events;
  }

  getMetrics(): AudioTriggerMetrics | null {
    return this.metrics;
  }

  getState(): TriggerState {
    return this.triggerState;
  }

  getConfig(): AudioTriggerConfig {
    return this.config;
  }

  getIsRecording(): boolean {
    return this.triggerState === 'RECORDING';
  }

  getDiscussionOn(): boolean {
    return this.metrics?.discussionOn ?? false;
  }

  getIsCalibrated(): boolean { return this.isCalibrated; }

  // Atualizar limiar ZCR para classificação H/M em runtime
  setZcrThreshold(value: number): void {
    this.zcrThreshold = value;
  }

  /**
   * Set calibration status from native service
   * This allows native calibration to update UI state
   */
  setCalibrationStatus(calibrated: boolean) {
    console.log('[AudioTriggerSingleton] Setting calibration status from native:', calibrated);
    this.isCalibrated = calibrated;
    this.notifyStateChange();
  }

  /**
   * Atualiza métricas com dados do evento audioMetrics nativo
   * Permite que as métricas nativas atualizem o estado da UI
   */
  setNativeMetrics(nativeMetrics: any) {
    // Classificação H/M baseada no ZCR
    const zcr = nativeMetrics.zcr ?? 0;
    let gender: 'MALE' | 'FEMALE' | 'UNKNOWN' = 'UNKNOWN';
    if (zcr > 0 && (nativeMetrics.isSpeech ?? false)) {
      gender = zcr < this.zcrThreshold ? 'MALE' : 'FEMALE';
    }

    // Mapeia o estado nativo para o estado do trigger
    const nativeState = nativeMetrics.state ?? 'IDLE';
    if (nativeState !== this.triggerState) {
      this.triggerState = nativeState as TriggerState;
    }

    // Calcular média RMS de 4 segundos
    const currentRms = nativeMetrics.rmsDb ?? 0;
    this.rmsBuffer.push(currentRms);
    if (this.rmsBuffer.length > 4) this.rmsBuffer.shift(); // manter últimos 4 valores (~4s com agregação 1s)
    const rmsAvg4s = this.rmsBuffer.reduce((a, b) => a + b, 0) / this.rmsBuffer.length;

    // Calcular média ZCR de 4 segundos (só quando há fala)
    if (zcr > 0 && (nativeMetrics.isSpeech ?? false)) {
      this.zcrBuffer.push(zcr);
      if (this.zcrBuffer.length > 4) this.zcrBuffer.shift();
    }
    const zcrAvg4s = this.zcrBuffer.length > 0
      ? this.zcrBuffer.reduce((a, b) => a + b, 0) / this.zcrBuffer.length
      : 0;

    // Atualiza métricas com dados nativos
    this.metrics = {
      timestamp: nativeMetrics.timestamp ?? Date.now(),
      dbfsCurrent: currentRms,
      noiseFloorDb: nativeMetrics.noiseFloor ?? 0,
      speechRatio: 0,
      loudRatio: 0,
      speechDensity: nativeMetrics.speechDensity ?? 0,
      loudDensity: nativeMetrics.loudDensity ?? 0,
      turnTaking: 0,
      score: nativeMetrics.score ?? 0,
      f0Current: zcr > 0 ? zcr : null,
      f0Median2s: null,
      voicingConfidence: null,
      gender: gender,
      speechOn: nativeMetrics.isSpeech ?? false,
      loudOn: nativeMetrics.isLoud ?? false,
      discussionOn: nativeMetrics.state === 'DISCUSSION_DETECTED',
      recordingOn: nativeMetrics.isRecording ?? false,
      recordingDuration: 0,
      state: nativeState as TriggerState,
      isNoisy: false,
      // Dados de silêncio para tela técnica
      isSilent: nativeMetrics.isSilent ?? false,
      silenceDurationMs: nativeMetrics.silenceDurationMs ?? 0,
      silenceTimeoutMs: nativeMetrics.silenceTimeoutMs ?? 600000,
      silenceThresholdDb: nativeMetrics.silenceThresholdDb ?? -40.0,
      isNativeRecording: nativeMetrics.isRecording ?? false,
      // Timers e contadores do DiscussionDetector
      timeInStateMs: nativeMetrics.timeInStateMs ?? 0,
      continuousSilenceMs: nativeMetrics.continuousSilenceMs ?? 0,
      isManualRecording: nativeMetrics.isManualRecording ?? false,
      startHoldSeconds: nativeMetrics.startHoldSeconds ?? 0,
      endHoldSeconds: nativeMetrics.endHoldSeconds ?? 0,
      silenceDecaySeconds: nativeMetrics.silenceDecaySeconds ?? 0,
      cooldownSeconds: nativeMetrics.cooldownSeconds ?? 0,
      speechDensityMin: nativeMetrics.speechDensityMin ?? 0,
      loudDensityMin: nativeMetrics.loudDensityMin ?? 0,
      speechDensityEnd: nativeMetrics.speechDensityEnd ?? 0,
      loudDensityEnd: nativeMetrics.loudDensityEnd ?? 0,
      rmsAvg4s,
      zcrAvg4s,
    };

    // Atualiza calibração do nativo
    if (nativeMetrics.isCalibrated !== undefined) {
      this.isCalibrated = nativeMetrics.isCalibrated;
    }

    this.notifyStateChange();
  }
}

// Export singleton instance
export const audioTriggerSingleton = new AudioTriggerSingleton();
