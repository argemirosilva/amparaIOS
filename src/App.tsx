import { useState, useEffect, useCallback } from "react";
import { Toaster } from "@/components/ui/toaster";
import { Toaster as Sonner } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { LoginPage } from "./pages/Login";
import { SplashPage } from "./pages/Splash";
import { HomePage } from "./pages/Home";

import { Preferences } from '@capacitor/preferences';
import { initializeSession, isAuthenticated, reloadSession, clearSession } from '@/services/sessionService';
import { initializeConfigService } from '@/services/configService';

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
import NotFound from "./pages/NotFound";
import { PanicProvider } from "./contexts/PanicContext";
import { PermissionGuard } from "./components/PermissionGuard";
import { LocationPermissionRequest } from "./components/LocationPermissionRequest";
import { Capacitor } from '@capacitor/core';
import KeepAlive from '@/plugins/keepAlive';
import { AudioTriggerNative } from '@/plugins/audioTriggerNative';
import { getDeviceId } from '@/lib/deviceId';
import { checkPermissions } from '@/services/permissionsService';

const queryClient = new QueryClient();

const App = () => {
  // Start with null to indicate "loading" state
  const [authState, setAuthState] = useState<boolean | null>(null);
  const [servicesInitialized, setServicesInitialized] = useState(false);
  const [showSplash, setShowSplash] = useState(true);


  // Initialize session service and check auth
  useEffect(() => {
    const initAuth = async () => {
      try {
        console.log('[App] Initializing session service...');
        

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
    const handleSessionExpired = () => {
      console.log('[App] Session expired event received, forcing logout');
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
        <PermissionGuard>
          <BrowserRouter>
            {showSplash ? (
              <SplashPage onComplete={() => setShowSplash(false)} />
            ) : !authState ? (
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
                <Route path="*" element={<NotFound />} />
              </Routes>
            </PanicProvider>
          )}
          </BrowserRouter>
        </PermissionGuard>
      </TooltipProvider>
    </QueryClientProvider>
  );
};

export default App;
