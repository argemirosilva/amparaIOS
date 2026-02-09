/**
 * Config Service
 * Manages remote configuration with cache, TTL, versioning, and fallback
 */

import { syncConfigMobile, getCachedConfig } from '@/lib/api';
import SecureStorage from '@/plugins/SecureStorage';
import type { ConfigSyncResponse } from '@/lib/types';
import { hybridAudioTrigger } from './hybridAudioTriggerService';
import { AudioTriggerNative } from '@/plugins/audioTriggerNative';
import { Capacitor } from '@capacitor/core';

// ============================================
// Types
// ============================================

export interface AppConfig {
  version: number; // Timestamp of config creation
  ttl_seconds: number;
  monitoring_enabled: boolean;
  monitoring_periods: Array<{ inicio: string; fim: string }>;
  periodos_semana?: import('@/lib/types').PeriodosSemana;
  dentro_horario?: boolean;
  periodo_atual_index?: number | null;
  audio_trigger?: import('@/lib/types').ServerAudioTriggerConfig;
}

export interface ConfigState {
  currentConfig: AppConfig | null;
  lastFetchedAt: string | null;
  source: 'cache' | 'remote' | 'default';
  isLoading: boolean;
  error: string | null;
}

// ============================================
// Default Configuration
// ============================================

const DEFAULT_CONFIG: AppConfig = {
  version: 0,
  ttl_seconds: 3600, // 1 hour
  monitoring_enabled: true,
  monitoring_periods: [
    { inicio: '08:00', fim: '18:00' }
  ]
  // audio_trigger will use DEFAULT_CONFIG from audioTrigger.ts if not provided by API
};

// ============================================
// Storage Keys
// ============================================

const STORAGE_KEYS = {
  CONFIG: 'ampara_config',
  CONFIG_METADATA: 'ampara_config_metadata'
};

// ============================================
// State
// ============================================

let state: ConfigState = {
  currentConfig: null,
  lastFetchedAt: null,
  source: 'default',
  isLoading: false,
  error: null
};

let listeners: Array<(state: ConfigState) => void> = [];
let syncIntervalId: NodeJS.Timeout | null = null;

// ============================================
// Core Functions
// ============================================

/**
 * Load config from cache
 */
async function loadFromCache(): Promise<AppConfig | null> {
  try {
    console.log('[ConfigService] Loading from cache...');
    
    // Try SecureStorage first (most reliable)
    const secureResult = await SecureStorage.get({ key: STORAGE_KEYS.CONFIG });
    if (secureResult.value) {
      const config = JSON.parse(secureResult.value) as AppConfig;
      console.log('[ConfigService] Loaded from SecureStorage', { version: config.version });
      return config;
    }
    
    // Fallback to localStorage (legacy)
    const cachedConfig = getCachedConfig();
    if (cachedConfig) {
      console.log('[ConfigService] Loaded from localStorage (legacy)', { version: cachedConfig.version });
      // Migrate to SecureStorage
      await SecureStorage.set({ key: STORAGE_KEYS.CONFIG, value: JSON.stringify(cachedConfig) });
      return cachedConfig as unknown as AppConfig;
    }
    
    console.log('[ConfigService] No cached config found');
    return null;
    
  } catch (error) {
    console.error('[ConfigService] Failed to load from cache:', error);
    return null;
  }
}

/**
 * Save config to cache
 */
async function saveToCache(config: AppConfig): Promise<void> {
  try {
    console.log('[ConfigService] Saving to cache...', { version: config.version });
    
    const configJson = JSON.stringify(config);
    
    // Save to SecureStorage (primary)
    await SecureStorage.set({ key: STORAGE_KEYS.CONFIG, value: configJson });
    
    // Also save to localStorage for backward compatibility
    localStorage.setItem(STORAGE_KEYS.CONFIG, configJson);
    
    // Save metadata
    const metadata = {
      lastFetchedAt: new Date().toISOString(),
      version: config.version
    };
    await SecureStorage.set({ 
      key: STORAGE_KEYS.CONFIG_METADATA, 
      value: JSON.stringify(metadata) 
    });
    
    console.log('[ConfigService] Config saved to cache');
    
  } catch (error) {
    console.error('[ConfigService] Failed to save to cache:', error);
  }
}

/**
 * Check if cached config has expired based on TTL
 */
function hasConfigExpired(config: AppConfig | null, lastFetchedAt: string | null): boolean {
  if (!config || !lastFetchedAt) {
    return true;
  }
  
  const lastFetchTime = new Date(lastFetchedAt).getTime();
  const now = Date.now();
  const ttlMs = config.ttl_seconds * 1000;
  
  const expired = (now - lastFetchTime) > ttlMs;
  
  if (expired) {
    console.log('[ConfigService] Config expired', {
      last_fetch: lastFetchedAt,
      ttl_seconds: config.ttl_seconds,
      age_seconds: Math.floor((now - lastFetchTime) / 1000)
    });
  }
  
  return expired;
}

/**
 * Fetch config from remote server
 */
async function fetchFromRemote(currentVersion: number): Promise<AppConfig | null> {
  try {
    console.log('[ConfigService] Fetching from remote...', { current_version: currentVersion });
    
    const result = await syncConfigMobile();
    
    if (result.error) {
      console.error('[ConfigService] Remote fetch failed', { error: result.error });
      return null;
    }
    
    if (!result.data?.configuracoes) {
      console.warn('[ConfigService] No config in response');
      return null;
    }
    
    // Transform API response to AppConfig format
    const remoteConfig = transformApiConfigToAppConfig(result.data);
    
    console.log('[ConfigService] Fetched from remote', { 
      version: remoteConfig.version,
      updated: remoteConfig.version > currentVersion
    });
    
    return remoteConfig;
    
  } catch (error) {
    console.error('[ConfigService] Remote fetch error:', error);
    return null;
  }
}

/**
 * Transform API response to AppConfig format
 */
function transformApiConfigToAppConfig(apiResponse: ConfigSyncResponse): AppConfig {
  // ===== DEBUG: LOG COMPLETO DA RESPOSTA DA API =====
  console.log('🔍 [ConfigService] API RESPONSE COMPLETA:', JSON.stringify(apiResponse, null, 2));
  console.log('[ConfigService] audio_trigger_config from API -> IGNORED');
  // ==================================================
  
  // Use fields directly from apiResponse (not from configuracoes)
  // periodos_hoje already has { inicio, fim } format
  const periods = apiResponse.periodos_hoje || [];
  
  return {
    version: Date.now(),
    ttl_seconds: 3600,
    monitoring_enabled: true, // Always enabled, dentro_horario controls active state
    monitoring_periods: periods,
    periodos_semana: apiResponse.periodos_semana,
    dentro_horario: apiResponse.dentro_horario ?? false,
    periodo_atual_index: apiResponse.periodo_atual_index ?? null,
    // audio_trigger: NEVER use from API - always use DEFAULT_CONFIG from audioTrigger.ts
  };
}

/**
 * Apply config to the app
 */
function applyConfig(config: AppConfig, source: 'cache' | 'remote' | 'default'): void {
  console.log('[ConfigService] Applying config', { version: config.version, source });
  
  state.currentConfig = config;
  state.source = source;
  state.error = null;
  
  notifyListeners();
  
  // Update native audio trigger service with new config
  updateNativeAudioTrigger(config);
}

/**
 * Update native audio trigger service with new config
 */
async function updateNativeAudioTrigger(config: AppConfig): Promise<void> {
  if (!Capacitor.isNativePlatform()) {
    return;
  }
  
  try {
    // Get current session tokens
    const sessionToken = sessionService.getSessionToken();
    const refreshToken = sessionService.getRefreshToken();
    const userData = sessionService.getUserData();
    
    // Parse email from user data
    let emailUsuario: string | undefined;
    if (userData) {
      try {
        const user = JSON.parse(userData);
        emailUsuario = user.email || user.usuario_email || undefined;
      } catch (e) {
        console.warn('[ConfigService] Failed to parse user data:', e);
      }
    }
    
    const nativeConfig = {
      monitoringPeriods: config.monitoring_periods || [],
      sessionToken: sessionToken || undefined,
      refreshToken: refreshToken || undefined,
      emailUsuario: emailUsuario || undefined
      // audioTriggerConfig: NEVER use from API - always use DEFAULT_CONFIG
    };
    
    console.log('[ConfigService] Updating native audio trigger config:', nativeConfig);
    
    // Update via hybrid service (handles both running and stopped states)
    await hybridAudioTrigger.setNativeConfig(nativeConfig);
    
    console.log('[ConfigService] Native audio trigger config updated successfully');
  } catch (error) {
    console.error('[ConfigService] Failed to update native audio trigger:', error);
  }
}

/**
 * Notify all listeners of state change
 */
function notifyListeners(): void {
  listeners.forEach(listener => listener(state));
}

// ============================================
// Public API
// ============================================

/**
 * Initialize and load config
 * This should be called on app startup
 */
export async function initializeConfigService(): Promise<void> {
  console.log('[ConfigService] Initializing...');
  
  state.isLoading = true;
  notifyListeners();
  
  try {
    // 1. Load from cache first (instant)
    const cachedConfig = await loadFromCache();
    
    if (cachedConfig) {
      applyConfig(cachedConfig, 'cache');
      
      // Load metadata to check TTL
      const metadataResult = await SecureStorage.get({ key: STORAGE_KEYS.CONFIG_METADATA });
      if (metadataResult.value) {
        const metadata = JSON.parse(metadataResult.value);
        state.lastFetchedAt = metadata.lastFetchedAt;
      }
    } else {
      // No cache, use default
      applyConfig(DEFAULT_CONFIG, 'default');
    }
    
    // 2. Check if we need to fetch from remote
    const currentVersion = state.currentConfig?.version || 0;
    const shouldFetch = hasConfigExpired(state.currentConfig, state.lastFetchedAt);
    
    if (shouldFetch) {
      console.log('[ConfigService] Cache expired or missing, fetching from remote...');
      
      const remoteConfig = await fetchFromRemote(currentVersion);
      
      if (remoteConfig && remoteConfig.version > currentVersion) {
        // New config available
        await saveToCache(remoteConfig);
        applyConfig(remoteConfig, 'remote');
        state.lastFetchedAt = new Date().toISOString();
        
        console.log('[ConfigService] Config updated', {
          from_version: currentVersion,
          to_version: remoteConfig.version
        });
      } else if (remoteConfig) {
        // Config not modified
        console.log('[ConfigService] Config not modified');
        state.lastFetchedAt = new Date().toISOString();
      } else {
        // Fetch failed, keep using cached/default
        console.warn('[ConfigService] Using fallback config (fetch failed)');
      }
    } else {
      console.log('[ConfigService] Using cached config (not expired)');
    }
    
  } catch (error) {
    console.error('[ConfigService] Initialization error:', error);
    state.error = error instanceof Error ? error.message : 'Unknown error';
    
    // Fallback to default if nothing else worked
    if (!state.currentConfig) {
      applyConfig(DEFAULT_CONFIG, 'default');
    }
  } finally {
    state.isLoading = false;
    notifyListeners();
  }
}

/**
 * Start periodic config sync
 */
export function startConfigSync(intervalMs: number = 3600000): void {
  if (syncIntervalId) {
    console.warn('[ConfigService] Sync already running');
    return;
  }
  
  console.log('[ConfigService] Starting periodic sync', { interval_ms: intervalMs });
  
  syncIntervalId = setInterval(async () => {
    await syncConfig();
  }, intervalMs);
}

/**
 * Stop periodic config sync
 */
export function stopConfigSync(): void {
  if (syncIntervalId) {
    console.log('[ConfigService] Stopping periodic sync');
    clearInterval(syncIntervalId);
    syncIntervalId = null;
  }
}

/**
 * Manually trigger config sync
 */
export async function syncConfig(): Promise<void> {
  console.log('[ConfigService] Manual sync triggered');
  
  const currentVersion = state.currentConfig?.version || 0;
  const remoteConfig = await fetchFromRemote(currentVersion);
  
  if (remoteConfig && remoteConfig.version > currentVersion) {
    await saveToCache(remoteConfig);
    applyConfig(remoteConfig, 'remote');
    state.lastFetchedAt = new Date().toISOString();
    
    console.log('[ConfigService] Config synced', {
      from_version: currentVersion,
      to_version: remoteConfig.version
    });
  } else if (remoteConfig) {
    console.log('[ConfigService] Config already up to date');
  } else {
    console.warn('[ConfigService] Sync failed, keeping current config');
  }
}

/**
 * Force config sync - ignores cache and always fetches from remote
 * Use this after user makes changes in the app to immediately apply them
 */
export async function forceSyncConfig(): Promise<boolean> {
  console.log('[ConfigService] Force sync triggered - ignoring cache');
  
  try {
    // Always fetch from remote, ignoring version check
    const remoteConfig = await fetchFromRemote(0);
    
    if (remoteConfig) {
      // Save to cache and apply immediately
      await saveToCache(remoteConfig);
      applyConfig(remoteConfig, 'remote');
      state.lastFetchedAt = new Date().toISOString();
      
      console.log('[ConfigService] Force sync successful', {
        version: remoteConfig.version
      });
      return true;
    } else {
      console.error('[ConfigService] Force sync failed - no config received');
      return false;
    }
  } catch (error) {
    console.error('[ConfigService] Force sync error:', error);
    return false;
  }
}

/**
 * Get current config state
 */
export function getConfigState(): ConfigState {
  return { ...state };
}

/**
 * Get current config (shorthand)
 */
export function getCurrentConfig(): AppConfig | null {
  return state.currentConfig;
}

/**
 * Subscribe to config changes
 */
export function subscribeToConfig(listener: (state: ConfigState) => void): () => void {
  listeners.push(listener);
  
  // Return unsubscribe function
  return () => {
    listeners = listeners.filter(l => l !== listener);
  };
}

/**
 * Force reload config from cache (useful after app resume)
 */
export async function reloadConfigFromCache(): Promise<void> {
  console.log('[ConfigService] Reloading from cache...');
  
  const cachedConfig = await loadFromCache();
  
  if (cachedConfig) {
    applyConfig(cachedConfig, 'cache');
  } else {
    console.warn('[ConfigService] No cached config, using current or default');
  }
}
