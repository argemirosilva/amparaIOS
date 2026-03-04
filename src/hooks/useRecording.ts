import { useState, useCallback, useEffect, useRef } from 'react';
import { reportarStatusGravacao } from '@/lib/api';
import { OrigemGravacao } from '@/lib/types';
import { AudioTriggerNative } from '@/plugins/audioTriggerNative';
import { getSessionToken, getUserEmail } from '@/lib/api';
import { Capacitor } from '@capacitor/core';

interface RecordingState {
  isRecording: boolean;
  isPaused: boolean;
  isStopping: boolean;
  duration: number;
  segmentsSent: number;
  segmentsPending: number;
  origemGravacao: OrigemGravacao | null;
}

export function useRecording() {
  const [state, setState] = useState<RecordingState>({
    isRecording: false,
    isPaused: false,
    isStopping: false,
    duration: 0,
    segmentsSent: 0,
    segmentsPending: 0,
    origemGravacao: null,
  });

  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const isRecordingRef = useRef(false);
  const origemGravacaoRef = useRef<OrigemGravacao>('botao_manual');
  const currentSessionIdRef = useRef<string | null>(null);
  const startedAtRef = useRef<number | null>(null);

  // iOS MediaRecorder refs
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioStreamRef = useRef<MediaStream | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const segmentIndexRef = useRef(0);
  const uploadIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Listen to native recording events
  useEffect(() => {
    let listenerHandle: { remove: () => void } | null = null;

    AudioTriggerNative.addListener('audioTriggerEvent', (event) => {
      console.log('[useRecording] Native event:', event);

      switch (event.event) {
        case 'nativeRecordingStarted':
          currentSessionIdRef.current = event.sessionId || null;
          // Capture startedAt timestamp from native
          startedAtRef.current = (event.startedAt as number) || Date.now();
          console.log('[useRecording] Recording started at:', new Date(startedAtRef.current).toISOString());
          // Update origem if provided by native (automatic detection)
          if (event.origemGravacao) {
            origemGravacaoRef.current = event.origemGravacao as OrigemGravacao;
            console.log('[useRecording] Origem from native:', event.origemGravacao);
          }
          setState((prev) => ({
            ...prev,
            isRecording: true,
            isStopping: false,
            duration: 0,
            segmentsSent: 0,
            segmentsPending: 0,
            origemGravacao: event.origemGravacao as OrigemGravacao || prev.origemGravacao,
          }));
          break;

        case 'nativeRecordingProgress':
          // Atualiza contagem de segmentos completados
          if (event.segmentIndex !== undefined && event.segmentIndex !== null) {
            const segIdx = event.segmentIndex as number;
            setState((prev) => ({
              ...prev,
              segmentsSent: segIdx,
            }));
          }
          break;

        case 'nativeUploadProgress':
          // Update upload progress
          if (event.success !== undefined) {
            setState((prev) => ({
              ...prev,
              segmentsSent: (event.success as number) ?? 0,
              segmentsPending: (event.pending as number) ?? 0,
            }));
          }
          break;

        case 'nativeRecordingStopped':
          setState((prev) => ({
            ...prev,
            isRecording: false,
            isStopping: false,
          }));
          currentSessionIdRef.current = null;
          startedAtRef.current = null;
          break;

        case 'recordingState':
          // Sync recording state from native (e.g., after app restart)
          console.log('[useRecording] Syncing recording state from native:', event);
          if (event.isRecording !== undefined) {
            currentSessionIdRef.current = event.sessionId || null;
            setState((prev) => ({
              ...prev,
              isRecording: event.isRecording as boolean,
              isStopping: false,
            }));
          }
          break;
      }
    }).then(handle => {
      listenerHandle = handle;
    });

    return () => {
      if (listenerHandle) {
        listenerHandle.remove();
      }
    };
  }, []);

  // Duration timer
  useEffect(() => {
    if (state.isRecording && !state.isPaused) {
      timerRef.current = setInterval(() => {
        // Calculate duration from startedAt timestamp (persistent across remounts)
        if (startedAtRef.current) {
          const elapsed = Math.floor((Date.now() - startedAtRef.current) / 1000);
          setState((prev) => ({ ...prev, duration: elapsed }));
        } else {
          setState((prev) => ({ ...prev, duration: prev.duration + 1 }));
        }
      }, 1000);
    } else {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    }

    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
      }
    };
  }, [state.isRecording, state.isPaused]);

  const startRecording = useCallback(async (origem: OrigemGravacao = 'botao_manual'): Promise<boolean> => {
    try {
      const platform = Capacitor.getPlatform();
      console.log('[useRecording] Starting recording, platform:', platform, 'origem:', origem);

      origemGravacaoRef.current = origem;

      // Get credentials
      const sessionToken = getSessionToken();
      const emailUsuario = getUserEmail();

      if (!sessionToken || !emailUsuario) {
        console.error('[useRecording] Missing credentials');
        return false;
      }

      // iOS: Use native plugin (works in background with screen locked)
      if (platform === 'ios') {
        console.log('[useRecording] iOS detected, using native plugin');

        await AudioTriggerNative.startRecording({
          sessionToken,
          emailUsuario,
          origemGravacao: origem,
        });
      } else {
        // Android: Use native plugin
        console.log('[useRecording] Android detected, using native plugin');
        await AudioTriggerNative.startRecording({
          sessionToken,
          emailUsuario,
          origemGravacao: origem,
        });
      }

      isRecordingRef.current = true;

      setState((prev) => ({
        ...prev,
        isRecording: true,
        isPaused: false,
        isStopping: false,
        origemGravacao: origem,
      }));

      console.log('[useRecording] Native recording started');
      return true;

    } catch (error) {
      console.error('[useRecording] Error starting native recording:', error);
      return false;
    }
  }, []);

  const stopRecording = useCallback(async (): Promise<void> => {
    try {
      const platform = Capacitor.getPlatform();
      console.log('[useRecording] Stopping recording, platform:', platform);

      setState((prev) => ({ ...prev, isStopping: true }));

      // iOS: Stop native recording
      if (platform === 'ios') {
        console.log('[useRecording] iOS detected, stopping native recording');
        await AudioTriggerNative.stopManualRecording();
      } else {
        // Android: Stop native recording
        await AudioTriggerNative.stopRecording();
      }

      isRecordingRef.current = false;

      // Report final status
      if (currentSessionIdRef.current) {
        await reportarStatusGravacao(
          'finalizada' as import('@/lib/types').RecordingStatusType,
          origemGravacaoRef.current || undefined,
        );
      }

      setState((prev) => ({
        ...prev,
        isRecording: false,
        isStopping: false,
        duration: 0,
      }));

      console.log('[useRecording] Recording stopped');

    } catch (error) {
      console.error('[useRecording] Error stopping recording:', error);
      setState((prev) => ({ ...prev, isStopping: false }));
    }
  }, [state.segmentsSent]);

  const pauseRecording = useCallback(() => {
    // Native recording doesn't support pause
    console.warn('[useRecording] Pause not supported in native recording');
  }, []);

  const resumeRecording = useCallback(() => {
    // Native recording doesn't support resume
    console.warn('[useRecording] Resume not supported in native recording');
  }, []);

  return {
    isRecording: state.isRecording,
    isPaused: state.isPaused,
    isStopping: state.isStopping,
    duration: state.duration,
    segmentsSent: state.segmentsSent,
    segmentsPending: state.segmentsPending,
    origemGravacao: state.origemGravacao,
    startRecording,
    stopRecording,
    pauseRecording,
    resumeRecording,
  };
}

// Helper function to get supported MIME type
function getSupportedMimeType(): string {
  const platform = Capacitor.getPlatform();

  // iOS: Priorizar MP4 (AAC codec)
  if (platform === 'ios') {
    const iosTypes = [
      'audio/mp4',
      'audio/aac',
      'audio/webm;codecs=opus',
      'audio/webm',
    ];

    for (const type of iosTypes) {
      if (MediaRecorder.isTypeSupported(type)) {
        console.log('[useRecording] iOS: Selected MIME type:', type);
        return type;
      }
    }
  }

  // Android: Priorizar OGG (Opus codec) - mantém comportamento atual
  const types = [
    'audio/webm;codecs=opus',
    'audio/ogg;codecs=opus',
    'audio/webm',
    'audio/mp4',
  ];

  for (const type of types) {
    if (MediaRecorder.isTypeSupported(type)) {
      console.log('[useRecording] Selected MIME type:', type);
      return type;
    }
  }

  console.log('[useRecording] No supported MIME type found, using default');
  return ''; // Browser will use default
}
