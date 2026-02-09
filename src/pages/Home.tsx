import React, { useState, useEffect, useRef } from 'react';
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { Capacitor } from '@capacitor/core';
import { Triangle, Menu, LogOut, X, Upload, Calendar, Wifi, WifiOff, Palette, Info, Settings } from 'lucide-react';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { Button } from '@/components/ui/button';
import { Logo } from '@/components/Logo';

import orizonLogo from '@/assets/orizon-tech-logo.png';
import amparaCircleLogo from '@/assets/ampara-circle-logo.png';
import { PanicButton } from '@/components/PanicButton';
import { RecordButton } from '@/components/RecordButton';
import { LogoutConfirmDialog } from '@/components/LogoutConfirmDialog';
import { PermissionsRequest } from '@/components/PermissionsRequest';

// MonitoringStatus is now integrated into AudioTriggerMeter
import { MonitoringPeriodsList } from '@/components/MonitoringPeriodsList';
import { AudioTriggerMeter } from '@/components/AudioTriggerMeter';
// AudioTriggerDebugPanel removed - AudioTrigger now starts automatically
import { usePanicContext } from '@/contexts/PanicContext';
import { useRecording } from '@/hooks/useRecording';
import { useAppState } from '@/hooks/useAppState';
import { useToast } from '@/hooks/use-toast';
import { useAudioTriggerSingleton } from '@/hooks/useAudioTriggerSingleton';
// import { useStealthNotification } from '@/hooks/useStealthNotification'; // REMOVED: useBackgroundServices already manages ForegroundService
import { usePermissions } from '@/hooks/usePermissions';
import { useBackgroundServices } from '@/hooks/useBackgroundServices';
import { useHeartbeat } from '@/hooks/useHeartbeat';
import { hybridAudioTrigger } from '@/services/hybridAudioTriggerService';
import { getSessionToken, getRefreshToken, getUserData } from '@/services/sessionService';
import { audioTriggerSingleton } from '@/services/audioTriggerSingleton';
import { getMonitoringGateStatus } from '@/services/monitoringGateService';

interface HomePageProps {
  onLogout: () => void;
}

export function HomePage({ onLogout }: HomePageProps) {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [menuOpen, setMenuOpen] = useState(false);
  const [logoutDialogOpen, setLogoutDialogOpen] = useState(false);
  const [isStopping, setIsStopping] = useState(false);
  const [, forceUpdate] = useState({});
  
  // Permissions check
  const { permissions, isLoading: isPermissionsLoading, hasAllRequired, requestAll } = usePermissions();
  
  // Background services (connectivity + config)
  const { connectivity, config, isInitialized } = useBackgroundServices();
  
  const appState = useAppState();
  const panic = usePanicContext();
  const recording = useRecording();
  
  // Extract monitoring config from the new config service
  // Ensure monitoring_periods is a valid array with proper structure
  const rawPeriods = config.currentConfig?.monitoring_periods || [];
  const validPeriods = Array.isArray(rawPeriods) 
    ? rawPeriods.filter(p => p && typeof p.inicio === 'string' && typeof p.fim === 'string')
    : [];
  
  // Calculate monitoring status locally
  const monitoringStatus = getMonitoringGateStatus(new Date(), validPeriods);
  
  const monitoring = {
    dentroHorario: monitoringStatus.isWithinPeriod,
    periodoAtualIndex: monitoringStatus.currentPeriodIndex,
    periodosHoje: validPeriods
  };
  
  // REMOVED: audioTriggerConfig from API (use local defaults only)
  const isConfigLoading = config.isLoading;
  const periodosSemana = config.currentConfig?.periodos_semana ?? null;
  
  const audioTrigger = useAudioTriggerSingleton();
  
  // Heartbeat - ping server every 30 seconds
  // Pass recording status to heartbeat
  useHeartbeat({ 
    autoStart: true, 
    interval: 30000,
    isRecording: recording.isRecording,
    isMonitoring: audioTrigger.isCapturing
  });
  
  // REMOVED: Update config from server (use local defaults only)
  // audioTrigger.updateConfig() no longer called
  
  // Stealth notification - shows "Bem-estar Ativo" when monitoring is active
  // REMOVED: useBackgroundServices already manages ForegroundService
  // useStealthNotification(audioTrigger.isCapturing);
  
  // Ref to track if we auto-started the recording (to avoid duplicate toasts)
  const autoRecordingStartedRef = useRef(false);
  // Ref to track if audio was started manually via debug panel
  const manualAudioStartRef = useRef(false);

  const handleManualAudioStart = () => {
    manualAudioStartRef.current = true;
  };

  const handleManualAudioStop = () => {
    manualAudioStartRef.current = false;
  };

  // Auto-request native permissions on mount
  useEffect(() => {
    if (!hasAllRequired && !isPermissionsLoading) {
      console.log('[Home] Requesting native permissions...');
      requestAll();
    }
  }, [hasAllRequired, isPermissionsLoading, requestAll]);

  // Phase 1: Initialize HybridAudioTrigger (once)
  useEffect(() => {
    console.log('[Home] Initializing HybridAudioTrigger...');
    hybridAudioTrigger.init();
  }, []);

  // Phase 3: Register JavaScript callbacks (optional, for UI updates only)
  // Native-first architecture: NATIVE service handles all audio processing
  useEffect(() => {
    console.log('[Home] Registering JavaScript callbacks (optional, for UI)...');
    hybridAudioTrigger.registerJavaScriptCallbacks({
      onStateChange: (mode) => {
        console.log('[Home] HybridAudioTrigger state changed:', mode);
        forceUpdate({});
      },
      onDebug: (data) => {
        console.log('[Home] HybridAudioTrigger debug:', data);
      },
    });
  }, []);

  // Listen to native audio trigger events (discussion detected in background)
  useEffect(() => {
    const handleNativeEvent = (event: { event: string; reason?: string; sessionId?: string; segmentIndex?: number; isCalibrated?: boolean }) => {
      console.log('[Home] Native audio trigger event:', event);
      
      if (event.event === 'discussionDetected') {
        // Native recording is handled automatically by the service
        // Just show notification to user
        console.log('[Home] Native discussion detected - native recording started automatically');
        toast({
          title: 'Discussão detectada',
          description: 'Gravação automática em andamento',
          duration: 3000,
        });
      }
      
      if (event.event === 'nativeRecordingStarted') {
        console.log('[Home] Native recording started:', event.sessionId);
        appState.setStatus('recording');
        setIsStopping(false);
      }
      
      if (event.event === 'recordingStopping') {
        console.log('[Home] Recording is stopping - uploading final segment...');
        setIsStopping(true);
      }
      
      if (event.event === 'recordingStopped') {
        console.log('[Home] Recording stopped - final segment uploaded');
        setIsStopping(false);
      }
      
      if (event.event === 'nativeRecordingStopped') {
        console.log('[Home] Native recording stopped:', event.sessionId);
        appState.setStatus('idle');
        setIsStopping(false);
        toast({
          title: 'Gravação finalizada',
          description: 'Áudio enviado com sucesso',
          duration: 3000,
        });
      }
      
      if (event.event === 'calibrationStatus') {
        console.log('[Home] Calibration status changed:', event.isCalibrated);
        // State is already managed in audioTrigger.isCalibrated, no need for local state
      }
      
      if (event.event === 'audioMetrics') {
        // Update audioTriggerSingleton with native metrics (for UI updates)
        try {
          audioTriggerSingleton.setNativeMetrics(event);
        } catch (error) {
          console.error('[Home] Error updating native metrics:', error);
        }
      }
      
      // Debug events
      if (event.event === 'debugStartCalled') {
        toast({
          title: '🔴 DEBUG: start() chamado!',
          description: 'O método start() foi executado no iOS',
          duration: 5000,
        });
      }
      
      if (event.event === 'debugPermissionGranted') {
        toast({
          title: '🟢 DEBUG: Permissão OK',
          description: 'Microfone autorizado',
          duration: 5000,
        });
      }
      
      if (event.event === 'debugMonitoringStarted') {
        toast({
          title: '✅ DEBUG: Monitoramento iniciado!',
          description: 'startMonitoring() executado com sucesso',
          duration: 5000,
        });
      }
    };
    
    hybridAudioTrigger.addListener(handleNativeEvent);
    
    return () => {
      hybridAudioTrigger.removeListener(handleNativeEvent);
    };
  }, [monitoring.dentroHorario, panic.isPanicActive, toast, appState]);

  // REMOVED: setNativeConfig (use local defaults only)
  // Native thresholds are now hardcoded in AudioTriggerDefaults.kt

  // Phase 4: Auto-start audio monitoring on login (keeps app alive 24/7)
  // Only stops on logout
  useEffect(() => {
    console.log('\n\n\n');
    console.log('🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵');
    console.log('🔵 [Home] useEffect AUTO-START executando!');
    console.log('🔵 isCapturing=', audioTrigger.isCapturing);
    console.log('🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵🔵');
    console.log('\n\n\n');
    
    // REMOVED: hasAllRequired check - let hybridAudioTrigger.start() handle permissions internally
    
    // Only start if not already capturing (prevents restart on navigation)
    if (audioTrigger.isCapturing) {
      console.log('[Home] ✅ Audio trigger already capturing, skipping auto-start');
      return;
    }
    
    // HybridAudioTrigger will handle:
    // - Permission flow gates
    // - Callback validation
    // - Foreground/background transitions
    console.log('[Home] 🟢 Phase 4: Auto-starting hybrid audio trigger...');
    const timer = setTimeout(() => {
    console.log('\n\n\n');
    console.log('🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢');
    console.log('🜢 CHAMANDO hybridAudioTrigger.start() AGORA!');
    console.log('🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢🜢');
    console.log('\n\n\n');
      
      // Get credentials from session
      const sessionToken = getSessionToken();
      const refreshToken = getRefreshToken();
      const userData = getUserData();
      const emailUsuario = userData ? JSON.parse(userData).email : null;
      
      console.log('[Home] 🔑 Credentials:', { 
        hasSessionToken: !!sessionToken, 
        hasRefreshToken: !!refreshToken, 
        emailUsuario
      });
      
      // Pass credentials to native (iOS generates and manages device_id internally)
      const config = {
        sessionToken,
        refreshToken,
        emailUsuario
      };
      
      hybridAudioTrigger.start(config).then(() => {
        console.log('[Home] ✅ hybridAudioTrigger.start() completed successfully');
      }).catch(err => {
        console.error('[Home] Failed to auto-start hybrid audio trigger:', err);
        toast({
          title: "Erro ao iniciar monitoramento",
          description: "Reinicie o app para tentar novamente.",
          variant: "destructive"
        });
      });
    }, 500);
    return () => clearTimeout(timer);
  }, [toast, audioTrigger.isCapturing]);

  // Periodic check for monitoring period changes (every minute)
  // This ensures the app switches modes automatically when entering/exiting periods
  useEffect(() => {
    const checkPeriod = () => {
      const newStatus = getMonitoringGateStatus(new Date(), validPeriods);
      const wasWithinPeriod = monitoring.dentroHorario;
      const isWithinPeriod = newStatus.isWithinPeriod;
      
      if (wasWithinPeriod !== isWithinPeriod) {
        console.log('[Home] Period status changed:', { wasWithinPeriod, isWithinPeriod });
        // Force re-render without losing state
        forceUpdate({});
      }
    };
    
    // Check every minute
    const interval = setInterval(checkPeriod, 60000);
    return () => clearInterval(interval);
  }, [monitoring.dentroHorario, validPeriods]);

  // Switch AudioTrigger processing mode based on monitoring period
  // FULL mode: inside monitoring period (full analysis)
  // LIGHT mode: outside monitoring period (minimal processing for battery saving)
  useEffect(() => {
    if (!audioTrigger.isCapturing) return;
    
    const newMode = monitoring.dentroHorario ? 'FULL' : 'LIGHT';
    
    if (audioTrigger.config.processingMode !== newMode) {
      console.log('[Home] Switching AudioTrigger mode:', newMode, '| dentroHorario:', monitoring.dentroHorario);
      audioTrigger.setProcessingMode(newMode);
    }
  }, [
    hasAllRequired,
    monitoring.dentroHorario, 
    audioTrigger.isCapturing,
    audioTrigger.start,
    audioTrigger.stop,
    toast,
    manualAudioStartRef.current,
  ]);

  // Auto-start recording when discussion is detected
  // IMPORTANT: Only auto-record if WITHIN monitoring period (monitoring gate)
  useEffect(() => {
    const shouldAutoRecord = 
      audioTrigger.discussionOn && 
      monitoring.dentroHorario && // <-- MONITORING GATE CHECK
      !recording.isRecording && 
      !panic.isPanicActive &&
      !autoRecordingStartedRef.current;

    if (shouldAutoRecord) {
      autoRecordingStartedRef.current = true;
      recording.startRecording('automatico').then(success => {
        if (success) {
          appState.setStatus('recording');
          toast({
            title: 'Gravação automática iniciada',
            description: 'Discussão detectada pelo monitoramento.',
          });
        } else {
          autoRecordingStartedRef.current = false;
        }
      });
    }
    
    // Reset the ref when discussion ends and recording stops
    if (!audioTrigger.discussionOn && !recording.isRecording) {
      autoRecordingStartedRef.current = false;
    }
  }, [audioTrigger.discussionOn, monitoring.dentroHorario, recording.isRecording, panic.isPanicActive, appState, toast]);

  const [isRecordLoading, setIsRecordLoading] = useState(false);

  const handleRecordToggle = async () => {
    if (isRecordLoading) return; // Prevent multiple clicks
    
    setIsRecordLoading(true);
    try {
      if (recording.isRecording) {
        await recording.stopRecording();
        appState.setStatus('normal');
        toast({
          title: 'Gravação encerrada',
          description: `${recording.segmentsSent} segmentos enviados.`,
        });
      } else {
        const success = await recording.startRecording();
        if (success) {
          appState.setStatus('recording');
          toast({
            title: 'Gravação iniciada',
            description: 'O áudio está sendo enviado em tempo real.',
          });
        } else {
          toast({
            title: 'Erro ao iniciar gravação',
            description: 'Verifique as permissões do microfone.',
            variant: 'destructive',
          });
        }
      }
    } finally {
      setIsRecordLoading(false);
    }
  };

  const handlePanicStart = () => {
    panic.startHold();
  };

  const handlePanicEnd = () => {
    panic.cancelHold();
  };

  const handleLogoutRequest = () => {
    setMenuOpen(false);
    setLogoutDialogOpen(true);
  };

  const handleLogoutConfirm = async () => {
    // Note: onLogout in App.tsx now handles clearing all storage via Preferences
    setLogoutDialogOpen(false);
    onLogout();
  };

  // Show permissions request screen if permissions are not granted
  // Desativado temporariamente para permitir pop-ups nativos diretos
  /*
  if (!isPermissionsLoading && !hasAllRequired) {
    return (
      <PermissionsRequest
        permissions={permissions}
        onRequestAll={requestAll}
      />
    );
  }
  */

  return (
    <div className="min-h-screen flex flex-col bg-background relative overflow-hidden">
      {/* Background watermark logo */}
      <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
        <img 
          src={amparaCircleLogo} 
          alt="" 
          className="w-[3200px] h-[3200px] object-contain opacity-20"
        />
      </div>
      {/* Header */}
      <header className="flex items-center justify-between px-4 pb-4 bg-background" style={{ paddingTop: 'calc(env(safe-area-inset-top) + 2rem)' }}>
        <Logo size="sm" />
        <div className="flex items-center gap-2">
          {/* Connectivity indicator */}
          <Tooltip>
            <TooltipTrigger asChild>
              <div className="flex items-center gap-1.5">
                {connectivity.isOnline ? (
                  <Wifi className="w-4 h-4 text-success" />
                ) : (
                  <WifiOff className="w-4 h-4 text-destructive" />
                )}
              </div>
            </TooltipTrigger>
            <TooltipContent>
              <p>
                {connectivity.isOnline 
                  ? `Online - Última resposta: ${connectivity.lastLatencyMs}ms` 
                  : 'Offline - Sem conexão com o servidor'}
              </p>
              {connectivity.lastSuccessfulPing && (
                <p className="text-xs text-muted-foreground mt-1">
                  Último ping: {new Date(connectivity.lastSuccessfulPing).toLocaleTimeString()}
                </p>
              )}
            </TooltipContent>
          </Tooltip>

          {/* Upload file button */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                onClick={() => navigate('/upload')}
              >
                <Upload className="w-5 h-5" />
              </Button>
            </TooltipTrigger>
            <TooltipContent className="max-w-[200px] text-center">
              <p>Enviar arquivo de áudio gravado fora do app para incluir no seu perfil e análise</p>
            </TooltipContent>
          </Tooltip>

          {/* Pending uploads badge */}
          {appState.pendingUploads > 0 && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => navigate('/pending')}
                  className="relative"
                >
                  <Triangle className="w-5 h-5 text-amber-400 fill-amber-400" />
                  <span className="absolute -top-1 -right-1 w-5 h-5 bg-warning text-warning-foreground text-xs rounded-full flex items-center justify-center">
                    {appState.pendingUploads}
                  </span>
                </Button>
              </TooltipTrigger>
              <TooltipContent>
                <p>Uploads pendentes</p>
              </TooltipContent>
            </Tooltip>
          )}
          
          {/* Menu button */}
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setMenuOpen(true)}
          >
            <Menu className="w-5 h-5" />
          </Button>
        </div>
      </header>

      {/* Main content */}
      <main className="flex-1 flex flex-col items-center p-6">
        
        {/* Top section: Audio meter with integrated monitoring status */}
        {!panic.isPanicActive && (
          <div className="w-full max-w-sm flex flex-col items-center pt-4 mb-auto">
            {/* AudioTriggerDebugPanel removed - AudioTrigger starts automatically on login */}
            <AudioTriggerMeter 
              score={audioTrigger.metrics?.score ?? 0}
              isCapturing={audioTrigger.isCapturing}
              state={audioTrigger.state}
              isRecording={recording.isRecording}
              recordingDuration={recording.duration}
              recordingOrigin={recording.origemGravacao}
              dentroHorario={monitoring.dentroHorario}
              periodoAtualIndex={monitoring.periodoAtualIndex}
              periodosHoje={monitoring.periodosHoje}
              periodosSemana={periodosSemana}
              isCalibrated={audioTrigger.isCalibrated}
              isNoisy={audioTrigger.metrics?.isNoisy ?? false}
              isLoading={isConfigLoading}
              triggerMode={hybridAudioTrigger.getMode()}
            />
          </div>
        )}

        {/* Center section: Panic button + Record button */}
        <div className="flex-1 flex flex-col items-center justify-center gap-6 min-h-[280px]">
          {!panic.isPanicActive ? (
            <>
              <motion.div
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={{ delay: 0.2 }}
              >
                <PanicButton
                  onHoldStart={handlePanicStart}
                  onHoldEnd={handlePanicEnd}
                  isActivating={panic.isActivating}
                  disabled={recording.isRecording || panic.isSendingToServer}
                  isLoading={panic.isSendingToServer}
                />
              </motion.div>
              
              {/* Recording button - Always reserve space, hide with opacity during panic */}
              <motion.div
                initial={{ opacity: 0, y: 10 }}
                animate={{ 
                  opacity: (!panic.isActivating && !panic.isSendingToServer) ? 1 : 0,
                  y: 0 
                }}
                transition={{ delay: 0.3 }}
                className="h-[80px] flex items-center justify-center"
              >
                {(!panic.isActivating && !panic.isSendingToServer) && (
                  <RecordButton
                    onClick={handleRecordToggle}
                    isRecording={recording.isRecording}
                    disabled={false}
                    isLoading={isRecordLoading}
                    isStopping={isStopping}
                  />
                )}
              </motion.div>
            </>
          ) : (
            <motion.div
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              className="flex flex-col items-center gap-6"
            >
              <motion.div
                animate={{ 
                  scale: [1, 1.03, 1], 
                  opacity: [1, 0.8, 1] 
                }}
                transition={{ 
                  duration: 1.5, 
                  repeat: Infinity, 
                  ease: "easeInOut" 
                }}
                className="text-6xl font-bold text-destructive"
              >
                {formatDuration(panic.panicDuration)}
              </motion.div>
              
              <motion.button
                onClick={() => navigate('/panic-active')}
                className={`
                  w-32 h-32 rounded-full bg-gradient-safe 
                  flex flex-col items-center justify-center 
                  ${panic.canCancel() ? 'pulse-safe' : 'opacity-50'}
                `}
                whileTap={panic.canCancel() ? { scale: 0.95 } : {}}
              >
                {panic.canCancel() ? (
                  <>
                    <span className="text-xl font-bold text-white">Cancelar</span>
                    <span className="text-[10px] text-white/80 mt-1">Agora estou segura</span>
                  </>
                ) : (
                  <span className="text-sm font-bold text-white">Aguarde 5s...</span>
                )}
              </motion.button>
            </motion.div>
          )}
        </div>
      </main>

      {/* Powered by footer */}
      <footer className="py-4 px-4 flex items-center justify-center pb-[calc(1rem+env(safe-area-inset-bottom))]">
        <div className="flex flex-col items-center gap-0.5">
          <span className="text-[6px] text-muted-foreground/60">powered by</span>
          <img src={orizonLogo} alt="Orizon Tech" className="h-8 object-contain mix-blend-multiply opacity-70" />
        </div>
      </footer>


      {/* Side menu */}
      {menuOpen && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 bg-black/50 z-50"
          onClick={() => setMenuOpen(false)}
        >
          <motion.div
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', damping: 25 }}
            className="absolute right-0 top-0 bottom-0 w-72 bg-card border-l border-border"
            style={{ 
              paddingTop: 'env(safe-area-inset-top)',
              paddingBottom: 'env(safe-area-inset-bottom)',
              paddingLeft: '1.5rem',
              paddingRight: '1.5rem'
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-8">
              <h2 className="text-lg font-semibold">Menu</h2>
              <Button variant="ghost" size="icon" onClick={() => setMenuOpen(false)}>
                <X className="w-5 h-5" />
              </Button>
            </div>

            <nav className="space-y-2">
              <Button
                variant="ghost"
                className="w-full justify-start gap-3"
                onClick={() => {
                  navigate('/pending');
                  setMenuOpen(false);
                }}
              >
                <Triangle className="w-5 h-5 text-amber-400 fill-amber-400" />
                Pendências
                {appState.pendingUploads > 0 && (
                  <span className="ml-auto bg-warning text-warning-foreground text-xs px-2 py-0.5 rounded-full">
                    {appState.pendingUploads}
                  </span>
                )}
              </Button>

              <Button
                variant="ghost"
                className="w-full justify-start gap-3"
                onClick={() => {
                  navigate('/upload');
                  setMenuOpen(false);
                }}
              >
                <Upload className="w-5 h-5" />
                Enviar arquivo
              </Button>

              {/* Hide icon changer on iOS (not supported) */}
              {Capacitor.getPlatform() !== 'ios' && (
                <Button
                  variant="ghost"
                  className="w-full justify-start gap-3"
                  onClick={() => {
                    navigate('/icon-selector');
                    setMenuOpen(false);
                  }}
                >
                  <Palette className="w-5 h-5" />
                  Alterar ícone do app
                </Button>
              )}

              <Button
                variant="ghost"
                className="w-full justify-start gap-3"
                onClick={() => {
                  navigate('/settings');
                  setMenuOpen(false);
                }}
              >
                <Settings className="w-5 h-5" />
                Configurações
              </Button>

              <Button
                variant="ghost"
                className="w-full justify-start gap-3"
                onClick={() => {
                  navigate('/about');
                  setMenuOpen(false);
                }}
              >
                <Info className="w-5 h-5" />
                Sobre
              </Button>

              <div className="pt-4 border-t border-border mt-4">
                <Button
                  variant="ghost"
                  className="w-full justify-start gap-3 text-destructive hover:text-destructive"
                  onClick={handleLogoutRequest}
                >
                  <LogOut className="w-5 h-5" />
                  Sair
                </Button>
              </div>
            </nav>
          </motion.div>
        </motion.div>
      )}

      {/* Logout confirmation dialog */}
      <LogoutConfirmDialog
        isOpen={logoutDialogOpen}
        onClose={() => setLogoutDialogOpen(false)}
        onConfirm={handleLogoutConfirm}
      />
    </div>
  );
}

function formatDuration(seconds: number): string {
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
}
