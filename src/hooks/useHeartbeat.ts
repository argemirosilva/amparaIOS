import { useState, useCallback, useRef, useEffect } from 'react';
import { pingMobile, hasValidSession } from '@/lib/api';

interface HeartbeatState {
  isOnline: boolean;
  lastPing: string | null;
  isRunning: boolean;
  consecutiveFailures: number;
}

interface UseHeartbeatOptions {
  interval?: number;       // Ping interval in ms (default: 60 sec)
  maxRetries?: number;     // Max consecutive failures before stopping (default: 5)
  autoStart?: boolean;     // Start automatically on mount (default: false)
  isRecording?: boolean;   // Is currently recording
  isMonitoring?: boolean;  // Is currently monitoring
}

const DEFAULT_INTERVAL = 30 * 1000; // 30 seconds
const MAX_RETRIES = 5;

export function useHeartbeat(options: UseHeartbeatOptions = {}) {
  const {
    interval = DEFAULT_INTERVAL,
    maxRetries = MAX_RETRIES,
    autoStart = false,
    isRecording = false,
    isMonitoring = false,
  } = options;

  const [state, setState] = useState<HeartbeatState>({
    isOnline: true,
    lastPing: null,
    isRunning: false,
    consecutiveFailures: 0,
  });

  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const failureCountRef = useRef(0);

  // Send ping to server
  const ping = useCallback(async (): Promise<boolean> => {
    if (!hasValidSession()) {
      return false;
    }

    const result = await pingMobile(isRecording, isMonitoring);

    if (result.error || !result.data) {
      failureCountRef.current++;
      
      setState(prev => ({
        ...prev,
        isOnline: false,
        consecutiveFailures: failureCountRef.current,
      }));

      // Stop if too many failures
      if (failureCountRef.current >= maxRetries) {
        stop();
        console.warn('Heartbeat stopped: too many consecutive failures');
      }

      return false;
    }

    // Reset failure count on success
    failureCountRef.current = 0;

    setState(prev => ({
      ...prev,
      isOnline: true,
      lastPing: result.data!.server_time || new Date().toISOString(),
      consecutiveFailures: 0,
    }));

    return true;
  }, [maxRetries, isRecording, isMonitoring]);

  // Start heartbeat
  const start = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
    }

    // Reset failure count
    failureCountRef.current = 0;

    // Ping immediately
    ping();

    // Set up interval
    intervalRef.current = setInterval(ping, interval);

    setState(prev => ({ ...prev, isRunning: true }));
  }, [ping, interval]);

  // Stop heartbeat
  const stop = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }

    setState(prev => ({ ...prev, isRunning: false }));
  }, []);

  // Manual ping
  const manualPing = useCallback(async (): Promise<boolean> => {
    return ping();
  }, [ping]);

  // Auto-start on mount if enabled
  useEffect(() => {
    if (autoStart && hasValidSession()) {
      start();
    }

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    };
  }, [autoStart, start]);

  return {
    ...state,
    start,
    stop,
    ping: manualPing,
  };
}
