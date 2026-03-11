import { useState, useEffect } from 'react';
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Lock, Calendar, Eye, EyeOff, Bell, Mic } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useToast } from '@/hooks/use-toast';

import { changePassword, updateSchedules, WeekSchedule, validatePassword } from '@/lib/api_settings';
import { clearSessionToken } from '@/lib/api';
import { PeriodosSemana } from '@/lib/types';
import { getCurrentConfig, forceSyncConfig } from '@/services/configService';
import { WeeklyScheduleEditor } from '@/components/WeeklyScheduleEditor';
import { PasswordValidationDialog } from '@/components/PasswordValidationDialog';
import { AudioTriggerNative } from '@/plugins/audioTriggerNative';

export default function SettingsPage() {
  const navigate = useNavigate();
  const { toast } = useToast();

  // Password Validation State (for coercion detection)
  const [showPasswordDialog, setShowPasswordDialog] = useState(true);
  const [isValidatingPassword, setIsValidatingPassword] = useState(false);
  const [isCoercionMode, setIsCoercionMode] = useState(false);
  const [isAuthenticated, setIsAuthenticated] = useState(false);

  // Change Password State
  const [senhaAtual, setSenhaAtual] = useState('');
  const [novaSenha, setNovaSenha] = useState('');
  const [confirmarSenha, setConfirmarSenha] = useState('');
  const [showSenhaAtual, setShowSenhaAtual] = useState(false);
  const [showNovaSenha, setShowNovaSenha] = useState(false);
  const [showConfirmarSenha, setShowConfirmarSenha] = useState(false);
  const [isChangingPassword, setIsChangingPassword] = useState(false);

  // Monitoring Periods State
  const [initialSchedule, setInitialSchedule] = useState<WeekSchedule>({});
  const [modifiedSchedule, setModifiedSchedule] = useState<WeekSchedule>({});
  const [isSavingSchedule, setIsSavingSchedule] = useState(false);

  // Estado das notificações de eventos
  const [notificationsEnabled, setNotificationsEnabled] = useState(true);

  // Carregar preferência de notificações ao autenticar
  useEffect(() => {
    if (isAuthenticated) {
      AudioTriggerNative.getNotificationPreference()
        .then(({ enabled }) => setNotificationsEnabled(enabled))
        .catch(() => { });
    }
  }, [isAuthenticated]);

  // Load initial schedule from ConfigService (force sync from server)
  useEffect(() => {
    if (isAuthenticated) {
      const loadSchedule = async () => {
        try {
          // Forçar sincronização com o servidor ao entrar nas configurações
          console.log('[Settings] Force syncing config from server...');
          const synced = await forceSyncConfig();

          if (synced) {
            console.log('[Settings] Config synced successfully from server');
          } else {
            console.warn('[Settings] Force sync failed, using cached config');
          }

          // Carregar config atualizada (do servidor ou cache)
          const config = getCurrentConfig();
          if (config?.periodos_semana) {
            console.log('[Settings] Loading schedule:', JSON.stringify(config.periodos_semana, null, 2));
            setInitialSchedule(config.periodos_semana as WeekSchedule);
          } else {
            console.log('[Settings] No periodos_semana found in config');
            console.log('[Settings] Full config:', JSON.stringify(config, null, 2));
          }
        } catch (error) {
          console.error('[Settings] Failed to load schedule:', error);
        }
      };

      loadSchedule();
    }
  }, [isAuthenticated]);

  // Password Validation Handler
  const handlePasswordValidation = async (senha: string) => {
    setIsValidatingPassword(true);

    try {
      const result = await validatePassword(senha);

      if (result.error) {
        toast({
          title: 'Erro',
          description: result.error,
          variant: 'destructive',
        });
        setIsValidatingPassword(false);
        return;
      }

      if (result.data?.success) {
        const isCoercion = result.data.loginTipo === 'coacao';
        setIsCoercionMode(isCoercion);
        setIsAuthenticated(true);
        setShowPasswordDialog(false);

        if (isCoercion) {
          console.log('[Settings] Coercion mode activated - changes will be simulated');
        }
      } else {
        toast({
          title: 'Erro',
          description: 'Senha incorreta',
          variant: 'destructive',
        });
      }
    } catch (error) {
      console.error('[Settings] Error validating password:', error);
      toast({
        title: 'Erro',
        description: 'Erro ao validar senha',
        variant: 'destructive',
      });
    } finally {
      setIsValidatingPassword(false);
    }
  };

  const handlePasswordCancel = () => {
    navigate('/');
  };

  // Change Password Handler (respects coercion mode)
  const handleChangePassword = async () => {
    // In coercion mode, simulate success without actually changing
    if (isCoercionMode) {
      console.log('[Settings] Coercion mode: simulating password change');
      toast({
        title: 'Sucesso',
        description: 'Senha alterada com sucesso',
      });
      setSenhaAtual('');
      setNovaSenha('');
      setConfirmarSenha('');
      document.querySelectorAll('input').forEach(input => input.blur());
      return;
    }

    // Normal mode: actual password change
    // Client-side validations
    if (!senhaAtual.trim()) {
      toast({
        title: 'Erro',
        description: 'Digite sua senha atual',
        variant: 'destructive',
      });
      return;
    }

    if (!novaSenha.trim()) {
      toast({
        title: 'Erro',
        description: 'Digite a nova senha',
        variant: 'destructive',
      });
      return;
    }

    if (novaSenha.length < 6) {
      toast({
        title: 'Erro',
        description: 'A nova senha deve ter no mínimo 6 caracteres',
        variant: 'destructive',
      });
      return;
    }

    if (novaSenha !== confirmarSenha) {
      toast({
        title: 'Erro',
        description: 'As senhas não coincidem',
        variant: 'destructive',
      });
      return;
    }

    setIsChangingPassword(true);

    try {
      const result = await changePassword(senhaAtual, novaSenha);

      if (result.error) {
        // Check for session expiration
        if (result.error.includes('Sessão')) {
          await clearSessionToken();
          toast({
            title: 'Sessão Expirada',
            description: 'Faça login novamente',
            variant: 'destructive',
          });
          navigate('/login');
          return;
        }

        toast({
          title: 'Erro',
          description: result.error,
          variant: 'destructive',
        });
        return;
      }

      if (result.data?.success) {
        toast({
          title: 'Sucesso',
          description: 'Senha alterada com sucesso',
        });

        // Clear fields
        setSenhaAtual('');
        setNovaSenha('');
        setConfirmarSenha('');

        // Close keyboard (blur all inputs)
        document.querySelectorAll('input').forEach(input => input.blur());
      } else {
        toast({
          title: 'Erro',
          description: (result.data as any)?.error || 'Erro ao alterar senha',
          variant: 'destructive',
        });
      }
    } catch (error) {
      console.error('[Settings] Error changing password:', error);
      toast({
        title: 'Erro',
        description: 'Erro inesperado ao alterar senha',
        variant: 'destructive',
      });
    } finally {
      setIsChangingPassword(false);
    }
  };

  // Save Schedule Handler (respects coercion mode)
  const handleSaveSchedule = async () => {
    setIsSavingSchedule(true);

    // In coercion mode, simulate success without actually saving
    if (isCoercionMode) {
      console.log('[Settings] Coercion mode: simulating schedule save');
      await new Promise(resolve => setTimeout(resolve, 1000)); // Simulate API delay
      toast({
        title: 'Sucesso',
        description: 'Agenda atualizada com sucesso',
      });
      setInitialSchedule({ ...initialSchedule, ...modifiedSchedule });
      setModifiedSchedule({});
      setIsSavingSchedule(false);
      return;
    }

    // Normal mode: actual schedule save
    try {
      const result = await updateSchedules({ ...initialSchedule, ...modifiedSchedule });

      if (result.error) {
        // Check for session expiration
        if (result.error.includes('Sessão')) {
          await clearSessionToken();
          toast({
            title: 'Sessão Expirada',
            description: 'Faça login novamente',
            variant: 'destructive',
          });
          navigate('/login');
          return;
        }

        toast({
          title: 'Erro',
          description: result.error,
          variant: 'destructive',
        });
        return;
      }

      if (result.data?.success) {
        // Full success
        const message = result.data.message || 'Agenda atualizada com sucesso';
        toast({
          title: 'Sucesso',
          description: message,
        });

        // Update initial schedule to reflect saved changes
        setInitialSchedule({ ...initialSchedule, ...modifiedSchedule });
        setModifiedSchedule({});

        // Force config refresh to update monitoring status and native service immediately
        console.log('[Settings] Force syncing config after schedule update (ignoring cache)...');
        const success = await forceSyncConfig();
        if (success) {
          console.log('[Settings] Config force synced successfully');
        } else {
          console.warn('[Settings] Config force sync failed, but schedule was saved');
        }
      } else {
        // Partial success or full error
        const errorMessage = result.data?.message || 'Erro ao atualizar agenda';
        const errors = result.data?.errors || [];

        // Show main message
        toast({
          title: errors.length > 0 ? 'Atenção' : 'Erro',
          description: errorMessage,
          variant: errors.length > 0 ? 'default' : 'destructive',
        });

        // Show individual errors if present
        if (errors.length > 0) {
          setTimeout(() => {
            errors.forEach((error: string, index: number) => {
              setTimeout(() => {
                toast({
                  title: 'Erro de Validação',
                  description: error,
                  variant: 'destructive',
                });
              }, index * 500);
            });
          }, 500);
        }

        // If partial success, update only the successful changes
        if (result.data?.periodos_atualizados && result.data.periodos_atualizados > 0) {
          setInitialSchedule({ ...initialSchedule, ...modifiedSchedule });
          setModifiedSchedule({});
        }
      }
    } catch (error) {
      console.error('[Settings] Error updating schedule:', error);
      toast({
        title: 'Erro',
        description: 'Erro inesperado ao atualizar agenda',
        variant: 'destructive',
      });
    } finally {
      setIsSavingSchedule(false);
    }
  };

  // Don't render settings until authenticated
  if (!isAuthenticated) {
    return (
      <>
        <PasswordValidationDialog
          isOpen={showPasswordDialog}
          title="Acesso às Configurações"
          description="Digite sua senha para acessar as configurações do aplicativo."
          onValidate={handlePasswordValidation}
          onCancel={handlePasswordCancel}
          isValidating={isValidatingPassword}
        />
        <div className="min-h-screen bg-app-deep flex items-center justify-center">
          <div className="w-8 h-8 border-2 border-primary border-t-transparent rounded-full animate-spin" />
        </div>
      </>
    );
  }

  return (
    <div className="min-h-screen bg-app-deep flex flex-col safe-area-inset-top safe-area-inset-bottom">
      {/* Header */}
      <motion.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="sticky top-0 z-10 bg-background/70 backdrop-blur-md border-b border-border/70 px-4 pb-4"
        style={{
          paddingTop: 'calc(env(safe-area-inset-top) + 0.75rem)',
          minHeight: 'calc(env(safe-area-inset-top) + 6.5rem)',
        }}
      >
        <div className="flex items-end h-full">
          <h1 className="text-lg font-semibold">Configurações</h1>
        </div>
      </motion.div>

      {/* Content */}
      <div className="flex-1 overflow-auto px-4 py-6 space-y-6">

        {/* Change Password Section */}
        <motion.div
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          className="card-glass-dark rounded-2xl p-6"
        >
          <div className="flex items-center gap-2 mb-4">
            <Lock className="h-5 w-5 text-primary" />
            <h2 className="text-lg font-semibold text-foreground">Alterar Senha</h2>
          </div>

          <div className="space-y-4">
            {/* Senha Atual */}
            <div className="space-y-2">
              <Label htmlFor="senha-atual">Senha Atual</Label>
              <div className="relative">
                <Input
                  id="senha-atual"
                  type={showSenhaAtual ? 'text' : 'password'}
                  value={senhaAtual}
                  onChange={(e) => setSenhaAtual(e.target.value)}
                  placeholder="Digite sua senha atual"
                  disabled={isChangingPassword}
                  className="pr-10 bg-background/70 border-border/80 focus-visible:ring-black"
                />
                <button
                  type="button"
                  onClick={() => setShowSenhaAtual(!showSenhaAtual)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  {showSenhaAtual ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>

            {/* Nova Senha */}
            <div className="space-y-2">
              <Label htmlFor="nova-senha">Nova Senha</Label>
              <div className="relative">
                <Input
                  id="nova-senha"
                  type={showNovaSenha ? 'text' : 'password'}
                  value={novaSenha}
                  onChange={(e) => setNovaSenha(e.target.value)}
                  placeholder="Digite a nova senha (mín. 6 caracteres)"
                  disabled={isChangingPassword}
                  className="pr-10 bg-background/70 border-border/80 focus-visible:ring-black"
                />
                <button
                  type="button"
                  onClick={() => setShowNovaSenha(!showNovaSenha)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  {showNovaSenha ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>

            {/* Confirmar Nova Senha */}
            <div className="space-y-2">
              <Label htmlFor="confirmar-senha">Confirmar Nova Senha</Label>
              <div className="relative">
                <Input
                  id="confirmar-senha"
                  type={showConfirmarSenha ? 'text' : 'password'}
                  value={confirmarSenha}
                  onChange={(e) => setConfirmarSenha(e.target.value)}
                  placeholder="Digite a nova senha novamente"
                  disabled={isChangingPassword}
                  className="pr-10 bg-background/70 border-border/80 focus-visible:ring-black"
                />
                <button
                  type="button"
                  onClick={() => setShowConfirmarSenha(!showConfirmarSenha)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  {showConfirmarSenha ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>

            {/* Submit Button */}
            <Button
              onClick={handleChangePassword}
              disabled={isChangingPassword}
              className="w-full rounded-xl bg-black hover:bg-black/90 text-white"
            >
              {isChangingPassword ? (
                <>
                  <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin mr-2" />
                  Alterando...
                </>
              ) : (
                'Salvar Nova Senha'
              )}
            </Button>
          </div>
        </motion.div>

        {/* Notifications Section */}
        <motion.div
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.05 }}
          className="card-glass-dark rounded-2xl p-6"
        >
          <div className="flex items-center gap-2 mb-4">
            <Bell className="h-5 w-5 text-primary" />
            <h2 className="text-lg font-semibold text-foreground">Notificações</h2>
          </div>

          <div className="flex items-center justify-between">
            <div className="flex-1 pr-4">
              <p className="text-sm font-medium text-foreground">Notificações de eventos</p>
              <p className="text-xs text-muted-foreground mt-0.5">
                Receba alertas silenciosos quando uma gravação iniciar ou finalizar por detecção automática
              </p>
            </div>
            <button
              onClick={async () => {
                const newValue = !notificationsEnabled;
                setNotificationsEnabled(newValue);
                try {
                  await AudioTriggerNative.setNotificationPreference({ enabled: newValue });
                } catch (e) {
                  console.error('[Settings] Error saving notification preference:', e);
                }
              }}
              className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out ${notificationsEnabled ? 'bg-primary' : 'bg-gray-600'
                }`}
            >
              <span
                className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${notificationsEnabled ? 'translate-x-5' : 'translate-x-0'
                  }`}
              />
            </button>
          </div>
        </motion.div>

        {/* Monitoring Periods Section */}
        <motion.div
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
          className="card-glass-dark rounded-2xl p-6"
        >
          <div className="flex items-center gap-2 mb-3">
            <Mic className="h-5 w-5 text-primary" />
            <h2 className="text-lg font-semibold text-foreground">Frases de Segurança</h2>
          </div>
          <p className="text-sm text-muted-foreground mb-4">
            Cadastre frases de voz para controlar gravação, pânico e alertas de risco.
          </p>
          <Button
            className="w-full rounded-xl bg-black hover:bg-black/90 text-white"
            onClick={() => navigate('/phrase-enrollment')}
          >
            <Mic className="h-4 w-4 mr-2" />
            Gerenciar Frases
          </Button>
        </motion.div>

        {/* Monitoring Periods Section */}
        <motion.div
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
          className="card-glass-dark rounded-2xl p-6"
        >
          <div className="flex items-center gap-2 mb-4">
            <Calendar className="h-5 w-5 text-primary" />
            <h2 className="text-lg font-semibold text-foreground">Períodos de Monitoramento</h2>
          </div>

          <p className="text-sm text-muted-foreground mb-4">
            Configure os dias e horários em que o Ampara deve monitorar o ambiente.
          </p>

          <WeeklyScheduleEditor
            initialSchedule={initialSchedule}
            onScheduleChange={setModifiedSchedule}
          />

          {/* Save Button */}
          {Object.keys(modifiedSchedule).length > 0 && (
            <Button
              onClick={handleSaveSchedule}
              disabled={isSavingSchedule}
              className="w-full mt-4 rounded-xl bg-black hover:bg-black/90 text-white"
            >
              {isSavingSchedule ? (
                <>
                  <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin mr-2" />
                  Salvando...
                </>
              ) : (
                'Salvar Agenda'
              )}
            </Button>
          )}
        </motion.div>


      </div>

      {/* Bottom back button */}
      <div
        className="border-t border-border/70 bg-background/70 backdrop-blur-md px-4 pt-3"
        style={{ paddingBottom: 'calc(env(safe-area-inset-bottom) + 0.75rem)' }}
      >
        <Button
          variant="outline"
          className="w-full h-11"
          onClick={() => navigate('/')}
        >
          <ArrowLeft className="h-4 w-4 mr-2" />
          Voltar
        </Button>
      </div>
    </div>
  );
}
