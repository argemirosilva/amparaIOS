/**
 * Audio Trigger Meter - Minimalist circular meter with integrated monitoring status
 * Shows detection proximity and monitoring period info in a unified component
 */

import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { Ear, EarOff } from 'lucide-react';
import type { TriggerState } from '@/types/audioTrigger';
import type { MonitoringPeriod, PeriodosSemana, OrigemGravacao } from '@/lib/types';

export type MonitoringStatusType = 'active' | 'next' | 'none' | 'loading';

interface AudioTriggerMeterProps {
  score: number;
  isCapturing: boolean;
  state: TriggerState;
  isRecording: boolean;
  recordingDuration?: number;
  recordingOrigin?: OrigemGravacao | null;
  // Monitoring status props
  dentroHorario?: boolean;
  periodoAtualIndex?: number | null;
  periodosHoje?: MonitoringPeriod[];
  periodosSemana?: PeriodosSemana | null;
  isLoading?: boolean;
  // Calibration status
  isCalibrated?: boolean;
  // Noisy environment
  isNoisy?: boolean;
  // Trigger mode (for showing correct status)
  triggerMode?: 'STOPPED' | 'WAITING_PERMISSION' | 'RUNNING';
}

// Linear interpolation between two hex colors
const lerpColor = (color1: string, color2: string, ratio: number): string => {
  const hex = (c: string) => parseInt(c, 16);
  const r1 = hex(color1.slice(1, 3));
  const g1 = hex(color1.slice(3, 5));
  const b1 = hex(color1.slice(5, 7));
  const r2 = hex(color2.slice(1, 3));
  const g2 = hex(color2.slice(3, 5));
  const b2 = hex(color2.slice(5, 7));

  const r = Math.round(r1 + (r2 - r1) * ratio);
  const g = Math.round(g1 + (g2 - g1) * ratio);
  const b = Math.round(b1 + (b2 - b1) * ratio);

  return `rgb(${r}, ${g}, ${b})`;
};

// Get gradient color based on score (0-7 for color scale)
const getGradientColor = (score: number, isCalibrated: boolean, dentroHorario: boolean): string => {
  // Fora do período de monitoramento - cinza
  if (!dentroHorario) {
    return '#9ca3af'; // gray-400
  }

  // Calibrando - laranja fixo
  if (!isCalibrated) {
    return '#f97316'; // orange-500
  }

  // Monitorando - verde base, escala para amarelo/vermelho conforme score
  const ratio = Math.min(score / 7, 1);

  if (ratio <= 0.5) {
    // Darker Green (#16a34a) → Darker Yellow (#ca8a04)
    return lerpColor('#16a34a', '#ca8a04', ratio * 2);
  } else {
    // Darker Yellow (#ca8a04) → Darker Red (#dc2626)
    return lerpColor('#ca8a04', '#dc2626', (ratio - 0.5) * 2);
  }
};

export function AudioTriggerMeter({
  score,
  isCapturing,
  state,
  dentroHorario = false,
  periodoAtualIndex = null,
  periodosHoje = [],
  periodosSemana = null,
  isLoading = false,
  isCalibrated = false,
  isNoisy = false,
  triggerMode,
}: AudioTriggerMeterProps) {
  const navigate = useNavigate();
  const [now, setNow] = useState(new Date());

  // Contador de toques rápidos no mostrador para abrir a tela técnica de debug
  const tapCountRef = useRef(0);
  const tapTimerRef = useRef<NodeJS.Timeout | null>(null);

  const handleMeterTap = useCallback(() => {
    tapCountRef.current += 1;

    // Resetar timer anterior
    if (tapTimerRef.current) {
      clearTimeout(tapTimerRef.current);
    }

    // Se atingiu 5 toques, navegar para a tela de debug
    if (tapCountRef.current >= 5) {
      tapCountRef.current = 0;
      navigate('/audio-trigger-debug');
      return;
    }

    // Resetar contador após 2 segundos sem toques
    tapTimerRef.current = setTimeout(() => {
      tapCountRef.current = 0;
    }, 2000);
  }, [navigate]);

  // Debug: Log score changes
  useEffect(() => {
    console.log('[AudioTriggerMeter] 🎯 Score prop changed:', score);
  }, [score]);

  // Update time every second for countdown
  useEffect(() => {
    const interval = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(interval);
  }, []);

  const size = 56;
  const strokeWidth = 4;
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;

  // Use 270° arc (75% of circle)
  const arcLength = circumference * 0.75;
  // Scale: 0-1 (score is normalized, 1.0 = detection threshold met)
  const progress = Math.min(score, 1);
  const offset = arcLength * (1 - progress);

  const strokeColor = getGradientColor(score * 7, isCalibrated, dentroHorario); // Scale color: 0-1 -> 0-7 for gradient

  // Parse time string "HH:MM" to today's Date
  const parseTime = (timeStr: string): Date => {
    const [hours, minutes] = timeStr.split(':').map(Number);
    const date = new Date();
    date.setHours(hours, minutes, 0, 0);
    return date;
  };

  // Get current period
  const currentPeriod = useMemo(() => {
    if (periodoAtualIndex !== null && periodosHoje[periodoAtualIndex]) {
      return periodosHoje[periodoAtualIndex];
    }
    return null;
  }, [periodoAtualIndex, periodosHoje]);

  // Day names for mapping
  const dayNames = ['dom', 'seg', 'ter', 'qua', 'qui', 'sex', 'sab'] as const;

  // Get readable day label
  const getDayLabel = (dayKey: string): string => {
    const labels: Record<string, string> = {
      dom: 'Dom',
      seg: 'Seg',
      ter: 'Ter',
      qua: 'Qua',
      qui: 'Qui',
      sex: 'Sex',
      sab: 'Sáb',
    };
    return labels[dayKey] || dayKey;
  };

  // Get next period (today or future days)
  const nextPeriodInfo = useMemo((): { period: MonitoringPeriod; dayLabel: string } | null => {
    if (dentroHorario) return null;

    const currentTime = now.getHours() * 60 + now.getMinutes();
    const currentDayIndex = now.getDay(); // 0 = Sunday

    // First, check remaining periods today
    for (const period of periodosHoje) {
      const [hours, minutes] = period.inicio.split(':').map(Number);
      const periodStart = hours * 60 + minutes;
      if (periodStart > currentTime) {
        return { period, dayLabel: 'Hoje' };
      }
    }

    // If no periods left today, look at future days
    if (periodosSemana) {
      for (let i = 1; i <= 7; i++) {
        const futureDayIndex = (currentDayIndex + i) % 7;
        const dayKey = dayNames[futureDayIndex];
        const periods = periodosSemana[dayKey];

        if (periods && periods.length > 0) {
          const dayLabel = i === 1 ? 'Amanhã' : getDayLabel(dayKey);
          return { period: periods[0], dayLabel };
        }
      }
    }

    return null;
  }, [dentroHorario, periodosHoje, periodosSemana, now, getDayLabel]);

  // Calculate time difference in readable format
  const formatTimeDiff = (targetTime: Date): string => {
    const diff = targetTime.getTime() - now.getTime();
    if (diff <= 0) return '0min';

    const hours = Math.floor(diff / (1000 * 60 * 60));
    const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));

    if (hours > 0) {
      return `${hours}h ${minutes}min`;
    }
    return `${minutes}min`;
  };

  // Determine monitoring status
  const monitoringStatus: MonitoringStatusType = isLoading
    ? 'loading'
    : dentroHorario && currentPeriod
      ? 'active'
      : nextPeriodInfo
        ? 'next'
        : 'none';

  return (
    <div className="flex flex-col items-center gap-2">
      {/* Circular meter */}
      <div className="relative" style={{ width: size, height: size }} onClick={handleMeterTap}>
        <svg
          width={size}
          height={size}
          viewBox={`0 0 ${size} ${size}`}
          className="transform -rotate-[135deg]"
        >
          {/* Background arc - more transparent */}
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            stroke="hsl(var(--muted) / 0.4)"
            strokeWidth={strokeWidth}
            strokeDasharray={arcLength}
            strokeDashoffset={0}
            strokeLinecap="round"
          />



          {/* Progress arc with gradient color */}
          {score > 0 && (
            <motion.circle
              cx={size / 2}
              cy={size / 2}
              r={radius}
              fill="none"
              stroke={strokeColor}
              strokeWidth={strokeWidth}
              strokeDasharray={arcLength}
              strokeLinecap="round"
              initial={{ strokeDashoffset: arcLength }}
              animate={{
                strokeDashoffset: offset,
                stroke: strokeColor,
              }}
              transition={{
                strokeDashoffset: { duration: 0.5, ease: 'easeOut' },
                stroke: { duration: 0.3 },
              }}
              style={{}}
            />
          )}
        </svg>

        {/* Center icon with sound waves */}
        <div className="absolute inset-0 flex items-center justify-center">
          {/* Sound wave animations */}
          {isCapturing && (
            <>
              {[0, 1, 2].map((i) => (
                <motion.div
                  key={i}
                  className="absolute rounded-full border"
                  style={{
                    borderColor: strokeColor,
                    width: 18,
                    height: 18,
                  }}
                  initial={{ scale: 0.8, opacity: 0.6 }}
                  animate={{ scale: 2, opacity: 0 }}
                  transition={{
                    duration: 1.5,
                    repeat: Infinity,
                    delay: i * 0.5,
                    ease: 'easeOut',
                  }}
                />
              ))}
            </>
          )}

          <motion.div
            animate={isCapturing ? {
              scale: [1, 1.1, 1],
            } : {}}
            transition={{
              duration: 1.5,
              repeat: Infinity,
              ease: 'easeInOut',
            }}
            className={`p-1.5 rounded-full relative z-10 ${isCapturing
              ? 'bg-success/10'
              : 'bg-muted/40'
              }`}
          >
            {isCapturing ? (
              <Ear
                className={`w-3.5 h-3.5 ${!dentroHorario ? 'text-gray-400' :
                  !isCalibrated ? 'text-orange-500' :
                    'text-emerald-500'
                  }`}
              />
            ) : (
              <EarOff className="w-3.5 h-3.5 text-muted-foreground" />
            )}
          </motion.div>

        </div>
      </div>

      {/* Status de monitoramento integrado (apenas visual, sem navegação) */}
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="flex flex-col items-center gap-0.5"
      >
        {monitoringStatus === 'loading' && (
          <div className="flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-muted animate-pulse" />
            <span className="text-xs text-muted-foreground">Carregando...</span>
          </div>
        )}

        {monitoringStatus === 'active' && currentPeriod && (
          <>
            <div className="flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
              <span className="text-xs font-medium text-emerald-500">Ativo</span>
              <span className="text-[10px] text-muted-foreground">{currentPeriod.inicio}-{currentPeriod.fim}</span>
            </div>
            <span className="text-[10px] text-muted-foreground">
              Termina em {formatTimeDiff(parseTime(currentPeriod.fim))}
            </span>
            {isNoisy ? (
              <span className="text-[10px] font-medium text-orange-500">
                ⚠️ Ambiente ruidoso
              </span>
            ) : (
              <span className={`text-[10px] font-medium ${triggerMode === 'WAITING_PERMISSION' ? 'text-red-500' :
                triggerMode === 'STOPPED' ? 'text-gray-500' :
                  triggerMode === 'RUNNING' ? (isCalibrated ? 'text-emerald-500' : 'text-orange-500') :
                    isCalibrated ? 'text-emerald-500' : 'text-orange-500'
                }`}>
                {triggerMode === 'WAITING_PERMISSION' ? 'Permissão pendente' :
                  triggerMode === 'STOPPED' ? 'Aguardando...' :
                    triggerMode === 'RUNNING' ? (isCalibrated ? 'Monitorando' : 'Calibrando...') :
                      isCalibrated ? 'Calibrado' : 'Calibrando...'}
              </span>
            )}
          </>
        )}

        {monitoringStatus === 'next' && nextPeriodInfo && (
          <>
            <div className="flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-primary" />
              <span className="text-xs font-medium text-primary">{nextPeriodInfo.dayLabel}</span>
              <span className="text-[10px] text-muted-foreground">{nextPeriodInfo.period.inicio}-{nextPeriodInfo.period.fim}</span>
            </div>
            {nextPeriodInfo.dayLabel === 'Hoje' && (
              <span className="text-[10px] text-muted-foreground">
                Inicia em {formatTimeDiff(parseTime(nextPeriodInfo.period.inicio))}
              </span>
            )}
          </>
        )}

        {monitoringStatus === 'none' && (
          <div className="flex flex-col items-center gap-0.5">
            <div className="flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-muted-foreground/50" />
              <span className="text-xs text-muted-foreground">Sem monitoramento agendado</span>
            </div>
            <span className="text-[10px] text-muted-foreground/70">
              Nenhum período configurado esta semana
            </span>
          </div>
        )}
      </motion.div>
    </div>
  );
}
