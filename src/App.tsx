import { useState, useEffect, useCallback } from "react";
import { Toaster } from "@/components/ui/toaster";
import { Toaster as Sonner } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { LoginPage } from "./pages/Login";

import { HomePage } from "./pages/Home";

import { Preferences } from '@capacitor/preferences';
import { initializeSession, isAuthenticated, reloadSession, clearSession } from '@/services/sessionService';
import { initializeConfigService, forceSyncConfig } from '@/services/configService';

import { initializeBackgroundStateManager } from '@/services/backgroundStateManager';
import SessionExpiredListener from '@/plugins/sessionExpiredListener';
import { PluginListenerHandle } from '@capacitor/core';
import { PanicActivePage } from "./pages/PanicActive";
import { RecordingPage } from "./pages/Recording";
import { PendingPage } from "./pages/Pending";
import { UploadPage } from "./pages/Upload";

import { AudioTriggerDebugPage } from "./pages/AudioTriggerDebug";
import IconSelector from "./pages/IconSelector";
import AboutPage from "./pages/About";
import SettingsPage from "./pages/Settings";
import PhraseEnrollmentPage from "./pages/PhraseEnrollment";
import NotFound from "./pages/NotFound";
import { PanicProvider } from "./contexts/PanicContext";

import { LocationPermissionRequest } from "./components/LocationPermissionRequest";
import { Capacitor } from '@capacitor/core';
import KeepAlive from '@/plugins/keepAlive';
import { AudioTriggerNative } from '@/plugins/audioTriggerNative';
import { getDeviceId, initializeDeviceId } from '@/lib/deviceId';
import { checkPermissions } from '@/services/permissionsService';

const queryClient = new QueryClient();

const App = () => {
  // Start with null to indicate "loading" state
  const [authState, setAuthState] = useState<boolean | null>(null);
  const [servicesInitialized, setServicesInitialized] = useState(false);



  // Initialize session service and check auth
  useEffect(() => {
    const initAuth = async () => {
      try {
        console.log('[App] Initializing session service...');

        // Initialize device ID from native platform (iOS Keychain sync)
        await initializeDeviceId();

        // Initialize the session service (loads from native storage)
        await initializeSession();

        // Initialize background state manager (monitors app visibility)
        initializeBackgroundStateManager();

        // Check if authenticated
        const authenticated = isAuthenticated();
        console.log('[App] Authentication status:', authenticated);

        // Persist session - don't validate token on app open
        // Token will be validated when making API calls
        // If token is invalid, API will return 401 and force logout
        setAuthState(authenticated);
      } catch (e) {
        console.error('[App] Session initialization failed:', e);
        setAuthState(false);
      }
    };

    initAuth();
  }, []);

  // Initialize background services after authentication
  useEffect(() => {
    if (authState === true && !servicesInitialized) {
      console.log('[App] User authenticated, initializing background services...');

      const initServices = async () => {
        try {
          // Initialize config service (loads from cache immediately)
          await initializeConfigService();
          console.log('[App][SYNC_CONFIG] ######## Forcing JS config sync after initializeConfigService ########');
          const forcedSyncOk = await forceSyncConfig();
          console.log('[App][SYNC_CONFIG] ######## forceSyncConfig startup result ########', forcedSyncOk ? 'SUCCESS' : 'FAILED');

          // Start KeepAlive service if not already running (Android only)
          if (Capacitor.getPlatform() === 'android') {
            try {
              // Check all required permissions before starting service
              console.log('[App] 🔍 Checking permissions before starting KeepAlive...');
              const permissions = await checkPermissions();
              console.log('[App] Permission status:', permissions);

              // Only start KeepAlive if location permission is granted
              // (KeepAliveService uses FOREGROUND_SERVICE_TYPE_LOCATION)
              if (permissions.location === 'granted') {
                console.log('[App] 🚀 Starting KeepAlive service...');
                const deviceId = getDeviceId();
                await KeepAlive.start({ deviceId });
                console.log('[App] ✅ KeepAlive service started successfully');
              } else {
                console.warn('[App] ⚠️ Location permission not granted, skipping KeepAlive start');
                console.warn('[App] KeepAlive will be started after user grants permissions');
              }
            } catch (error) {
              console.error('[App] ❌ Error starting KeepAlive service:', error);
            }
          }

          setServicesInitialized(true);
          console.log('[App] Background services initialized');
        } catch (error) {
          console.error('[App] Failed to initialize background services:', error);
        }
      };

      initServices();
    } else if (authState === false && servicesInitialized) {
      // User logged out, stop services
      console.log('[App] User logged out, stopping background services...');

      const stopServices = async () => {
        try {
          if (Capacitor.getPlatform() === 'android') {
            // Stop KeepAlive service (Android only)
            console.log('[App] Stopping KeepAlive service...');
            await KeepAlive.stop();
            console.log('[App] KeepAlive service stopped');
          } else if (Capacitor.getPlatform() === 'ios') {
            // Stop native audio monitoring (iOS) - releases microphone and AVAudioSession
            console.log('[App] Stopping AudioTriggerNative (iOS)...');
            await AudioTriggerNative.stop();
            console.log('[App] AudioTriggerNative stopped, microphone released');
          }

          setServicesInitialized(false);
        } catch (error) {
          console.error('[App] Error stopping services:', error);
          setServicesInitialized(false);
        }
      };

      stopServices();
    }
  }, [authState, servicesInitialized]);

  // Reload session when app becomes visible (Android lifecycle)
  useEffect(() => {
    const handleVisibilityChange = async () => {
      if (document.visibilityState === 'visible') {
        console.log('[App] App became visible, reloading session...');
        const authenticated = await reloadSession();
        console.log('[App] Reload result:', authenticated, 'Current state:', authState);

        // Always update state to force re-render, even if value seems the same
        // This handles cases where Android killed and restarted the WebView
        console.log('[App] Force updating auth state to:', authenticated);
        setAuthState(authenticated);
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
  }, []);



  // Listen for session expiration from Native (KeepAliveService)
  useEffect(() => {
    let nativeListener: PluginListenerHandle | null = null;

    const setupNativeListener = async () => {
      try {
        nativeListener = await SessionExpiredListener.addListener('sessionExpired', async (data) => {
          console.error('[App] Session expired event from Native:', data);

          // Try to refresh token first
          console.log('[App] Attempting to refresh token...');
          const { refreshAccessToken } = await import('@/services/tokenRefreshService');
          const refreshed = await refreshAccessToken();

          if (refreshed) {
            console.log('[App] Token refreshed successfully, session restored');
          } else {
            console.error('[App] Token refresh failed, logging out');
            await handleLogout();
          }
        });
        console.log('[App] Native session expired listener registered');
      } catch (error) {
        console.error('[App] Failed to register native session expired listener:', error);
      }
    };

    setupNativeListener();

    return () => {
      if (nativeListener) {
        nativeListener.remove();
      }
    };
  }, []);

  // Listen for token refresh from Native (AudioTriggerNative)
  useEffect(() => {
    let tokenListener: PluginListenerHandle | null = null;

    const setupTokenListener = async () => {
      try {
        tokenListener = await AudioTriggerNative.addListener('audioTriggerEvent', async (event: any) => {
          // Force logout immediately on device mismatch
          if (event.event === 'sessionExpired' && event.reason === 'device_mismatch') {
            console.error('[App] Device mismatch detected from Native, forcing logout');
            await clearSession();
            setAuthState(false);
            return;
          }

          // Check if this is a tokensRefreshed event
          if (event.event === 'tokensRefreshed') {
            console.log('[App] Tokens refreshed by Native, updating session service...');

            const { setSessionToken, setRefreshToken } = await import('@/services/sessionService');

            if (event.access_token) {
              await setSessionToken(event.access_token);
              console.log('[App] Access token updated in session service');
            }

            if (event.refresh_token) {
              await setRefreshToken(event.refresh_token);
              console.log('[App] Refresh token updated in session service');
            }

            console.log('[App] Session tokens synchronized with Native');
          }

          // Native iOS synced config (including periods) - refresh JS config state/UI.
          if (event.event === 'configUpdated') {
            console.log('[App] Native configUpdated received - forcing JS config sync...');
            const { forceSyncConfig } = await import('@/services/configService');
            const ok = await forceSyncConfig();
            console.log('[App] forceSyncConfig after native configUpdated:', ok ? 'SUCCESS' : 'FAILED');
          }
        });
        console.log('[App] Native token refresh listener registered');
      } catch (error) {
        console.error('[App] Failed to register native token refresh listener:', error);
      }
    };

    setupTokenListener();

    return () => {
      if (tokenListener) {
        tokenListener.remove();
      }
    };
  }, []);

  const handleLoginSuccess = async () => {
    console.log('[App] Login success, updating auth state');
    setAuthState(true);
    // KeepAlive will be started by useEffect when authState changes
  };

  const handleLogout = useCallback(async () => {
    console.log('[App] Logout requested, clearing session');
    await clearSession();
    setAuthState(false);
  }, []);

  // Listen for session expired events (from API calls)
  useEffect(() => {
    const handleSessionExpired = (event: Event) => {
      const reason = (event as CustomEvent)?.detail?.reason;
      console.log('[App] Session expired event received, forcing logout', reason ? `| reason: ${reason}` : '');
      handleLogout();
    };

    // Listen for custom event dispatched by API
    window.addEventListener('session_expired', handleSessionExpired);

    return () => {
      window.removeEventListener('session_expired', handleSessionExpired);
    };
  }, [handleLogout]);

  // Loading state while checking auth
  if (authState === null) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background">
        <div className="w-8 h-8 border-2 border-primary border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }



  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <Toaster />
        <Sonner />
        <BrowserRouter>
          {!authState ? (
            <Routes>
              <Route path="/" element={<LoginPage onLoginSuccess={handleLoginSuccess} />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          ) : (
            <PanicProvider>
              <LocationPermissionRequest />
              <Routes>
                <Route path="/" element={<HomePage onLogout={handleLogout} />} />
                <Route path="/panic-active" element={<PanicActivePage />} />
                <Route path="/recording" element={<RecordingPage />} />
                <Route path="/pending" element={<PendingPage />} />
                <Route path="/upload" element={<UploadPage />} />

                <Route path="/audio-trigger-debug" element={<AudioTriggerDebugPage />} />
                <Route path="/icon-selector" element={<IconSelector />} />
                <Route path="/about" element={<AboutPage />} />
                <Route path="/settings" element={<SettingsPage />} />
                <Route path="/phrase-enrollment" element={<PhraseEnrollmentPage />} />
                <Route path="*" element={<NotFound />} />
              </Routes>
            </PanicProvider>
          )}
        </BrowserRouter>
      </TooltipProvider>
    </QueryClientProvider>
  );
};

export default App;
