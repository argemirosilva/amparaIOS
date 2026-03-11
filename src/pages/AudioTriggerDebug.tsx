/**
 * Tela Técnica de Debug — com abas para Detecção e GPS
 * Acessível via 5 toques rápidos no mostrador principal
 */
import React, { useState, useEffect, useCallback } from 'react';
import { motion } from 'framer-motion';
import { ArrowLeft, Radio, MapPin, Navigation, Crosshair, Settings2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { useAudioTriggerSingleton } from '@/hooks/useAudioTriggerSingleton';
import { Capacitor } from '@capacitor/core';
import { Geolocation } from '@capacitor/geolocation';
import { AudioTriggerNative } from '@/plugins/audioTriggerNative';
import { audioTriggerSingleton } from '@/services/audioTriggerSingleton';

// Mapeamento completo de estados nativos para exibição
const STATE_INFO: Record<string, { label: string; color: string; step: number }> = {
  IDLE: { label: 'OCIOSO', color: '#6b7280', step: 0 },
  MONITORING: { label: 'MONITOR', color: '#22c55e', step: 0 },
  CALIBRATING: { label: 'CALIBRA', color: '#f97316', step: 0 },
  DISCUSSION_DETECTED: { label: 'DETECTOU', color: '#f59e0b', step: 1 },
  RECORDING: { label: 'GRAVANDO', color: '#ef4444', step: 2 },
  RECORDING_STARTED: { label: 'GRAVANDO', color: '#ef4444', step: 2 },
  DISCUSSION_ENDING: { label: 'PARANDO', color: '#f97316', step: 3 },
  COOLDOWN: { label: 'COOLDOWN', color: '#3b82f6', step: 4 },
};

// Estados para a timeline visual (apenas os 5 do fluxo principal)
const TIMELINE_STATES: Array<[string, { label: string; color: string; step: number }]> = [
  ['IDLE', { label: 'OCIOSO', color: '#6b7280', step: 0 }],
  ['DISCUSSION_DETECTED', { label: 'DETECTOU', color: '#f59e0b', step: 1 }],
  ['RECORDING_STARTED', { label: 'GRAVANDO', color: '#ef4444', step: 2 }],
  ['DISCUSSION_ENDING', { label: 'PARANDO', color: '#f97316', step: 3 }],
  ['COOLDOWN', { label: 'COOLDOWN', color: '#3b82f6', step: 4 }],
];

// Barra de progresso com marcador de threshold
function ThresholdBar({ value, threshold, label, color, maxVal = 1.0 }: {
  value: number; threshold: number; label: string; color: string; maxVal?: number;
}) {
  const pct = Math.min((value / maxVal) * 100, 100);
  const threshPct = (threshold / maxVal) * 100;
  const isAbove = value >= threshold;

  return (
    <div className="space-y-1">
      <div className="flex justify-between items-baseline">
        <span className="text-xs text-gray-400">{label}</span>
        <span className={`text-sm font-mono font-bold ${isAbove ? 'text-red-400' : 'text-gray-300'}`}>
          {(value * 100).toFixed(0)}%
        </span>
      </div>
      <div className="relative h-3 bg-gray-800 rounded-full overflow-hidden">
        <motion.div
          className="absolute inset-y-0 left-0 rounded-full"
          style={{ backgroundColor: color }}
          animate={{ width: `${pct}%` }}
          transition={{ duration: 0.1 }}
        />
        <div
          className="absolute top-0 bottom-0 w-0.5 bg-white/70"
          style={{ left: `${threshPct}%` }}
        />
      </div>
      <div className="flex justify-between text-[9px] text-gray-500">
        <span>0%</span>
        <span className="text-white/50">Limite: {(threshold * 100).toFixed(0)}%</span>
        <span>100%</span>
      </div>
    </div>
  );
}

// Valores padrão do AudioTriggerDefaults.java (para restaurar)
const DEFAULTS = {
  vadDeltaDb: 8.0, loudDeltaDb: 10.0,
  speechDensityMin: 0.35, loudDensityMin: 0.25,
  discussionWindowSeconds: 10, startHoldSeconds: 6,
  endHoldSeconds: 60, silenceDecaySeconds: 10,
  zcrThreshold: 0.12,
};

// Slider inline compacto para embutir ao lado dos indicadores
function InlineSlider({ label, paramKey, min, max, step, unit, decimal, values, onChange }: {
  label: string; paramKey: string; min: number; max: number; step: number;
  unit: string; decimal?: boolean;
  values: Record<string, number>; onChange: (key: string, val: number, decimal?: boolean) => void;
}) {
  const val = values[paramKey] ?? 0;
  const displayVal = paramKey.includes('Density')
    ? `${(val * 100).toFixed(0)}%`
    : `${decimal ? val.toFixed(1) : val}${unit}`;
  return (
    <div className="flex items-center gap-2 mt-0.5">
      {label && <span className="text-[8px] text-gray-600 w-14 shrink-0">{label}</span>}
      <input type="range" min={min} max={max} step={step} value={val}
        onChange={e => onChange(paramKey, parseFloat(e.target.value), decimal)}
        className="flex-1 h-1 bg-gray-700 rounded-full appearance-none cursor-pointer
          [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:h-3
          [&::-webkit-slider-thumb]:bg-purple-500 [&::-webkit-slider-thumb]:rounded-full"
      />
      <span className="text-[9px] font-mono text-purple-300 w-10 text-right shrink-0">{displayVal}</span>
    </div>
  );
}

// Estado do GPS para atualização periódica
interface GpsState {
  latitude: number | null;
  longitude: number | null;
  accuracy: number | null;
  speed: number | null;
  altitude: number | null;
  heading: number | null;
  lastUpdate: number | null;
  error: string | null;
}

export function AudioTriggerDebugPage() {
  const navigate = useNavigate();
  const { metrics, state, isCalibrated } = useAudioTriggerSingleton();
  const [activeTab, setActiveTab] = useState<'detection' | 'gps'>('detection');

  // State de tuning — valores locais dos sliders
  const [tuningValues, setTuningValues] = useState({ ...DEFAULTS });
  const [tuningInitialized, setTuningInitialized] = useState(false);

  // LOGS ASR LOCAIS
  const [asrLogs, setAsrLogs] = useState<{ id: string; phraseId: string; confidence: number; time: Date; phraseType: string }[]>([]);

  // Carregar os eventos da Sessão Singleton que já aconteceram (histórico) e escutar novos
  useEffect(() => {
    // Escutando ativamente via hook/intervalo ou apenas os eventos mais recentes do Singleton
    const updateLogs = () => {
      const allEvents = audioTriggerSingleton.getEvents();
      const asrEvents = allEvents
        .filter((e: any) => e.type === 'phraseDetected' || e.event === 'phraseDetected')
        .map((e: any) => ({
          id: `${e.timestamp}-${e.phraseId}`,
          phraseId: e.phraseId || 'Desconhecido',
          confidence: e.confidence || 0,
          time: new Date(e.timestamp || Date.now()),
          phraseType: e.phraseType || 'KEYWORD'
        }))
        // Evitar duplicidade pelo mesmo ID caso o Singleton retenha
        .reduce((acc, current) => {
          if (!acc.find(item => item.id === current.id)) {
            acc.push(current);
          }
          return acc;
        }, [] as any[]);

      setAsrLogs(asrEvents.slice(0, 10)); // Mostrar os últimos 10
    };

    updateLogs();
    
    // Como os Logs vêm pro singleton e o `metrics` atualiza o trigger geral, atrelando ao metrics
    // já garante re-renderizações frequentes (agregado de 1 em 1s).
  }, [metrics]);

  // Inicializar sliders com valores nativos quando chegarem
  useEffect(() => {
    if (!tuningInitialized && metrics?.startHoldSeconds) {
      setTuningValues(prev => ({
        ...prev,
        speechDensityMin: metrics.speechDensityMin ?? prev.speechDensityMin,
        loudDensityMin: metrics.loudDensityMin ?? prev.loudDensityMin,
        startHoldSeconds: metrics.startHoldSeconds ?? prev.startHoldSeconds,
        endHoldSeconds: metrics.endHoldSeconds ?? prev.endHoldSeconds,
        silenceDecaySeconds: metrics.silenceDecaySeconds ?? prev.silenceDecaySeconds,
      }));
      setTuningInitialized(true);
    }
  }, [metrics, tuningInitialized]);

  // Handler para alteração de slider — envia ao nativo em tempo real
  const onTuningChange = useCallback(async (key: string, raw: number, decimal?: boolean) => {
    const val = decimal ? parseFloat(raw.toFixed(2)) : Math.round(raw);
    setTuningValues(prev => ({ ...prev, [key]: val }));
    // zcrThreshold é classificação frontend, atualiza direto no singleton
    if (key === 'zcrThreshold') {
      audioTriggerSingleton.setZcrThreshold(val);
      return;
    }
    try {
      await AudioTriggerNative.updateTuning({ [key]: val });
    } catch (e) {
      console.error('[Tuning] Error:', e);
    }
  }, []);

  // Estado GPS com atualização periódica
  const [gps, setGps] = useState<GpsState>({
    latitude: null, longitude: null, accuracy: null,
    speed: null, altitude: null, heading: null,
    lastUpdate: null, error: null,
  });

  // Atualizar GPS periodicamente quando a aba GPS está ativa
  const fetchGps = useCallback(async () => {
    try {
      const isNative = Capacitor.isNativePlatform();
      if (isNative) {
        const pos = await Geolocation.getCurrentPosition({
          enableHighAccuracy: true,
          timeout: 10000,
          maximumAge: 0,
        });
        setGps({
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
          accuracy: pos.coords.accuracy,
          speed: pos.coords.speed,
          altitude: pos.coords.altitude,
          heading: pos.coords.heading,
          lastUpdate: pos.timestamp,
          error: null,
        });
      } else {
        // Fallback web
        navigator.geolocation.getCurrentPosition(
          (pos) => {
            setGps({
              latitude: pos.coords.latitude,
              longitude: pos.coords.longitude,
              accuracy: pos.coords.accuracy,
              speed: pos.coords.speed,
              altitude: pos.coords.altitude,
              heading: pos.coords.heading,
              lastUpdate: pos.timestamp,
              error: null,
            });
          },
          (err) => setGps(prev => ({ ...prev, error: err.message })),
          { enableHighAccuracy: true, timeout: 10000 }
        );
      }
    } catch (e: any) {
      setGps(prev => ({ ...prev, error: e.message || 'Erro GPS' }));
    }
  }, []);

  useEffect(() => {
    if (activeTab === 'gps') {
      fetchGps();
      const interval = setInterval(fetchGps, 500); // Atualiza a cada 500ms
      return () => clearInterval(interval);
    }
  }, [activeTab, fetchGps]);

  // Dados de detecção
  const nativeState = metrics?.state || 'IDLE';
  const stateKey = typeof nativeState === 'string' ? nativeState : 'IDLE';
  const currentStateInfo = STATE_INFO[stateKey] || STATE_INFO['IDLE'];
  const zcr = metrics?.f0Current ?? 0;
  const isSpeech = metrics?.speechOn ?? false;
  const rmsDb = metrics?.dbfsCurrent ?? -60;
  const noiseFloor = metrics?.noiseFloorDb ?? -60;
  const score = metrics?.score ?? 0;
  const speechDensity = metrics?.speechDensity ?? 0;
  const loudDensity = metrics?.loudDensity ?? 0;
  const gender = metrics?.gender ?? 'UNKNOWN';
  const volumePct = Math.max(0, Math.min(100, ((rmsDb + 60) / 60) * 100));
  const noiseFloorPct = Math.max(0, Math.min(100, ((noiseFloor + 60) / 60) * 100));

  // Velocidade em km/h (speed vem em m/s)
  const speedKmh = gps.speed !== null && gps.speed >= 0 ? gps.speed * 3.6 : null;

  // Classificação de precisão
  const accuracyLabel = gps.accuracy === null ? 'Indisponível'
    : gps.accuracy <= 5 ? 'Excelente' : gps.accuracy <= 15 ? 'Boa'
      : gps.accuracy <= 50 ? 'Moderada' : 'Fraca';
  const accuracyColor = gps.accuracy === null ? 'text-gray-500'
    : gps.accuracy <= 5 ? 'text-green-400' : gps.accuracy <= 15 ? 'text-emerald-400'
      : gps.accuracy <= 50 ? 'text-yellow-400' : 'text-red-400';

  return (
    <div className="min-h-screen flex flex-col bg-[#0a0a0f]">
      {/* Header */}
      <header className="flex items-center gap-3 px-4 py-3 border-b border-gray-800">
        <Button variant="ghost" size="icon" onClick={() => navigate('/')}>
          <ArrowLeft className="w-5 h-5" />
        </Button>
        <h1 className="text-sm font-semibold text-gray-300 uppercase tracking-wider">Painel Técnico</h1>
      </header>

      {/* Abas */}
      <div className="flex border-b border-gray-800">
        <button
          onClick={() => setActiveTab('detection')}
          className={`flex-1 flex items-center justify-center gap-2 py-3 text-sm font-medium transition-colors ${activeTab === 'detection'
            ? 'text-white border-b-2 border-purple-500 bg-purple-500/10'
            : 'text-gray-500 hover:text-gray-300'
            }`}
        >
          <Radio className="w-4 h-4" />
          Detecção
        </button>
        <button
          onClick={() => setActiveTab('gps')}
          className={`flex-1 flex items-center justify-center gap-2 py-3 text-sm font-medium transition-colors ${activeTab === 'gps'
            ? 'text-white border-b-2 border-blue-500 bg-blue-500/10'
            : 'text-gray-500 hover:text-gray-300'
            }`}
        >
          <MapPin className="w-4 h-4" />
          GPS
        </button>
      </div>

      <main className="flex-1 p-4 space-y-4 overflow-auto pb-8">

        {/* ======= ABA DETECÇÃO ======= */}
        {activeTab === 'detection' && (
          <>
            {/* Timeline compacta — no topo */}
            <div className="bg-gray-900/60 rounded-2xl p-3 border border-gray-800">
              <div className="flex items-center justify-between mb-2">
                <span className="text-[9px] text-gray-500 uppercase tracking-wider">Timeline</span>
                <div className="flex items-center gap-1.5">
                  <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: currentStateInfo.color }} />
                  <span className="text-[10px] font-bold text-white">{currentStateInfo.label}</span>
                </div>
              </div>
              <div className="flex items-center justify-between px-1">
                {TIMELINE_STATES.map(([key, info], idx) => {
                  const isActive = key === stateKey;
                  const isPast = info.step < currentStateInfo.step;
                  return (
                    <React.Fragment key={key}>
                      {idx > 0 && <div className="flex-1 h-0.5 mx-0.5 rounded-full"
                        style={{ backgroundColor: isPast || isActive ? info.color + '80' : '#1f2937' }} />}
                      <div className="flex flex-col items-center">
                        <motion.div className="rounded-full"
                          style={{
                            width: isActive ? 14 : 8, height: isActive ? 14 : 8,
                            backgroundColor: isActive ? currentStateInfo.color : isPast ? info.color + '60' : '#374151',
                            boxShadow: isActive ? `0 0 8px ${currentStateInfo.color}` : 'none',
                          }}
                          animate={isActive ? { scale: [1, 1.2, 1] } : {}}
                          transition={{ duration: 1.5, repeat: Infinity }}
                        />
                        <span className={`text-[7px] mt-1 ${isActive ? 'text-white' : 'text-gray-600'}`}>{info.label}</span>
                      </div>
                    </React.Fragment>
                  );
                })}
              </div>
            </div>

            {/* Barras de Sensibilidade com Sliders Inline */}
            <div className="bg-gray-900/60 rounded-2xl p-4 border border-gray-800 space-y-4">
              <span className="text-xs text-gray-500 uppercase tracking-wider">Sensibilidade</span>
              {/* Score de Discussão */}
              <div className="space-y-1">
                <div className="flex justify-between items-baseline">
                  <span className="text-xs text-gray-400">Score de Discussão</span>
                  <span className={`text-lg font-mono font-black ${score >= 0.8 ? 'text-red-400' : score >= 0.5 ? 'text-yellow-400' : 'text-green-400'}`}>
                    {(score * 100).toFixed(0)}%
                  </span>
                </div>
                <div className="relative h-4 bg-gray-800 rounded-full overflow-hidden">
                  <div className="absolute inset-0 opacity-20" style={{
                    background: 'linear-gradient(to right, #22c55e, #eab308, #ef4444)'
                  }} />
                  <motion.div
                    className="absolute inset-y-0 left-0 rounded-full"
                    style={{ background: score >= 0.8 ? '#ef4444' : score >= 0.5 ? '#eab308' : '#22c55e' }}
                    animate={{ width: `${Math.min(score * 100, 100)}%` }}
                    transition={{ duration: 0.1 }}
                  />
                </div>
                {/* Slider: Janela de análise */}
                <InlineSlider label="Janela" paramKey="discussionWindowSeconds" min={5} max={30} step={1} unit="s"
                  values={tuningValues} onChange={onTuningChange} />
              </div>

              {/* Densidade de Fala + sliders */}
              <div className="space-y-1">
                <ThresholdBar
                  value={speechDensity}
                  threshold={metrics?.speechDensityMin ?? 0.35}
                  label={`Fala — Início: ${((metrics?.speechDensityMin ?? 0.35) * 100).toFixed(0)}% | Fim: ${((metrics?.speechDensityEnd ?? 0.10) * 100).toFixed(0)}%`}
                  color="#3b82f6"
                />
                <InlineSlider label="Fala Início" paramKey="speechDensityMin" min={0.1} max={0.8} step={0.05} unit="%" decimal
                  values={tuningValues} onChange={onTuningChange} />
                <InlineSlider label="Delta Voz" paramKey="vadDeltaDb" min={3} max={15} step={0.5} unit="dB" decimal
                  values={tuningValues} onChange={onTuningChange} />
              </div>

              {/* Densidade de Volume + sliders */}
              <div className="space-y-1">
                <ThresholdBar
                  value={loudDensity}
                  threshold={metrics?.loudDensityMin ?? 0.25}
                  label={`Volume — Início: ${((metrics?.loudDensityMin ?? 0.25) * 100).toFixed(0)}% | Fim: ${((metrics?.loudDensityEnd ?? 0.09) * 100).toFixed(0)}%`}
                  color="#f59e0b"
                />
                <InlineSlider label="Vol. Início" paramKey="loudDensityMin" min={0.1} max={0.6} step={0.05} unit="%" decimal
                  values={tuningValues} onChange={onTuningChange} />
                <InlineSlider label="Delta Vol." paramKey="loudDeltaDb" min={4} max={20} step={0.5} unit="dB" decimal
                  values={tuningValues} onChange={onTuningChange} />
              </div>
            </div>

            {/* Nível de Áudio (RMS + Piso unificado) */}
            <div className="bg-gray-900/60 rounded-2xl p-3 border border-gray-800">
              <div className="flex justify-between items-baseline mb-1">
                <span className="text-[9px] text-gray-500 uppercase tracking-wider">Nível de Áudio</span>
                <div className="flex items-center gap-1.5">
                  <div className={`w-2 h-2 rounded-full ${noiseFloor < -40 ? 'bg-green-500' : noiseFloor < -30 ? 'bg-yellow-500' : 'bg-red-500'}`} />
                  <span className="text-[9px] text-gray-500">
                    {noiseFloor < -40 ? 'Silencioso' : noiseFloor < -30 ? 'Moderado' : 'Ruidoso'}
                  </span>
                </div>
              </div>
              <div className="flex items-baseline justify-between">
                <div className="flex items-baseline gap-1.5">
                  <span className={`text-2xl font-mono font-black ${rmsDb > -30 ? 'text-red-400' : rmsDb > -45 ? 'text-yellow-400' : 'text-gray-300'}`}>
                    {rmsDb.toFixed(1)}
                  </span>
                  <span className="text-[10px] text-gray-500">dB</span>
                  <span className="text-[9px] text-gray-500 ml-1">Avg:</span>
                  <span className={`text-sm font-mono font-bold ${(metrics?.rmsAvg4s ?? rmsDb) > -30 ? 'text-red-400' : (metrics?.rmsAvg4s ?? rmsDb) > -45 ? 'text-yellow-400' : 'text-gray-400'}`}>
                    {(metrics?.rmsAvg4s ?? rmsDb).toFixed(1)}
                  </span>
                </div>
                <div className="flex items-baseline gap-1">
                  <span className="text-[9px] text-yellow-500/70">Piso:</span>
                  <span className="text-sm font-mono font-bold text-yellow-400">{noiseFloor.toFixed(1)}</span>
                  <span className="text-[9px] text-gray-500">dB</span>
                </div>
              </div>
              <div className="relative h-2.5 bg-gray-800 rounded-full mt-2 overflow-hidden">
                <motion.div className="absolute inset-y-0 left-0 rounded-full bg-blue-500"
                  animate={{ width: `${volumePct}%` }} transition={{ duration: 0.15 }} />
                <div className="absolute top-0 bottom-0 w-0.5 bg-yellow-500/80" style={{ left: `${noiseFloorPct}%` }}
                  title={`Piso: ${noiseFloor.toFixed(1)} dB`} />
              </div>
              <div className="flex justify-between text-[8px] text-gray-600 mt-0.5">
                <span>-60dB</span>
                <span className="text-yellow-500/50">▲ Piso</span>
                <span>0dB</span>
              </div>
            </div>

            {/* Timers de Detecção com Sliders Inline */}
            <div className="bg-gray-900/60 rounded-2xl p-4 border border-gray-800 space-y-3">
              <div className="flex justify-between items-center">
                <span className="text-xs text-gray-500 uppercase tracking-wider">Timers de Detecção</span>
                <span className="text-[10px] font-mono text-gray-600">
                  No estado: {((metrics?.timeInStateMs ?? 0) / 1000).toFixed(0)}s
                </span>
              </div>

              {/* Timer de Confirmação + slider */}
              {(() => {
                const startHold = (metrics?.startHoldSeconds ?? 6) * 1000;
                const timeInState = metrics?.timeInStateMs ?? 0;
                const isInDetected = stateKey === 'DISCUSSION_DETECTED';
                const pct = isInDetected ? Math.min((timeInState / startHold) * 100, 100) : 0;
                return (
                  <div className="space-y-1">
                    <div className="flex justify-between items-baseline">
                      <span className="text-xs text-gray-400">Confirmação início</span>
                      <span className={`text-sm font-mono font-bold ${isInDetected ? 'text-purple-400' : 'text-gray-600'}`}>
                        {isInDetected ? `${(timeInState / 1000).toFixed(0)}s / ${metrics?.startHoldSeconds ?? 6}s` : `— / ${metrics?.startHoldSeconds ?? 6}s`}
                      </span>
                    </div>
                    <div className="relative h-2.5 bg-gray-800 rounded-full overflow-hidden">
                      <motion.div className="absolute inset-y-0 left-0 rounded-full bg-purple-500"
                        animate={{ width: `${pct}%` }} transition={{ duration: 0.3 }} />
                    </div>
                    <InlineSlider label="" paramKey="startHoldSeconds" min={2} max={15} step={1} unit="s"
                      values={tuningValues} onChange={onTuningChange} />
                  </div>
                );
              })()}

              {/* Timer de Silêncio Contínuo + slider */}
              {(() => {
                const decayMs = (metrics?.silenceDecaySeconds ?? 10) * 1000;
                const contSilence = metrics?.continuousSilenceMs ?? 0;
                const isRecState = stateKey === 'RECORDING_STARTED' || stateKey === 'RECORDING';
                const pct = (isRecState && contSilence > 0) ? Math.min((contSilence / decayMs) * 100, 100) : 0;
                return (
                  <div className="space-y-1">
                    <div className="flex justify-between items-baseline">
                      <span className="text-xs text-gray-400">Silêncio contínuo</span>
                      <span className={`text-sm font-mono font-bold ${contSilence > 0 && isRecState ? 'text-yellow-400' : 'text-gray-600'}`}>
                        {isRecState && contSilence > 0
                          ? `${(contSilence / 1000).toFixed(0)}s / ${metrics?.silenceDecaySeconds ?? 10}s`
                          : `— / ${metrics?.silenceDecaySeconds ?? 10}s`}
                      </span>
                    </div>
                    <div className="relative h-2.5 bg-gray-800 rounded-full overflow-hidden">
                      <motion.div className="absolute inset-y-0 left-0 rounded-full bg-yellow-500"
                        animate={{ width: `${pct}%` }} transition={{ duration: 0.3 }} />
                    </div>
                    <InlineSlider label="" paramKey="silenceDecaySeconds" min={3} max={30} step={1} unit="s"
                      values={tuningValues} onChange={onTuningChange} />
                  </div>
                );
              })()}

              {/* Timer de Countdown + slider */}
              {(() => {
                const endHoldMs = (metrics?.endHoldSeconds ?? 30) * 1000;
                const timeInState = metrics?.timeInStateMs ?? 0;
                const isEnding = stateKey === 'DISCUSSION_ENDING';
                const pct = isEnding ? Math.min((timeInState / endHoldMs) * 100, 100) : 0;
                return (
                  <div className="space-y-1">
                    <div className="flex justify-between items-baseline">
                      <span className="text-xs text-gray-400">Countdown parada</span>
                      <span className={`text-sm font-mono font-bold ${isEnding ? 'text-orange-400' : 'text-gray-600'}`}>
                        {isEnding ? `${(timeInState / 1000).toFixed(0)}s / ${metrics?.endHoldSeconds ?? 30}s` : `— / ${metrics?.endHoldSeconds ?? 30}s`}
                      </span>
                    </div>
                    <div className="relative h-2.5 bg-gray-800 rounded-full overflow-hidden">
                      <motion.div className="absolute inset-y-0 left-0 rounded-full bg-orange-500"
                        animate={{ width: `${pct}%` }} transition={{ duration: 0.3 }} />
                    </div>
                    <InlineSlider label="" paramKey="endHoldSeconds" min={10} max={120} step={5} unit="s"
                      values={tuningValues} onChange={onTuningChange} />
                  </div>
                );
              })()}

              {/* Timer de Cooldown */}
              {(() => {
                const cooldownMs = (metrics?.cooldownSeconds ?? 45) * 1000;
                const timeInState = metrics?.timeInStateMs ?? 0;
                const isCooldown = stateKey === 'COOLDOWN';
                const pct = isCooldown ? Math.min((timeInState / cooldownMs) * 100, 100) : 0;
                return (
                  <div className="space-y-1">
                    <div className="flex justify-between items-baseline">
                      <span className="text-xs text-gray-400">Cooldown</span>
                      <span className={`text-sm font-mono font-bold ${isCooldown ? 'text-blue-400' : 'text-gray-600'}`}>
                        {isCooldown ? `${(timeInState / 1000).toFixed(0)}s / ${metrics?.cooldownSeconds ?? 45}s` : `— / ${metrics?.cooldownSeconds ?? 45}s`}
                      </span>
                    </div>
                    <div className="relative h-2.5 bg-gray-800 rounded-full overflow-hidden">
                      <motion.div className="absolute inset-y-0 left-0 rounded-full bg-blue-500"
                        animate={{ width: `${pct}%` }} transition={{ duration: 0.3 }} />
                    </div>
                  </div>
                );
              })()}
            </div>

            {/* Box: Detecções ASR (Reconhecimento de Palavras CHAVE) */}
            <div className="bg-gray-900/60 rounded-2xl p-4 border border-gray-800 space-y-3">
              <div className="flex justify-between items-center">
                <span className="text-xs text-gray-500 uppercase tracking-wider">Detecções ASR / IA</span>
                <span className="text-[10px] text-gray-600 bg-gray-800 px-2 py-0.5 rounded-full">
                  Últimos 10 comandos
                </span>
              </div>
              
              <div className="space-y-2 mt-2">
                {asrLogs.length === 0 ? (
                  <div className="text-[10px] text-gray-500 italic text-center py-2 border border-dashed border-gray-700 rounded-lg">
                    Nenhuma palavra reconhecida ainda.
                  </div>
                ) : (
                  asrLogs.map(log => {
                    const confPct = Math.round(log.confidence * 100);
                    return (
                      <div key={log.id} className="flex flex-col bg-gray-800/50 p-2 rounded-lg border border-gray-700/50">
                        <div className="flex justify-between items-center">
                          <span className="text-xs font-bold text-white uppercase tracking-wider border-b border-purple-500/50 pb-0.5 mb-1">
                            {log.phraseId}
                          </span>
                          <span className="text-[9px] text-gray-400">
                            {log.time.toLocaleTimeString('pt-BR', { hour12: false })}
                          </span>
                        </div>
                        <div className="flex items-center gap-2 mt-1">
                          <div className="flex-1 h-1.5 bg-gray-700 rounded-full overflow-hidden">
                            <motion.div 
                              className="h-full bg-purple-500"
                              initial={{ width: 0 }}
                              animate={{ width: `${confPct}%` }}
                            />
                          </div>
                          <span className={`text-[10px] font-mono leading-none ${confPct >= 80 ? 'text-green-400' : confPct >= 50 ? 'text-yellow-400' : 'text-red-400'}`}>
                            {confPct}%
                          </span>
                          <span className="text-[8px] bg-purple-900/30 text-purple-300 px-1 py-0.5 rounded ml-1">
                            {log.phraseType}
                          </span>
                        </div>
                      </div>
                    )
                  })
                )}
              </div>
            </div>

            {/* Gravação Nativa + Silêncio Absoluto */}
            <div className="bg-gray-900/60 rounded-2xl p-4 border border-gray-800 space-y-3">
              <div className="flex justify-between items-center">
                <span className="text-xs text-gray-500 uppercase tracking-wider">Gravação Nativa</span>
                <div className="flex items-center gap-2">
                  <motion.div className="w-3 h-3 rounded-full"
                    style={{ backgroundColor: metrics?.isNativeRecording ? '#ef4444' : '#374151' }}
                    animate={metrics?.isNativeRecording ? { scale: [1, 1.3, 1] } : {}}
                    transition={{ duration: 0.8, repeat: Infinity }} />
                  <span className={`text-xs font-bold ${metrics?.isNativeRecording ? 'text-red-400' : 'text-gray-600'}`}>
                    {metrics?.isNativeRecording ? (metrics?.isManualRecording ? 'MANUAL' : 'AUTO') : 'Inativo'}
                  </span>
                </div>
              </div>
              {/* Barra de timeout de silêncio absoluto (10 min) */}
              <div className="space-y-1">
                <div className="flex justify-between items-baseline">
                  <span className="text-xs text-gray-400">Silêncio absoluto</span>
                  <span className={`text-sm font-mono font-bold ${metrics?.isSilent ? 'text-yellow-400' : 'text-gray-600'}`}>
                    {metrics?.isSilent
                      ? `${Math.floor((metrics?.silenceDurationMs ?? 0) / 1000)}s / ${Math.floor((metrics?.silenceTimeoutMs ?? 600000) / 1000)}s`
                      : `0s / ${Math.floor((metrics?.silenceTimeoutMs ?? 600000) / 1000)}s`}
                  </span>
                </div>
                <div className="relative h-2.5 bg-gray-800 rounded-full overflow-hidden">
                  <motion.div className="absolute inset-y-0 left-0 rounded-full bg-red-600"
                    animate={{ width: `${metrics?.isSilent ? Math.min(((metrics?.silenceDurationMs ?? 0) / (metrics?.silenceTimeoutMs ?? 600000)) * 100, 100) : 0}%` }}
                    transition={{ duration: 0.3 }} />
                </div>
                <div className="flex justify-between text-[9px] text-gray-500">
                  <span>Threshold: {metrics?.silenceThresholdDb ?? -40}dB</span>
                  <span>Timeout: {Math.floor((metrics?.silenceTimeoutMs ?? 600000) / 60000)}min</span>
                </div>
              </div>
            </div>



            {/* Frequência de Voz com média e slider */}
            <div className="bg-gray-900/60 rounded-2xl p-3 border border-gray-800">
              <div className="flex items-center justify-between mb-1">
                <span className="text-[9px] text-gray-500 uppercase tracking-wider">Frequência de Voz</span>
                <div className="flex items-center gap-2">
                  <span className="text-[9px] text-gray-500">ZCR: <span className="font-mono text-gray-300">{zcr > 0 ? zcr.toFixed(3) : '—'}</span></span>
                  <span className="text-[9px] text-gray-500">Avg: <span className="font-mono text-gray-300">{(metrics?.zcrAvg4s ?? 0) > 0 ? (metrics?.zcrAvg4s ?? 0).toFixed(3) : '—'}</span></span>
                  <span className={`text-lg font-black ${!isSpeech ? 'text-gray-600' : gender === 'MALE' ? 'text-blue-400' : 'text-pink-400'}`}>
                    {!isSpeech ? '—' : gender === 'MALE' ? 'H' : gender === 'FEMALE' ? 'M' : '?'}
                  </span>
                </div>
              </div>
              {(() => {
                const zcrThreshold = tuningValues.zcrThreshold ?? 0.12;
                return (
                  <>
                    <div className="relative h-4 bg-gray-800 rounded-full overflow-hidden">
                      <div className="absolute inset-y-0 bg-blue-500/20 rounded-l-full"
                        style={{ left: `${(0.02 / 0.25) * 100}%`, width: `${((zcrThreshold - 0.02) / 0.25) * 100}%` }} />
                      <div className="absolute inset-y-0 bg-pink-500/20"
                        style={{ left: `${(zcrThreshold / 0.25) * 100}%`, width: `${((0.22 - zcrThreshold) / 0.25) * 100}%` }} />
                      {zcr > 0 && isSpeech && (
                        <motion.div className="absolute top-0 bottom-0 w-1 rounded-full shadow-lg"
                          style={{
                            backgroundColor: gender === 'MALE' ? '#60a5fa' : gender === 'FEMALE' ? '#f472b6' : '#9ca3af',
                            boxShadow: `0 0 6px ${gender === 'MALE' ? '#60a5fa' : '#f472b6'}`,
                          }}
                          animate={{ left: `${Math.min((zcr / 0.25) * 100, 100)}%` }} transition={{ duration: 0.2 }}
                        />
                      )}
                      {/* Marcador da média ZCR 4s */}
                      {(metrics?.zcrAvg4s ?? 0) > 0 && isSpeech && (
                        <div className="absolute top-0 bottom-0 w-0.5 bg-white/50"
                          style={{ left: `${Math.min(((metrics?.zcrAvg4s ?? 0) / 0.25) * 100, 100)}%` }} />
                      )}
                    </div>
                    <div className="flex justify-between text-[8px] text-gray-600 mt-0.5">
                      <span className="text-blue-500/60">H: 0.02-{zcrThreshold.toFixed(2)}</span>
                      <span className="text-pink-500/60">M: {zcrThreshold.toFixed(2)}-0.22</span>
                    </div>
                    <InlineSlider label="Limiar H/M" paramKey="zcrThreshold" min={0.06} max={0.18} step={0.01} unit="" decimal
                      values={tuningValues} onChange={onTuningChange} />
                  </>
                );
              })()}
            </div>

            {/* LEDs Fala + Volume */}
            <div className="grid grid-cols-2 gap-3">
              <div className="bg-gray-900/60 rounded-2xl p-3 border border-gray-800">
                <span className="text-[9px] text-gray-500 uppercase tracking-wider">Fala</span>
                <div className="mt-1 flex items-center gap-2">
                  <motion.div className="w-4 h-4 rounded-full"
                    style={{ backgroundColor: isSpeech ? '#22c55e' : '#374151' }}
                    animate={isSpeech ? { scale: [1, 1.3, 1] } : {}}
                    transition={{ duration: 0.8, repeat: Infinity }} />
                  <span className={`text-sm font-bold ${isSpeech ? 'text-green-400' : 'text-gray-600'}`}>
                    {isSpeech ? 'ATIVA' : 'Silêncio'}
                  </span>
                </div>
              </div>
              <div className="bg-gray-900/60 rounded-2xl p-3 border border-gray-800">
                <span className="text-[9px] text-gray-500 uppercase tracking-wider">Volume</span>
                <div className="mt-1 flex items-center gap-2">
                  <motion.div className="w-4 h-4 rounded-full"
                    style={{ backgroundColor: metrics?.loudOn ? '#ef4444' : '#374151' }}
                    animate={metrics?.loudOn ? { scale: [1, 1.3, 1] } : {}}
                    transition={{ duration: 0.8, repeat: Infinity }} />
                  <span className={`text-sm font-bold ${metrics?.loudOn ? 'text-red-400' : 'text-gray-600'}`}>
                    {metrics?.loudOn ? 'ALTO' : 'Normal'}
                  </span>
                </div>
              </div>
            </div>

            {/* Botão Restaurar Padrões */}
            <button
              onClick={async () => {
                setTuningValues({ ...DEFAULTS });
                try { await AudioTriggerNative.updateTuning(DEFAULTS); } catch (e) { console.error(e); }
              }}
              className="w-full py-2 px-4 bg-gray-800 hover:bg-gray-700 text-xs text-gray-400 hover:text-white rounded-lg transition-colors border border-gray-700"
            >
              🔄 Restaurar Padrões
            </button>
          </>
        )}

        {/* ======= ABA GPS ======= */}
        {activeTab === 'gps' && (
          <>
            {/* Velocidade */}
            <div className="bg-gray-900/60 rounded-2xl p-5 border border-gray-800 flex flex-col items-center">
              <span className="text-xs text-gray-500 uppercase tracking-wider mb-3">Velocidade</span>
              <div className="flex items-baseline gap-1">
                <span className="text-5xl font-mono font-black text-white">
                  {speedKmh !== null ? speedKmh.toFixed(1) : '—'}
                </span>
                <span className="text-lg text-gray-500 font-medium">km/h</span>
              </div>
              {gps.heading !== null && gps.heading >= 0 && (
                <div className="mt-2 flex items-center gap-1.5 text-gray-400">
                  <Navigation className="w-3.5 h-3.5" style={{ transform: `rotate(${gps.heading}deg)` }} />
                  <span className="text-xs">{gps.heading.toFixed(0)}°</span>
                </div>
              )}
            </div>

            {/* Precisão e Altitude */}
            <div className="grid grid-cols-2 gap-3">
              <div className="bg-gray-900/60 rounded-2xl p-4 border border-gray-800">
                <div className="flex items-center gap-1.5 mb-2">
                  <Crosshair className="w-3.5 h-3.5 text-gray-500" />
                  <span className="text-[9px] text-gray-500 uppercase tracking-wider">Precisão</span>
                </div>
                <div>
                  <span className={`text-2xl font-mono font-black ${accuracyColor}`}>
                    {gps.accuracy !== null ? gps.accuracy.toFixed(0) : '—'}
                  </span>
                  <span className="text-xs text-gray-500 ml-1">m</span>
                </div>
                <div className="mt-1 flex items-center gap-1.5">
                  <div className={`w-2 h-2 rounded-full ${gps.accuracy === null ? 'bg-gray-600' :
                    gps.accuracy <= 5 ? 'bg-green-500' : gps.accuracy <= 15 ? 'bg-emerald-500' :
                      gps.accuracy <= 50 ? 'bg-yellow-500' : 'bg-red-500'
                    }`} />
                  <span className="text-[10px] text-gray-500">{accuracyLabel}</span>
                </div>
              </div>

              <div className="bg-gray-900/60 rounded-2xl p-4 border border-gray-800">
                <span className="text-[9px] text-gray-500 uppercase tracking-wider">Altitude</span>
                <div className="mt-2">
                  <span className="text-2xl font-mono font-black text-gray-300">
                    {gps.altitude !== null ? gps.altitude.toFixed(0) : '—'}
                  </span>
                  <span className="text-xs text-gray-500 ml-1">m</span>
                </div>
              </div>
            </div>

            {/* Coordenadas */}
            <div className="bg-gray-900/60 rounded-2xl p-4 border border-gray-800">
              <div className="flex items-center gap-1.5 mb-3">
                <MapPin className="w-3.5 h-3.5 text-gray-500" />
                <span className="text-xs text-gray-500 uppercase tracking-wider">Coordenadas</span>
              </div>
              <div className="space-y-2">
                <div className="flex justify-between">
                  <span className="text-xs text-gray-500">Latitude</span>
                  <span className="text-sm font-mono text-gray-300">
                    {gps.latitude !== null ? gps.latitude.toFixed(6) : '—'}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-xs text-gray-500">Longitude</span>
                  <span className="text-sm font-mono text-gray-300">
                    {gps.longitude !== null ? gps.longitude.toFixed(6) : '—'}
                  </span>
                </div>
              </div>
              {gps.lastUpdate && (
                <div className="mt-3 pt-2 border-t border-gray-800">
                  <span className="text-[10px] text-gray-600">
                    Última atualização: {new Date(gps.lastUpdate).toLocaleTimeString('pt-BR')}
                  </span>
                </div>
              )}
            </div>

            {/* Erro GPS */}
            {gps.error && (
              <div className="bg-red-900/20 rounded-2xl p-3 border border-red-800/50">
                <span className="text-xs text-red-400">⚠️ {gps.error}</span>
              </div>
            )}
          </>
        )}

      </main>
    </div>
  );
}
