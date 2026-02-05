// ============================================
// AMPARA API - Centralized API Client
// ============================================

import { getDeviceId } from './deviceId';
import { getTimezoneInfo } from '@/utils/timezoneHelper';
import { setSessionToken as saveSessionToken, setRefreshToken as saveRefreshToken, setUserData, clearSession, getSessionToken as getToken, getUserData } from '@/services/sessionService';
import { refreshAccessToken } from '@/services/tokenRefreshService';
import {
  ApiResponse,
  LoginResponse,
  PanicActivationResponse,
  PanicCancelResponse,
  AudioUploadResponse,
  LocationUpdateResponse,
  ConfigSyncResponse,
  PingResponse,
  PanicActivationType,
  PanicCancelType,
  RecordingStatusType,
  OrigemGravacao,
  STORAGE_KEYS,
} from './types';

// API Base URL - single endpoint with action field
const API_URL = import.meta.env.VITE_API_BASE_URL || 
  'https://ilikiajeduezvvanjejz.supabase.co/functions/v1/mobile-api';

// ============================================
// Session Token Management
// ============================================

export function getSessionToken(): string | null {
  return getToken();
}

export async function setSessionToken(token: string): Promise<void> {
  await saveSessionToken(token);
}

export async function clearSessionToken(): Promise<void> {
  await clearSession();
}

export function getUserEmail(): string | null {
  const userData = getUserData();
  if (userData) {
    try {
      return JSON.parse(userData).email;
    } catch {
      return null;
    }
  }
  return null;
}

// ============================================
// Core API Request Function
// ============================================

interface MobileApiPayload {
  action: string;
  [key: string]: unknown;
}

async function mobileApi<T>(
  action: string,
  payload: Record<string, unknown> = {},
  options: { requiresAuth?: boolean } = {}
): Promise<ApiResponse<T>> {
  const { requiresAuth = true } = options;
  
  // Capturar timezone
  const timezoneInfo = getTimezoneInfo();
  
  const body: MobileApiPayload = {
    action,
    device_id: getDeviceId(),
    timezone: timezoneInfo.timezone,
    timezone_offset_minutes: timezoneInfo.timezone_offset_minutes,
    ...payload,
  };

  // Add session token and email if required
  if (requiresAuth) {
    const token = getSessionToken();
    const email = getUserEmail();
    
    const tokenPreview = token ? `${token.substring(0, 10)}...${token.substring(token.length - 10)}` : 'null';
    console.log('[API] Token check for', action, '- Has token:', !!token, '| Token preview:', tokenPreview, '| In background:', document.visibilityState === 'hidden');
    
    if (!token) {
      console.error('[API] No token available for', action);
      return { data: null, error: 'Sessão expirada. Faça login novamente.' };
    }
    body.session_token = token;
    if (email) {
      body.email_usuario = email;
    }
  }

  try {
    const response = await fetch(API_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    });

    // Handle 401 Unauthorized - try to refresh token
    if (response.status === 401 && requiresAuth) {
      console.log('[API] Received 401, attempting token refresh...');
      
      const refreshed = await refreshAccessToken();
      
      if (refreshed) {
        console.log('[API] Token refreshed, retrying request...');
        
        // Retry the request with the new token
        const newToken = getSessionToken();
        if (newToken) {
          body.session_token = newToken;
          
          const retryResponse = await fetch(API_URL, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
            },
            body: JSON.stringify(body),
          });
          
          if (!retryResponse.ok) {
            const errorData = await retryResponse.json().catch(() => ({}));
            return { 
              data: null, 
              error: errorData.error || errorData.message || `Erro ${retryResponse.status}` 
            };
          }
          
          const retryData = await retryResponse.json();
          return { data: retryData, error: null };
        }
      }
      
      // If refresh failed, return session expired error
      console.error('[API] Token refresh failed, session expired');
      
      // Dispatch global event to force logout
      window.dispatchEvent(new Event('session_expired'));
      
      return { 
        data: null, 
        error: 'Sessão expirada. Faça login novamente.',
        session_expired: true 
      } as any;
    }

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      return { 
        data: null, 
        error: errorData.error || errorData.message || `Erro ${response.status}` 
      };
    }

    const data = await response.json();
    return { data, error: null };
  } catch (error) {
    console.error('API Error:', error);
    return { data: null, error: 'Erro de conexão. Verifique sua internet.' };
  }
}

// ============================================
// Authentication Actions
// ============================================

/**
 * Login with email and password
 * Supports coercion password (silent alert)
 */
export async function loginCustomizado(
  email: string,
  senha: string
): Promise<ApiResponse<LoginResponse> & { isCoercion?: boolean }> {
  const result = await mobileApi<LoginResponse>(
    'loginCustomizado',
    { email, senha },
    { requiresAuth: false }
  );

  if (result.data) {
    // Debug: Log the entire response structure
    console.log('[API] Login response keys:', Object.keys(result.data));
    console.log('[API] Has access_token:', !!result.data.access_token);
    console.log('[API] Has refresh_token:', !!result.data.refresh_token);
    console.log('[API] Has session:', !!result.data.session);
    
    // Support both old and new response formats
    const accessToken = result.data.access_token || result.data.session?.token;
    const refreshToken = result.data.refresh_token || result.data.session?.refresh_token;
    const userData = result.data.user || result.data.usuario;
    
    console.log('[API] Extracted accessToken:', !!accessToken);
    console.log('[API] Extracted refreshToken:', !!refreshToken);
    console.log('[API] Extracted userData:', !!userData);
    
    // Store session token using session service
    if (accessToken) {
      await setSessionToken(accessToken);
      console.log('[API] Access token stored successfully');
    } else {
      console.error('[API] No access token found in response!');
    }
    
    // Store refresh token if provided by backend
    if (refreshToken) {
      await saveRefreshToken(refreshToken);
      console.log('[API] Refresh token stored successfully');
    } else {
      console.error('[API] No refresh token found in response!');
    }
    
    // Store user data
    if (userData) {
      await setUserData(JSON.stringify(userData));
    }
    
    // Store user config in localStorage (not critical for auth)
    localStorage.setItem(
      STORAGE_KEYS.USER_CONFIG,
      JSON.stringify(result.data.configuracoes)
    );

    // Check for coercion (silent - no visual feedback)
    if (result.data.coacao_detectada) {
      return { ...result, isCoercion: true };
    }
  }

  return result;
}

/**
 * Logout from the app
 */
export async function logoutMobile(): Promise<ApiResponse<{ success: boolean }>> {
  const result = await mobileApi<{ success: boolean }>('logoutMobile');
  
  // Clear local session even if API fails
  await clearSessionToken();
  localStorage.removeItem(STORAGE_KEYS.USER_CONFIG);
  
  return result;
}

// ============================================
// Panic Mode Actions
// ============================================

/**
 * Activate panic mode
 */
export async function acionarPanicoMobile(
  latitude: number,
  longitude: number,
  tipo_acionamento: PanicActivationType = 'manual'
): Promise<ApiResponse<PanicActivationResponse>> {
  return mobileApi<PanicActivationResponse>('acionarPanicoMobile', {
    localizacao: { latitude, longitude },
    tipo_acionamento,
    timestamp: new Date().toISOString(),
  });
}

/**
 * Cancel panic mode (requires password validation)
 */
export async function cancelarPanicoMobile(
  tipo_cancelamento: PanicCancelType = 'manual'
): Promise<ApiResponse<PanicCancelResponse>> {
  return mobileApi<PanicCancelResponse>('cancelarPanicoMobile', {
    tipo_cancelamento,
    timestamp: new Date().toISOString(),
  });
}

// ============================================
// Location Actions
// ============================================

/**
 * Send GPS location update
 */
export async function enviarLocalizacaoGPS(
  latitude: number,
  longitude: number
): Promise<ApiResponse<LocationUpdateResponse>> {
  const result = await mobileApi<LocationUpdateResponse>('enviarLocalizacaoGPS', {
    latitude,
    longitude,
    timestamp: new Date().toISOString(),
  });

  // Cache last known location
  if (result.data?.success) {
    localStorage.setItem(
      STORAGE_KEYS.LAST_LOCATION,
      JSON.stringify({ latitude, longitude, timestamp: new Date().toISOString() })
    );
  }

  return result;
}

// ============================================
// Recording Actions
// ============================================

/**
 * Upload audio segment (multipart/form-data)
 * Supports multiple formats: OGG (Android), MP4/M4A (iOS), WebM, MP3
 * @param audioBlob - The audio blob to upload
 * @param segmentIndex - The segment index (0-based)
 * @param durationSeconds - Duration of this segment in seconds
 * @param origemGravacao - Origin of the recording for backend routing
 */
export async function receberAudioMobile(
  audioBlob: Blob,
  segmentIndex: number,
  durationSeconds: number,
  origemGravacao: import('@/lib/types').OrigemGravacao = 'botao_manual'
): Promise<ApiResponse<AudioUploadResponse>> {
  console.log(`[receberAudioMobile] Uploading segment ${segmentIndex}, size: ${audioBlob.size} bytes, type: ${audioBlob.type}`);
  const email = getUserEmail();
  const token = getSessionToken();

  if (!token || !email) {
    return { data: null, error: 'Sessão expirada. Faça login novamente.' };
  }

  const formData = new FormData();
  formData.append('action', 'receberAudioMobile');
  formData.append('session_token', token);
  formData.append('device_id', getDeviceId());
  formData.append('email_usuario', email);
  formData.append('segment_index', segmentIndex.toString());
  formData.append('duration_seconds', durationSeconds.toString());
  formData.append('origem_gravacao', origemGravacao);
  formData.append('timestamp', new Date().toISOString());
  // Detect file extension from MIME type
  const mimeType = audioBlob.type || 'audio/wav';
  let extension = 'wav';
  
  if (mimeType.includes('mp4')) {
    extension = 'm4a'; // iOS MP4 audio
  } else if (mimeType.includes('ogg')) {
    extension = 'ogg'; // Android OGG
  } else if (mimeType.includes('webm')) {
    extension = 'webm'; // WebM fallback
  } else if (mimeType.includes('mpeg') || mimeType.includes('mp3')) {
    extension = 'mp3';
  }
  
  formData.append('audio', audioBlob, `segment_${segmentIndex}.${extension}`);
  console.log(`[receberAudioMobile] Uploading segment ${segmentIndex} as ${extension} (${mimeType})`);

  try {
    const response = await fetch(API_URL, {
      method: 'POST',
      body: formData,
    });

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      return { 
        data: null, 
        error: errorData.error || 'Falha no envio do áudio' 
      };
    }

    const data = await response.json();
    return { data, error: null };
  } catch (error) {
    console.error('Audio upload error:', error);
    return { data: null, error: 'Erro ao enviar áudio' };
  }
}

/**
 * Report recording status changes
 */
export async function reportarStatusGravacao(
  status: RecordingStatusType,
  origem?: OrigemGravacao,
  alertaId?: string,
  protocolo?: string,
  segmentoIdx?: number
): Promise<ApiResponse<{ success: boolean }>> {
  const payload: any = {
    status_gravacao: status,
    timestamp: new Date().toISOString(),
  };

  // Add optional fields if provided
  if (origem) {
    payload.origem_gravacao = origem;
  }
  
  if (alertaId) {
    payload.device_id = await getDeviceId();
    payload.alerta_id = alertaId;
  }
  
  if (protocolo) {
    payload.protocolo = protocolo;
  }
  
  if (segmentoIdx !== undefined) {
    payload.segmento_idx = segmentoIdx;
  }

  return mobileApi<{ success: boolean }>('reportarStatusGravacao', payload);
}

// ============================================
// Configuration & Heartbeat Actions
// ============================================

/**
 * Sync user configuration
 */
export async function syncConfigMobile(): Promise<ApiResponse<ConfigSyncResponse>> {
  console.log('[API] Calling syncConfigMobile...');
  
  const result = await mobileApi<any>('syncConfigMobile');
  
  console.log('[API] syncConfigMobile raw response:', JSON.stringify(result, null, 2));
  
  if (result.error || !result.data) {
    console.error('[API] syncConfigMobile error:', result.error);
    return { data: null, error: result.error || 'Failed to sync config' };
  }
  
  // Backend now returns data directly, not wrapped in 'configuracoes'
  // Transform the flat response into the expected ConfigSyncResponse format
  const configResponse: ConfigSyncResponse = {
    configuracoes: {
      contatos_suporte: result.data.contatos_rede_apoio || [],
      gatilhos: {
        voz: result.data.gravacao_ativa_config ?? true,
        manual: true
      }
    },
    dentro_horario: result.data.dentro_horario ?? false,
    gravacao_ativa: result.data.gravacao_ativa ?? false,
    periodo_atual_index: result.data.periodo_atual_index ?? null,
    gravacao_inicio: result.data.gravacao_inicio ?? null,
    gravacao_fim: result.data.gravacao_fim ?? null,
    periodos_hoje: result.data.periodos_hoje ?? [],
    gravacao_dias: result.data.gravacao_dias ?? [],
    audio_trigger_config: result.data.audio_trigger_config ?? null,
    periodos_semana: result.data.periodos_semana ?? null,
    ultima_atualizacao: new Date().toISOString()
  };
  
  // Cache the transformed config
  localStorage.setItem(
    STORAGE_KEYS.USER_CONFIG,
    JSON.stringify(configResponse.configuracoes)
  );
  
  console.log('[API] syncConfigMobile processed successfully', {
    dentro_horario: configResponse.dentro_horario,
    periodos_hoje_count: configResponse.periodos_hoje.length
  });
  
  return { data: configResponse, error: null };
}

/**
 * Ping server to maintain online status
 */
export async function pingMobile(isRecording?: boolean, isMonitoring?: boolean): Promise<ApiResponse<PingResponse>> {
  console.log('[API] pingMobile called with:', { isRecording, isMonitoring });
  
  try {
    // Import device info plugin dynamically to avoid circular dependencies
    const DeviceInfoExtended = (await import('@/plugins/deviceInfo')).default;
    console.log('[API] DeviceInfoExtended imported successfully');
    
    const deviceInfo = await DeviceInfoExtended.getExtendedInfo();
    console.log('[API] Device info retrieved:', deviceInfo);
    
    const payload = {
      bateria_percentual: deviceInfo.batteryLevel,
      is_charging: deviceInfo.isCharging,
      dispositivo_info: deviceInfo.deviceModel,
      versao_app: deviceInfo.appVersion,
      is_recording: isRecording ?? false,
      is_monitoring: isMonitoring ?? false,
      timezone: deviceInfo.timezone,
      timezone_offset_minutes: deviceInfo.timezoneOffsetMinutes,
    };
    
    console.log('[API] pingMobile FULL payload:', JSON.stringify(payload, null, 2));
    
    return mobileApi<PingResponse>('pingMobile', payload);
  } catch (error) {
    console.error('[API] ERROR in pingMobile:', error);
    console.warn('[API] Falling back to minimal payload');
    
    const fallbackPayload = {
      is_recording: isRecording ?? false,
      is_monitoring: isMonitoring ?? false,
    };
    
    console.log('[API] pingMobile FALLBACK payload:', JSON.stringify(fallbackPayload, null, 2));
    
    return mobileApi<PingResponse>('pingMobile', fallbackPayload);
  }
}

// ============================================
// Utility Functions
// ============================================

/**
 * Get cached user configuration
 */
export function getCachedConfig(): ConfigSyncResponse['configuracoes'] | null {
  const stored = localStorage.getItem(STORAGE_KEYS.USER_CONFIG);
  if (stored) {
    try {
      return JSON.parse(stored);
    } catch {
      return null;
    }
  }
  return null;
}

/**
 * Get last known location
 */
export function getLastKnownLocation(): { latitude: number; longitude: number; timestamp: string } | null {
  const stored = localStorage.getItem(STORAGE_KEYS.LAST_LOCATION);
  if (stored) {
    try {
      return JSON.parse(stored);
    } catch {
      return null;
    }
  }
  return null;
}

/**
 * Check if session is valid (basic check - doesn't verify with server)
 */
export function hasValidSession(): boolean {
  return !!getSessionToken();
}
