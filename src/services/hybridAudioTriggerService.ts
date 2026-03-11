/**
 * Hybrid Audio Trigger Service - v3 Native-First Architecture
 * 
 * CRITICAL CHANGE (Android 14/15 compatibility):
 * - NATIVE service must be started while app is in FOREGROUND (eligible state)
 * - NATIVE continues running when app goes to background
 * - DO NOT try to start NATIVE when app is already in background (SecurityException)
 * 
 * Architecture:
 * - Start: Always start NATIVE while in foreground
 * - Background: NATIVE continues running (no mode switch)
 * - Foreground: NATIVE continues running (no mode switch)
 * - JS callbacks are optional, only for UI updates
 */

import { Capacitor } from '@capacitor/core';
import { App } from '@capacitor/app';
import AudioPermission from '@/plugins/audioPermission';
import { AudioTriggerNative } from '@/plugins/audioTriggerNative';
import type { AudioTriggerEvent } from '@/plugins/audioTriggerNative';
import { PermissionFlowState } from './permissionFlowState';

// State machine modes (simplified)
type TriggerMode =
  | 'STOPPED'                  // Not started
  | 'WAITING_PERMISSION'       // Waiting for RECORD_AUDIO
  | 'RUNNING';                 // Native service running (works in fg and bg)

// JS Callbacks interface (optional, for UI updates only)
interface JsCallbacks {
  onFirstFrame?: (data: any) => void;
  onDebug?: (data: any) => void;
  onStateChange?: (mode: TriggerMode) => void;
}

// State machine class
class HybridAudioTriggerService {
  // State
  private mode: TriggerMode = 'STOPPED';
  private pendingStart = false;
  private startInProgress = false;
  private lastStartAttempt = 0;
  private readonly startDebounceMs = 2000;

  // Callbacks (optional)
  private jsCallbacks: JsCallbacks | null = null;

  // Native listener
  private nativeListenerRegistered = false;

  // App state listener
  private appStateListenerRegistered = false;

  // Permission flow listener
  private permissionFlowUnsubscribe: (() => void) | null = null;

  // Initialization flag
  private initialized = false;

  // Event listeners
  private eventListeners: Array<(event: AudioTriggerEvent) => void> = [];

  // Native config
  private nativeConfig: any = null;

  /**
   * Initialize (no audio start)
   */
  init() {
    if (this.initialized) {
      console.log('[HybridAudioTrigger] Already initialized, skipping');
      return;
    }

    if (!Capacitor.isNativePlatform()) {
      console.log('[HybridAudioTrigger] Not on native platform, skipping');
      return;
    }

    console.log('[HybridAudioTrigger] 🔧 Initializing v3 (Native-First)...');

    // Register native listener (once)
    this.registerNativeListenerOnce();

    // Register app state listener (for logging only, no mode switching)
    this.registerAppStateListener();

    // Subscribe to permission flow changes
    this.permissionFlowUnsubscribe = PermissionFlowState.subscribe(() => {
      this.onPermissionFlowChanged();
    });

    this.initialized = true;
    console.log('[HybridAudioTrigger] ✅ Initialized (Native-First mode)');

    // CRITICAL: Sync status from native service on init
    // This handles the case where Android killed the JS process but native service is still running
    this.syncStatusFromNative();
    this.refreshModeFromNativeRunning();
  }

  /**
   * Register JS callbacks (optional, for UI updates only)
   */
  registerJavaScriptCallbacks(callbacks: JsCallbacks) {
    this.jsCallbacks = callbacks;
    console.log('[HybridAudioTrigger] ✅ JavaScript callbacks registered (optional)');
  }

  /**
   * Start audio trigger (Native-First)
   * MUST be called while app is in FOREGROUND
   */
  async start(config?: any) {
    console.log('\n\n\n');
    console.log('🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡');
    console.log('🟡 [HybridAudioTrigger] start() CHAMADO!');
    console.log('🟡 mode=', this.mode);
    console.log('🟡 startInProgress=', this.startInProgress);
    console.log('🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡🟡');
    console.log('\n\n\n');

    // Store config if provided
    if (config) {
      this.nativeConfig = config;
    }

    // Gate 1: Already running
    if (this.mode === 'RUNNING') {
      console.log('[HybridAudioTrigger] ✅ Already RUNNING, skipping start (idempotent)');
      return;
    }

    // Gate 2: Start already in progress
    if (this.startInProgress) {
      console.log('[HybridAudioTrigger] 🔒 Start already in progress, ignoring');
      return;
    }

    // Gate 3: Debounce
    const now = Date.now();
    if (now - this.lastStartAttempt < this.startDebounceMs) {
      console.log('[HybridAudioTrigger] ⏱️ Debounce active, ignoring');
      return;
    }

    // Gate 4: Permission flow
    if (PermissionFlowState.isInFlow()) {
      console.log('[HybridAudioTrigger] 🚫 BLOCKED_BY_PERMISSION_FLOW');
      this.pendingStart = true;
      this.mode = 'STOPPED';
      this.notifyStateChange();
      return;
    }

    // Gate 5: Check app state - MUST be in foreground
    // Keep a defensive fallback because some iOS bridge states can throw.
    try {
      const appState = await App.getState();
      if (!appState.isActive) {
        console.log('[HybridAudioTrigger] 🚫 BLOCKED_NOT_FOREGROUND: Cannot start native service in background');
        console.log('[HybridAudioTrigger] ⏳ Will retry when app returns to foreground');
        this.pendingStart = true;
        return;
      }
    } catch (error) {
      // Fallback: if state cannot be read, continue only on iOS to avoid hard failures.
      if (Capacitor.getPlatform() !== 'ios') {
        console.log('[HybridAudioTrigger] ❌ Cannot verify foreground state; blocking native start on non-iOS platform');
        this.pendingStart = true;
        return;
      }
      console.warn('[HybridAudioTrigger] ⚠️ App.getState() unavailable on iOS, proceeding with native start fallback');
    }

    // Gate 6: RECORD_AUDIO permission
    const permissionStatus = await AudioPermission.checkPermission();
    if (!permissionStatus.granted) {
      console.log('[HybridAudioTrigger] ⏳ WAITING_PERMISSION: RECORD_AUDIO');
      this.pendingStart = true;
      this.mode = 'WAITING_PERMISSION';
      this.notifyStateChange();
      return;
    }

    // All gates passed - start native service
    this.startInProgress = true;
    this.lastStartAttempt = now;

    try {
      console.log('[HybridAudioTrigger] 🟢 NATIVE_STARTING (while app is in foreground)');

      // iOS: Plugin nativo Swift está disponível
      // Android: Plugin nativo Java está disponível
      // Merge config parameter with nativeConfig (parameter takes precedence for tokens)
      const mergedConfig = {
        ...this.nativeConfig,
        ...config // sessionToken, refreshToken, emailUsuario from parameter
      };
      const options = { config: mergedConfig };

      console.log('🔍🔍🔍 DEBUG mergedConfig:');
      console.log('  - sessionToken:', mergedConfig.sessionToken ? `${mergedConfig.sessionToken.substring(0, 20)}...` : 'MISSING');
      console.log('  - refreshToken:', mergedConfig.refreshToken ? `${mergedConfig.refreshToken.substring(0, 20)}...` : 'MISSING');
      console.log('  - emailUsuario:', mergedConfig.emailUsuario || 'MISSING');
      console.log('  - monitoringPeriods:', mergedConfig.monitoringPeriods?.length || 0);

      console.log('\n\n\n');
      console.log('🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠');
      console.log('🟠 CHAMANDO AudioTriggerNative.start() AGORA!');
      console.log('🟠 options=', JSON.stringify(options));
      console.log('🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠🟠');
      console.log('\n\n\n');

      const result = await AudioTriggerNative.start(options);

      if (result.alreadyRunning) {
        console.log('[HybridAudioTrigger] ✅ NATIVE already running (idempotent)');
      } else {
        console.log('[HybridAudioTrigger] ✅ NATIVE_STARTED');
      }

      this.mode = 'RUNNING';
      this.pendingStart = false;
      this.notifyStateChange();

    } catch (error: any) {
      console.error('[HybridAudioTrigger] ❌ NATIVE_START_FAILED:', error);

      // Check if it's a SecurityException (FGS not eligible)
      if (error.message?.includes('SecurityException') || error.message?.includes('not eligible')) {
        console.log('[HybridAudioTrigger] 🚫 FGS_NOT_ELIGIBLE: App was not in eligible state');
        this.pendingStart = true;
      }

      this.mode = 'STOPPED';
      this.notifyStateChange();
    } finally {
      this.startInProgress = false;
    }
  }

  /**
   * Stop audio trigger
   */
  async stop() {
    console.log('[HybridAudioTrigger] 🛑 stop() called');

    if (this.mode === 'STOPPED') {
      console.log('[HybridAudioTrigger] Already stopped');
      return;
    }

    try {
      console.log('[HybridAudioTrigger] 🔴 NATIVE_STOPPING');
      await AudioTriggerNative.stop();
      console.log('[HybridAudioTrigger] ✅ NATIVE_STOPPED');
    } catch (error) {
      console.error('[HybridAudioTrigger] ❌ NATIVE_STOP_FAILED:', error);
    }

    this.mode = 'STOPPED';
    this.notifyStateChange();
  }

  /**
   * Register app state listener (for logging and pending start retry)
   */
  private registerAppStateListener() {
    if (this.appStateListenerRegistered) {
      console.log('[HybridAudioTrigger] App state listener already registered');
      return;
    }

    App.addListener('appStateChange', async ({ isActive }) => {
      console.log(`[HybridAudioTrigger] 📱 App state: ${isActive ? 'FOREGROUND' : 'BACKGROUND'}`);

      if (isActive) {
        // App returned to foreground
        console.log('[HybridAudioTrigger] 📱 FOREGROUND detected');

        // CRITICAL: Re-sync calibration status from native when returning to foreground
        // The native service may be calibrated but JS lost the state
        if (Capacitor.isNativePlatform()) {
          try {
            await AudioTriggerNative.getStatus();
            console.log('[HybridAudioTrigger] 🔄 Status sync requested from native');
            await this.refreshModeFromNativeRunning();
          } catch (error) {
            console.error('[HybridAudioTrigger] Failed to sync status from native:', error);
          }
        }

        // If we have a pending start, try now (we're in eligible state)
        if (this.pendingStart) {
          console.log('[HybridAudioTrigger] 🔄 Pending start detected, retrying...');
          await this.start();
        }
      } else {
        // App going to background
        console.log('[HybridAudioTrigger] 🌙 BACKGROUND detected');

        // DO NOT try to start native here (Android 14/15 will block it)
        // Native service should already be running from foreground start
        if (this.mode === 'RUNNING') {
          console.log('[HybridAudioTrigger] ✅ NATIVE continues running in background');
        } else {
          console.log('[HybridAudioTrigger] ⚠️ NATIVE not running, will start when app returns to foreground');
          this.pendingStart = true;
        }
      }
    });

    this.appStateListenerRegistered = true;
    console.log('[HybridAudioTrigger] ✅ App state listener registered');
  }

  /**
   * Register native listener (once)
   */
  private registerNativeListenerOnce() {
    if (this.nativeListenerRegistered) {
      console.log('[HybridAudioTrigger] Native listener already registered');
      return;
    }

    AudioTriggerNative.addListener('audioTriggerEvent', (event) => {
      console.log('[HybridAudioTrigger] 📡 Native event:', event.event);

      // Keep Hybrid mode aligned with native runtime whenever we receive native traffic.
      if (this.mode !== 'RUNNING' && event.event !== 'fgsNotEligible') {
        this.mode = 'RUNNING';
        this.pendingStart = false;
        this.notifyStateChange();
      }

      // Handle FGS not eligible event
      if (event.event === 'fgsNotEligible') {
        console.log('[HybridAudioTrigger] 🚫 Received FGS_NOT_ELIGIBLE from native');
        this.mode = 'STOPPED';
        this.pendingStart = true;
        this.notifyStateChange();
      }

      // Processar métricas nativas direto no service (independente de componentes)
      // Garante atualização contínua mesmo quando Home.tsx não está montado
      if (event.event === 'audioMetrics') {
        import('@/services/audioTriggerSingleton').then(({ audioTriggerSingleton }) => {
          audioTriggerSingleton.setNativeMetrics(event);
        });
      }

      // Handle calibration status from native
      if (event.event === 'calibrationStatus' && event.isCalibrated !== undefined) {
        console.log('[HybridAudioTrigger] 📡 Native calibration status:', event.isCalibrated);
        // Update audioTriggerSingleton state
        import('@/services/audioTriggerSingleton').then(({ audioTriggerSingleton }) => {
          audioTriggerSingleton.setCalibrationStatus(event.isCalibrated ?? false);
        });
      }

      // Forward to event listeners
      this.notifyListeners(event);

      // Forward to JS callback if registered
      if (this.jsCallbacks?.onDebug) {
        this.jsCallbacks.onDebug(event);
      }
    });

    this.nativeListenerRegistered = true;
    console.log('[HybridAudioTrigger] ✅ Native listener registered');
  }

  /**
   * Handle permission flow state changes
   */
  private onPermissionFlowChanged() {
    const inFlow = PermissionFlowState.isInFlow();
    console.log(`[HybridAudioTrigger] 🔄 Permission flow changed: ${inFlow}`);

    if (!inFlow && this.pendingStart) {
      console.log('[HybridAudioTrigger] 🔄 Permission flow ended, retrying start...');
      this.start();
    }
  }

  /**
   * Notify state change to JS callback
   */
  private notifyStateChange() {
    if (this.jsCallbacks?.onStateChange) {
      this.jsCallbacks.onStateChange(this.mode);
    }
  }

  /**
   * Add listener for audio trigger events
   */
  addListener(callback: (event: AudioTriggerEvent) => void) {
    this.eventListeners.push(callback);
  }

  /**
   * Remove listener
   */
  removeListener(callback: (event: AudioTriggerEvent) => void) {
    const index = this.eventListeners.indexOf(callback);
    if (index > -1) {
      this.eventListeners.splice(index, 1);
    }
  }

  private notifyListeners(event: AudioTriggerEvent) {
    this.eventListeners.forEach(listener => {
      try {
        listener(event);
      } catch (error) {
        console.error('[HybridAudioTrigger] Error in event listener:', error);
      }
    });
  }

  /**
   * Set configuration for native audio trigger
   * Hot reload: updates config without restart if native is running
   */
  async setNativeConfig(config: any) {
    this.nativeConfig = config;
    console.log('[HybridAudioTrigger] 🔧 Native config set:', config);

    // Hot reload: update config if native is already running
    if (this.mode === 'RUNNING' && Capacitor.isNativePlatform()) {
      try {
        await AudioTriggerNative.updateConfig({ config });
        console.log('[HybridAudioTrigger] ✅ Native config hot-reloaded');
      } catch (error) {
        console.error('[HybridAudioTrigger] ❌ Native config hot-reload failed:', error);
      }
    }
  }

  /**
   * Reportar mudança de status de monitoramento ao servidor via plugin nativo
   */
  async reportStatus(status: string, isMonitoring: boolean, motivo: string): Promise<void> {
    if (!Capacitor.isNativePlatform()) {
      console.log('[HybridAudioTrigger] reportStatus ignorado (não é plataforma nativa)');
      return;
    }

    try {
      console.log(`[HybridAudioTrigger] 📡 Reportando status: ${status}, isMonitoring: ${isMonitoring}, motivo: ${motivo}`);
      await AudioTriggerNative.reportStatus({ status, isMonitoring, motivo });
      console.log('[HybridAudioTrigger] ✅ Status reportado com sucesso');
    } catch (error) {
      console.error('[HybridAudioTrigger] ❌ Erro ao reportar status:', error);
    }
  }

  /**
   * Get current mode (for UI)
   */
  getMode(): TriggerMode {
    return this.mode;
  }

  /**
   * Check if pending start
   */
  isPendingStart(): boolean {
    return this.pendingStart;
  }

  /**
   * Sync status from native service
   * Called on init to handle process restart scenarios
   */
  private async syncStatusFromNative() {
    try {
      console.log('[HybridAudioTrigger] 🔄 Syncing status from native service...');
      await AudioTriggerNative.getStatus();
      console.log('[HybridAudioTrigger] ✅ Status sync requested');
    } catch (error) {
      console.log('[HybridAudioTrigger] ℹ️ Native service not running or error:', error);
    }
  }

  /**
   * Sync hybrid mode based on native running status.
   * Prevents stale STOPPED mode in JS when native is already alive.
   */
  private async refreshModeFromNativeRunning() {
    if (!Capacitor.isNativePlatform()) return;

    try {
      const result = await AudioTriggerNative.isRunning();
      const isRunning = !!result?.isRunning;
      if (isRunning && this.mode !== 'RUNNING') {
        console.log('[HybridAudioTrigger] ✅ Native is running, switching mode to RUNNING');
        this.mode = 'RUNNING';
        this.pendingStart = false;
        this.notifyStateChange();
      }
    } catch (error) {
      console.warn('[HybridAudioTrigger] Could not query native running status:', error);
    }
  }

  /**
   * Get status (for debugging)
   */
  getStatus() {
    return {
      mode: this.mode,
      pendingStart: this.pendingStart,
      startInProgress: this.startInProgress,
      lastStartAttempt: this.lastStartAttempt,
      nativeListenerRegistered: this.nativeListenerRegistered,
    };
  }

  /**
   * Cleanup
   */
  destroy() {
    console.log('[HybridAudioTrigger] 🧹 Destroying...');

    if (this.permissionFlowUnsubscribe) {
      this.permissionFlowUnsubscribe();
    }

    this.stop();
    this.initialized = false;
  }
}

// Export singleton instance
export const hybridAudioTrigger = new HybridAudioTriggerService();
