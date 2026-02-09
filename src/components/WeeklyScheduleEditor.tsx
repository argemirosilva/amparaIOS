import React, { useState, useEffect } from 'react';
import { Plus, Trash2, AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/components/ui/accordion';
import { SchedulePeriod, DayOfWeek, WeekSchedule } from '@/lib/api_settings';

interface WeeklyScheduleEditorProps {
  initialSchedule: WeekSchedule;
  onScheduleChange: (modifiedDays: WeekSchedule) => void;
}

const DAY_NAMES: Record<DayOfWeek, string> = {
  seg: 'Segunda-feira',
  ter: 'Terça-feira',
  qua: 'Quarta-feira',
  qui: 'Quinta-feira',
  sex: 'Sexta-feira',
  sab: 'Sábado',
  dom: 'Domingo',
};

const DAYS_ORDER: DayOfWeek[] = ['seg', 'ter', 'qua', 'qui', 'sex', 'sab', 'dom'];

// Validation functions
function parseTime(time: string): number | null {
  const match = time.match(/^(\d{2}):(\d{2})$/);
  if (!match) return null;
  const hours = parseInt(match[1], 10);
  const minutes = parseInt(match[2], 10);
  if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return null;
  return hours * 60 + minutes;
}

function validatePeriod(period: SchedulePeriod): string | null {
  const start = parseTime(period.inicio);
  const end = parseTime(period.fim);

  if (start === null) return 'Formato de hora inválido para início';
  if (end === null) return 'Formato de hora inválido para fim';
  if (start >= end) return 'Horário de início deve ser menor que o fim';

  return null;
}

function checkOverlap(periods: SchedulePeriod[]): boolean {
  for (let i = 0; i < periods.length; i++) {
    for (let j = i + 1; j < periods.length; j++) {
      const start1 = parseTime(periods[i].inicio)!;
      const end1 = parseTime(periods[i].fim)!;
      const start2 = parseTime(periods[j].inicio)!;
      const end2 = parseTime(periods[j].fim)!;

      // Check if periods overlap
      if (start1 < end2 && start2 < end1) {
        return true;
      }
    }
  }
  return false;
}

function calculateTotalMinutes(periods: SchedulePeriod[]): number {
  return periods.reduce((total, period) => {
    const start = parseTime(period.inicio);
    const end = parseTime(period.fim);
    if (start === null || end === null) return total;
    return total + (end - start);
  }, 0);
}

export function WeeklyScheduleEditor({ initialSchedule, onScheduleChange }: WeeklyScheduleEditorProps) {
  const [schedule, setSchedule] = useState<WeekSchedule>(initialSchedule);
  const [modifiedDays, setModifiedDays] = useState<Set<DayOfWeek>>(new Set());
  const [editingDay, setEditingDay] = useState<DayOfWeek | null>(null);
  const [editingPeriod, setEditingPeriod] = useState<{ inicio: string; fim: string }>({ inicio: '', fim: '' });
  const [errors, setErrors] = useState<Record<DayOfWeek, string>>({} as Record<DayOfWeek, string>);

  // Update schedule when initialSchedule changes (e.g., loaded from server)
  useEffect(() => {
    setSchedule(initialSchedule);
  }, [initialSchedule]);

  useEffect(() => {
    // Notify parent of modified days
    const modified: WeekSchedule = {};
    modifiedDays.forEach(day => {
      modified[day] = schedule[day] || [];
    });
    onScheduleChange(modified);
  }, [schedule, modifiedDays, onScheduleChange]);

  const handleAddPeriod = (day: DayOfWeek) => {
    setEditingDay(day);
    setEditingPeriod({ inicio: '', fim: '' });
  };

  const handleSavePeriod = (day: DayOfWeek) => {
    const error = validatePeriod(editingPeriod);
    if (error) {
      setErrors({ ...errors, [day]: error });
      return;
    }

    const currentPeriods = schedule[day] || [];
    const newPeriods = [...currentPeriods, editingPeriod];

    // Check overlap
    if (checkOverlap(newPeriods)) {
      setErrors({ ...errors, [day]: 'Períodos não podem se sobrepor' });
      return;
    }

    // Check 8-hour limit
    const totalMinutes = calculateTotalMinutes(newPeriods);
    if (totalMinutes > 480) {
      setErrors({ ...errors, [day]: `${DAY_NAMES[day]} excede 8 horas de monitoramento` });
      return;
    }

    // Check max 6 periods per day
    if (newPeriods.length > 6) {
      setErrors({ ...errors, [day]: 'Máximo de 6 períodos por dia' });
      return;
    }

    setSchedule({ ...schedule, [day]: newPeriods });
    setModifiedDays(new Set(modifiedDays).add(day));
    setEditingDay(null);
    setEditingPeriod({ inicio: '', fim: '' });
    
    // Clear error for this day
    const newErrors = { ...errors };
    delete newErrors[day];
    setErrors(newErrors);
  };

  const handleRemovePeriod = (day: DayOfWeek, index: number) => {
    const currentPeriods = schedule[day] || [];
    const newPeriods = currentPeriods.filter((_, i) => i !== index);
    setSchedule({ ...schedule, [day]: newPeriods });
    setModifiedDays(new Set(modifiedDays).add(day));
  };

  const handleClearDay = (day: DayOfWeek) => {
    setSchedule({ ...schedule, [day]: [] });
    setModifiedDays(new Set(modifiedDays).add(day));
  };

  const getTotalHours = (day: DayOfWeek): string => {
    const periods = schedule[day] || [];
    const totalMinutes = calculateTotalMinutes(periods);
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    return `${hours}h${minutes > 0 ? ` ${minutes}min` : ''}`;
  };

  return (
    <div className="space-y-4">
      <Accordion type="single" collapsible className="w-full">
        {DAYS_ORDER.map((day) => {
          const periods = schedule[day] || [];
          const hasError = !!errors[day];
          const totalHours = getTotalHours(day);

          return (
            <AccordionItem key={day} value={day}>
              <AccordionTrigger className="hover:no-underline">
                <div className="flex items-center justify-between w-full pr-4">
                  <span className="font-medium">{DAY_NAMES[day]}</span>
                  <div className="flex items-center gap-2 text-sm">
                    {periods.length > 0 && (
                      <span className="text-muted-foreground">
                        {periods.length} período{periods.length > 1 ? 's' : ''} • {totalHours}
                      </span>
                    )}
                    {hasError && <AlertCircle className="h-4 w-4 text-destructive" />}
                  </div>
                </div>
              </AccordionTrigger>
              <AccordionContent>
                <div className="space-y-3 pt-2">
                  {/* Error message */}
                  {hasError && (
                    <div className="bg-destructive/10 text-destructive text-sm p-2 rounded">
                      {errors[day]}
                    </div>
                  )}

                  {/* Existing periods */}
                  {periods.map((period, index) => (
                    <div key={index} className="flex items-center gap-2 bg-muted p-2 rounded">
                      <span className="flex-1 text-sm">
                        {period.inicio} - {period.fim}
                      </span>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-8 w-8"
                        onClick={() => handleRemovePeriod(day, index)}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  ))}

                  {/* Add period form */}
                  {editingDay === day ? (
                    <div className="space-y-3 bg-card border border-border p-3 rounded">
                      <div className="grid grid-cols-2 gap-3">
                        <div className="space-y-1.5">
                          <Label className="text-xs">Ínicio</Label>
                          <Input
                            type="time"
                            value={editingPeriod.inicio}
                            onChange={(e) => setEditingPeriod({ ...editingPeriod, inicio: e.target.value })}
                            className="h-9"
                          />
                        </div>
                        <div className="space-y-1.5">
                          <Label className="text-xs">Fim</Label>
                          <Input
                            type="time"
                            value={editingPeriod.fim}
                            onChange={(e) => setEditingPeriod({ ...editingPeriod, fim: e.target.value })}
                            className="h-9"
                          />
                        </div>
                      </div>
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          onClick={() => handleSavePeriod(day)}
                          className="flex-1"
                        >
                          Salvar Período
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            setEditingDay(null);
                            setEditingPeriod({ inicio: '', fim: '' });
                          }}
                          className="flex-1"
                        >
                          Cancelar
                        </Button>
                      </div>
                    </div>
                  ) : (
                    <div className="flex gap-2">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => handleAddPeriod(day)}
                        className="flex-1"
                      >
                        <Plus className="h-4 w-4 mr-1" />
                        Adicionar Período
                      </Button>
                      {periods.length > 0 && (
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => handleClearDay(day)}
                          className="text-destructive hover:text-destructive"
                        >
                          Limpar Dia
                        </Button>
                      )}
                    </div>
                  )}
                </div>
              </AccordionContent>
            </AccordionItem>
          );
        })}
      </Accordion>

      {/* Summary */}
      {modifiedDays.size > 0 && (
        <div className="bg-primary/5 border border-primary/20 rounded-lg p-3">
          <p className="text-sm text-muted-foreground">
            <strong>{modifiedDays.size}</strong> dia{modifiedDays.size > 1 ? 's' : ''} modificado{modifiedDays.size > 1 ? 's' : ''}
          </p>
        </div>
      )}
    </div>
  );
}
