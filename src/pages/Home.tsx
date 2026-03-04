import React, { useState, useEffect, useRef } from 'react';
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { Triangle, Menu, LogOut, X, Upload, Wifi, WifiOff, Info, Settings, Mic, Square } from 'lucide-react';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { Button } from '@/components/ui/button';
import { PanicButton } from '@/components/PanicButton';

import { RecordButton } from '@/components/RecordButton';
import { LogoutConfirmDialog } from '@/components/LogoutConfirmDialog';
import { PasswordValidationDialog } from '@/components/PasswordValidationDialog';
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
import { hybridAudioTrigger } from '@/services/hybridAudioTriggerService';
import { audioTriggerSingleton } from '@/services/audioTriggerSingleton';
import { getMonitoringGateStatus } from '@/services/monitoringGateService';
import { getSessionToken, getUserEmail } from '@/lib/api';
import { getRefreshToken } from '@/services/sessionService';
import { Capacitor } from '@capacitor/core';

interface HomePageProps {
  onLogout: () => void;
}

export function HomePage({ onLogout }: HomePageProps) {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [menuOpen, setMenuOpen] = useState(false);
  const [logoutDialogOpen, setLogoutDialogOpen] = useState(false);
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
      }

      if (event.event === 'nativeRecordingStopped') {
        console.log('[Home] Native recording stopped:', event.sessionId);
        appState.setStatus('normal');

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
    if (isPermissionsLoading) {
      console.log('[Home] Permissions still loading, postponing auto-start');
      return;
    }

    // Only start if not already capturing (prevents restart on navigation)
    if (audioTrigger.isCapturing) {
      console.log('[Home] Audio trigger already capturing, skipping auto-start');
      return;
    }

    // HybridAudioTrigger will handle:
    // - Permission flow gates
    // - Callback validation
    // - Foreground/background transitions
    console.log('[Home] Phase 4: Auto-starting hybrid audio trigger...');
    const timer = setTimeout(() => {
      const startConfig = {
        monitoringPeriods: validPeriods,
        // FIX: Pass full week schedule so the native plugin can always derive today's
        // periods correctly, even if the app stays in background past midnight.
        periodosSemana: periodosSemana ?? undefined,
        sessionToken: getSessionToken() || undefined,
        refreshToken: getRefreshToken() || undefined,
        emailUsuario: getUserEmail() || undefined,
      };
      hybridAudioTrigger.start(startConfig).catch(err => {
        console.error('[Home] Failed to auto-start hybrid audio trigger:', err);
        toast({
          title: "Erro ao iniciar monitoramento",
          description: "Reinicie o app para tentar novamente.",
          variant: "destructive"
        });
      });
    }, 500);
    return () => clearTimeout(timer);
  }, [isPermissionsLoading, toast, audioTrigger.isCapturing, validPeriods, periodosSemana]);

  // Periodic check for monitoring period changes (every minute)
  // This ensures the app switches modes automatically when entering/exiting periods
  useEffect(() => {
    const checkPeriod = () => {
      const newStatus = getMonitoringGateStatus(new Date(), validPeriods);
      const wasWithinPeriod = monitoring.dentroHorario;
      const isWithinPeriod = newStatus.isWithinPeriod;

      if (wasWithinPeriod !== isWithinPeriod) {
        console.log('[Home] Period status changed:', { wasWithinPeriod, isWithinPeriod });

        // Report status to API via Native Plugin
        const status = isWithinPeriod ? 'janela_iniciada' : 'janela_finalizada';
        hybridAudioTrigger.reportStatus(status, isWithinPeriod, 'agenda_automatica')
          .catch(err => console.error('[Home] Failed to report period status:', err));

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
  }, [audioTrigger.discussionOn, recording.isRecording, panic.isPanicActive, appState, toast]);

  const [isRecordLoading, setIsRecordLoading] = useState(false);
  const [showPasswordDialog, setShowPasswordDialog] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [showPanicPulse, setShowPanicPulse] = useState(false);
  const isRecordingEffective = recording.isRecording || appState.status === 'recording';
  const menuControlHeightPx = 40;
  // No Android, descer os botões do header 2x a altura do botão menu
  const controlsVerticalOffsetPx = Capacitor.getPlatform() === 'android'
    ? 0
    : Math.round(menuControlHeightPx * 1.5);

  const handleRecordToggle = async () => {
    if (isRecordLoading) return; // Prevent multiple clicks

    // Se o pânico estiver ativo, não permite parar a gravação
    if (panic.isPanicActive && isRecordingEffective) {
      setShowPanicPulse(true);
      setTimeout(() => setShowPanicPulse(false), 2000);
      toast({
        title: 'Ação não permitida',
        description: 'Cancele o pânico antes de parar a gravação.',
        variant: 'destructive',
      });
      return;
    }

    setIsRecordLoading(true);
    try {
      if (isRecordingEffective) {
        await recording.stopRecording();
        appState.setStatus('normal');
        toast({
          title: 'Gravação encerrada',
          description: `${recording.segmentsSent} segmentos enviados.`,
        });
      } else {
        // Optimistic UI status to avoid delayed "Gravando" indication in meter.
        appState.setStatus('recording');
        const success = await recording.startRecording();
        if (success) {
          toast({
            title: 'Gravação iniciada',
            description: 'O áudio está sendo enviado em tempo real.',
          });
        } else {
          appState.setStatus('normal');
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

  const handlePasswordValidated = async (loginTipo: 'normal' | 'coacao') => {
    setShowPasswordDialog(false);
    setIsCancelling(true);

    if (loginTipo === 'coacao') {
      console.log('[Home] MODO COAÇÃO DETECTADO - Simulando cancelamento');
      toast({
        title: 'Proteção desativada',
        description: 'O modo pânico foi encerrado.',
      });
      setIsCancelling(false);
      return;
    }

    console.log('[Home] Cancelando pânico (modo normal)');
    await panic.deactivatePanic();
    appState.setStatus('normal');

    toast({
      title: 'Proteção desativada',
      description: 'O modo pânico foi encerrado.',
    });
    setIsCancelling(false);
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
    <div className="min-h-screen flex flex-col bg-app-deep safe-area-inset-top safe-area-inset-bottom relative overflow-hidden">
      <PasswordValidationDialog
        open={showPasswordDialog}
        onOpenChange={setShowPasswordDialog}
        onValidated={handlePasswordValidated}
        title="Confirmar Cancelamento"
        description="Digite sua senha para cancelar o modo pânico"
      />
      <header
        className="flex items-center justify-between px-4 pb-2 bg-transparent"
        style={{ paddingTop: Capacitor.getPlatform() === 'android' ? '0.25rem' : 'calc(env(safe-area-inset-top) + 0.75rem)' }}
      >

        <div />

        <div className="flex items-center gap-2" style={{ marginTop: `${controlsVerticalOffsetPx}px` }}>
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

        {/* Top section: Audio meter with integrated monitoring status - ALWAYS VISIBLE */}
        <div className="w-full max-w-sm flex flex-col items-center pt-2 mb-8">
          <AudioTriggerMeter
            score={audioTrigger.metrics?.score ?? 0}
            isCapturing={audioTrigger.isCapturing}
            state={audioTrigger.state}
            isRecording={isRecordingEffective}
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


        {/* Center section: Stable layout with fixed positions */}
        <div className="flex-1 w-full max-w-sm flex flex-col items-center justify-center gap-4 min-h-[400px]">

          {/* Fixed Timer Area above Panic Button */}
          <div className="h-16 flex flex-col items-center justify-end pb-2">
            {(isRecordingEffective || panic.isPanicActive) && (
              <motion.div
                initial={{ opacity: 0, y: 5 }}
                animate={{ opacity: 1, y: 0 }}
                className="flex items-end gap-2"
              >
                <span className={`text-4xl font-mono font-bold ${panic.isPanicActive ? 'text-destructive' : 'text-destructive'}`}>
                  {formatDuration(
                    panic.isPanicActive
                      ? panic.panicDuration
                      : (recording.origemGravacao === 'botao_manual' ? recording.duration : 0)
                  )}
                </span>
                {isRecordingEffective && !panic.isPanicActive && (
                  <span className="text-xs font-mono text-success opacity-80 pb-1">
                    ({recording.segmentsSent || Math.floor(recording.duration / 30) + 1})
                  </span>
                )}
              </motion.div>
            )}
            {isRecordingEffective && !panic.isPanicActive && (
              <span className="text-[10px] font-medium text-destructive uppercase tracking-widest">
                Gravando {recording.origemGravacao === 'automatico' ? '(Detector Ao Redor)' : '(Modo Manual)'}
              </span>
            )}
            {panic.isPanicActive && (
              <span className="text-[10px] font-medium text-destructive uppercase tracking-widest animate-pulse">
                Pânico Ativo
              </span>
            )}
          </div>


          {/* Panic Button - Fixed Size & Position */}
          <motion.div
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: 0.2 }}
          >
            <PanicButton
              onHoldStart={panic.isPanicActive ? () => setShowPasswordDialog(true) : handlePanicStart}
              onHoldEnd={panic.isPanicActive ? () => { } : handlePanicEnd}
              isActivating={panic.isActivating}
              isPanicActive={panic.isPanicActive}
              shouldPulse={showPanicPulse}
              disabled={panic.isSendingToServer || isCancelling}
              isLoading={panic.isSendingToServer || isCancelling}
            />
          </motion.div>


          {/* Botão de gravação manual - centralizado */}
          <motion.div
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.3 }}
            className="flex flex-col items-center mt-4"
          >
            <Button
              onClick={handleRecordToggle}
              disabled={panic.isActivating || panic.isSendingToServer || isRecordLoading}
              aria-label={isRecordingEffective ? 'Parar gravação' : 'Iniciar gravação manual'}
              className={`rounded-full w-16 h-16 p-0 border-0 ${isRecordingEffective
                ? 'bg-black hover:bg-black/90 text-white'
                : 'btn-primary-neon hover:opacity-90 text-white'
                }`}
            >
              {isRecordLoading ? (
                '...'
              ) : isRecordingEffective ? (
                <Square className="w-6 h-6 fill-current" />
              ) : (
                <span className="relative flex items-center justify-center w-7 h-7">
                  <span className="absolute w-7 h-7 rounded-full border-2 border-white/90" />
                  <span className="w-3 h-3 rounded-full bg-white" />
                </span>
              )}
            </Button>
            <span className={`mt-2 text-sm font-medium ${isRecordingEffective ? 'text-destructive' : 'text-muted-foreground'}`}>
              {isRecordingEffective ? 'Parar' : 'Gravar'}
            </span>
          </motion.div>
        </div>

      </main>

      {/* Powered by footer removed */}



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
            className="absolute right-0 top-0 bottom-0 w-72 card-glass-dark border-l border-border"
            style={{
              paddingTop: 'calc(env(safe-area-inset-top) + 1rem)',
              paddingBottom: 'calc(env(safe-area-inset-bottom) + 1rem)',
              paddingLeft: '1.5rem',
              paddingRight: '1.5rem',
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
