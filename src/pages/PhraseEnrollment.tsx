import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Mic, MicOff, Shield, AlertTriangle, Trash2, CheckCircle, XCircle, Loader2, Radio, Square, Siren, Hand, HelpCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useToast } from '@/hooks/use-toast';
import { AudioTriggerNative } from '@/plugins/audioTriggerNative';

/**
 * Tela de cadastro de frases personalizadas (enrollment).
 * Permite à usuária gravar amostras de voz para comandos operacionais e contextuais.
 * SPEC v3, seção 9.
 */

// Tipos de frase disponíveis para cadastro
type PhraseType = 'OPERATIONAL' | 'CONTEXTUAL';

// Frases pré-definidas por categoria
const OPERATIONAL_PHRASES = [
    {
        id: 'start_recording', label: 'Iniciar Gravação', description: 'Comando de voz para começar a gravar', Icon: Radio,
        help: 'Grave uma frase curta que você dirá quando quiser iniciar uma gravação por voz. Exemplo: "Ampara, comece a gravar". Grave-a 3 a 5 vezes com clareza.'
    },
    {
        id: 'stop_recording', label: 'Parar Gravação', description: 'Comando de voz para parar de gravar', Icon: Square,
        help: 'Grave uma frase que você dirá para encerrar a gravação. Exemplo: "Ampara, pare". Grave 3 a 5 vezes de forma natural.'
    },
    {
        id: 'start_panic', label: 'Ativar Pânico', description: 'Comando de voz para acionar pânico (ALTA segurança)', Icon: Siren,
        help: 'Essa frase aciona o modo pânico por voz. Por segurança, será necessário dizê-la 2 vezes seguidas. Exemplo: "Ampara socorro". Grave 3 a 5 vezes.'
    },
    {
        id: 'cancel_panic', label: 'Cancelar Pânico', description: 'Comando de voz para desativar pânico (ULTRA segurança)', Icon: Hand,
        help: 'Essa frase cancela o modo pânico por voz. Por ULTRA segurança, será necessário dizê-la 3 vezes seguidas. Escolha uma frase única que só você saiba.'
    },
];

const CONTEXTUAL_PHRASES = [
    {
        id: 'alert_1', label: 'Alerta Personalizado 1', description: 'Frase que indica situação de risco', Icon: AlertTriangle,
        help: 'Grave uma frase que você costuma ouvir em situações de risco (ex: "me larga!", "não encosta em mim"). Quando detectada, ela aumenta o score de risco.'
    },
    {
        id: 'alert_2', label: 'Alerta Personalizado 2', description: 'Outra frase de alerta', Icon: AlertTriangle,
        help: 'Outra frase de alerta. Pode ser algo como "para com isso" ou "vou chamar a polícia". Grave 3 a 5 vezes.'
    },
    {
        id: 'alert_3', label: 'Alerta Personalizado 3', description: 'Mais uma frase de alerta', Icon: AlertTriangle,
        help: 'Mais uma frase de alerta contextual. Quanto mais frases cadastrar, mais precisa será a detecção. Grave 3 a 5 vezes.'
    },
];

// Estado do processo de enrollment
type EnrollmentStep = 'select' | 'recording' | 'completed';

export default function PhraseEnrollmentPage() {
    const navigate = useNavigate();
    const { toast } = useToast();

    // Estado principal
    const [step, setStep] = useState<EnrollmentStep>('select');
    const [selectedPhrase, setSelectedPhrase] = useState<string | null>(null);
    const [selectedType, setSelectedType] = useState<PhraseType>('OPERATIONAL');
    const [isRecording, setIsRecording] = useState(false);

    // Estado de amostras
    const [currentSamples, setCurrentSamples] = useState(0);
    const [minRequired, setMinRequired] = useState(3);
    const [maxAllowed, setMaxAllowed] = useState(5);
    const [statusMessage, setStatusMessage] = useState('');

    // Estado de ações
    const [isProcessing, setIsProcessing] = useState(false);
    const [isRemoving, setIsRemoving] = useState<string | null>(null);
    const [expandedHelp, setExpandedHelp] = useState<string | null>(null);

    // ========== Handlers ==========

    /**
     * Inicia o processo de enrollment para uma frase
     */
    const handleStartEnrollment = async (phraseId: string, type: PhraseType) => {
        setIsProcessing(true);
        try {
            const result = await AudioTriggerNative.startEnrollment({ phraseId, type });
            if (result.success) {
                setSelectedPhrase(phraseId);
                setSelectedType(type);
                setCurrentSamples(0);
                setStep('recording');
                setStatusMessage(result.message);
            } else {
                toast({
                    title: 'Erro',
                    description: result.message,
                    variant: 'destructive',
                });
            }
        } catch (error: any) {
            toast({
                title: 'Erro',
                description: error?.message || 'Serviço não disponível',
                variant: 'destructive',
            });
        } finally {
            setIsProcessing(false);
        }
    };

    /**
     * Grava uma amostra de áudio (3 segundos)
     */
    const handleRecordSample = async () => {
        setIsRecording(true);
        setStatusMessage('Gravando... Diga a frase agora!');
        try {
            const result = await AudioTriggerNative.addEnrollmentSample({ durationMs: 3000 });
            if (result.success) {
                setCurrentSamples(result.currentSamples);
                setMinRequired(result.minRequired);
                setMaxAllowed(result.maxAllowed);
                setStatusMessage(result.message);
                toast({
                    title: 'Amostra aceita',
                    description: `Amostra ${result.currentSamples} gravada com sucesso`,
                });
            } else {
                setStatusMessage(result.message);
                toast({
                    title: 'Atenção',
                    description: result.message,
                    variant: 'destructive',
                });
            }
        } catch (error: any) {
            setStatusMessage('Erro ao gravar. Tente novamente.');
            toast({
                title: 'Erro',
                description: error?.message || 'Falha na gravação',
                variant: 'destructive',
            });
        } finally {
            setIsRecording(false);
        }
    };

    /**
     * Finaliza o enrollment
     */
    const handleFinishEnrollment = async () => {
        setIsProcessing(true);
        try {
            const result = await AudioTriggerNative.finishEnrollment();
            if (result.success) {
                setStep('completed');
                setStatusMessage(result.message);
                toast({
                    title: 'Sucesso!',
                    description: result.message,
                });
            } else {
                setStatusMessage(result.message);
                toast({
                    title: 'Atenção',
                    description: result.message,
                    variant: 'destructive',
                });
            }
        } catch (error: any) {
            toast({
                title: 'Erro',
                description: error?.message || 'Falha ao finalizar',
                variant: 'destructive',
            });
        } finally {
            setIsProcessing(false);
        }
    };

    /**
     * Cancela o enrollment e volta à seleção
     */
    const handleCancelEnrollment = async () => {
        try {
            await AudioTriggerNative.cancelEnrollment();
        } catch { /* ignora erros */ }
        setStep('select');
        setSelectedPhrase(null);
        setCurrentSamples(0);
        setStatusMessage('');
    };

    /**
     * Remove uma frase cadastrada
     */
    const handleRemovePhrase = async (phraseId: string) => {
        setIsRemoving(phraseId);
        try {
            const result = await AudioTriggerNative.removePhrase({ phraseId });
            if (result.success) {
                toast({
                    title: 'Removida',
                    description: `Frase removida com sucesso`,
                });
            }
        } catch (error: any) {
            toast({
                title: 'Erro',
                description: error?.message || 'Falha ao remover',
                variant: 'destructive',
            });
        } finally {
            setIsRemoving(null);
        }
    };

    // ========== Render: Etapa de Seleção ==========

    const renderPhraseCard = (phrase: { id: string; label: string; description: string; Icon: any; help: string }, type: PhraseType) => (
        <motion.div
            key={phrase.id}
            initial={{ opacity: 0, y: 5 }}
            animate={{ opacity: 1, y: 0 }}
            className="rounded-xl bg-background/40 border border-border/50"
        >
            <div className="flex items-center gap-3 p-3">
                <phrase.Icon className="h-5 w-5 text-muted-foreground flex-shrink-0" />
                <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-foreground truncate">{phrase.label}</p>
                    <p className="text-xs text-muted-foreground truncate">{phrase.description}</p>
                </div>
                <div className="flex gap-1.5 flex-shrink-0">
                    <button
                        onClick={() => setExpandedHelp(expandedHelp === phrase.id ? null : phrase.id)}
                        className="h-8 w-8 flex items-center justify-center rounded-lg text-muted-foreground hover:text-foreground"
                    >
                        <HelpCircle className="h-4 w-4" />
                    </button>
                    <Button
                        size="sm"
                        className="h-8 px-3 text-xs rounded-lg bg-black hover:bg-black/90 text-white"
                        onClick={() => handleStartEnrollment(phrase.id, type)}
                        disabled={isProcessing}
                    >
                        {isProcessing ? <Loader2 className="h-3 w-3 animate-spin" /> : <Mic className="h-3 w-3 mr-1" />}
                        Gravar
                    </Button>
                    <Button
                        size="sm"
                        variant="ghost"
                        className="h-8 w-8 p-0 text-muted-foreground hover:text-foreground hover:bg-background/50"
                        onClick={() => handleRemovePhrase(phrase.id)}
                        disabled={isRemoving === phrase.id}
                    >
                        {isRemoving === phrase.id ? <Loader2 className="h-3 w-3 animate-spin" /> : <Trash2 className="h-3 w-3" />}
                    </Button>
                </div>
            </div>
            {/* Painel de ajuda expandível */}
            <AnimatePresence>
                {expandedHelp === phrase.id && (
                    <motion.div
                        initial={{ height: 0, opacity: 0 }}
                        animate={{ height: 'auto', opacity: 1 }}
                        exit={{ height: 0, opacity: 0 }}
                        className="overflow-hidden"
                    >
                        <div className="px-3 pb-3 pt-1">
                            <p className="text-xs text-muted-foreground leading-relaxed bg-background/30 rounded-lg p-2.5 border border-border/30">
                                {phrase.help}
                            </p>
                        </div>
                    </motion.div>
                )}
            </AnimatePresence>
        </motion.div>
    );

    const renderSelectStep = () => (
        <>
            {/* Comandos Operacionais */}
            <motion.div
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                className="card-glass-dark rounded-2xl p-5"
            >
                <div className="flex items-center gap-2 mb-3">
                    <Shield className="h-5 w-5 text-muted-foreground" />
                    <h2 className="text-base font-semibold text-foreground">Comandos Operacionais</h2>
                </div>
                <p className="text-xs text-muted-foreground mb-3">
                    Frases que controlam gravação e pânico. Alta segurança.
                </p>
                <div className="space-y-2">
                    {OPERATIONAL_PHRASES.map(p => renderPhraseCard(p, 'OPERATIONAL'))}
                </div>
            </motion.div>

            {/* Alertas Contextuais */}
            <motion.div
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.05 }}
                className="card-glass-dark rounded-2xl p-5"
            >
                <div className="flex items-center gap-2 mb-3">
                    <AlertTriangle className="h-5 w-5 text-muted-foreground" />
                    <h2 className="text-base font-semibold text-foreground">Alertas Contextuais</h2>
                </div>
                <p className="text-xs text-muted-foreground mb-3">
                    Frases que indicam situação de risco. Somam ao score de detecção.
                </p>
                <div className="space-y-2">
                    {CONTEXTUAL_PHRASES.map(p => renderPhraseCard(p, 'CONTEXTUAL'))}
                </div>
            </motion.div>
        </>
    );

    // ========== Render: Etapa de Gravação ==========

    const renderRecordingStep = () => {
        const phraseLabel = [...OPERATIONAL_PHRASES, ...CONTEXTUAL_PHRASES]
            .find(p => p.id === selectedPhrase)?.label || selectedPhrase;

        const progress = maxAllowed > 0 ? (currentSamples / maxAllowed) * 100 : 0;
        const canFinish = currentSamples >= minRequired;

        return (
            <motion.div
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                className="card-glass-dark rounded-2xl p-6 space-y-6"
            >
                {/* Título da frase */}
                <div className="text-center">
                    <h2 className="text-lg font-bold text-foreground">{phraseLabel}</h2>
                    <p className="text-sm text-muted-foreground mt-1">
                        {selectedType === 'OPERATIONAL' ? 'Comando operacional' : 'Alerta contextual'}
                    </p>
                </div>

                {/* Indicador de progresso */}
                <div className="space-y-2">
                    <div className="flex justify-between text-xs text-muted-foreground">
                        <span>Amostras gravadas</span>
                        <span>{currentSamples}/{maxAllowed} (mín. {minRequired})</span>
                    </div>
                    <div className="w-full bg-background/50 rounded-full h-2.5">
                        <motion.div
                            className="h-2.5 rounded-full bg-primary"
                            initial={{ width: 0 }}
                            animate={{ width: `${progress}%` }}
                            transition={{ duration: 0.3 }}
                        />
                    </div>
                </div>

                {/* Indicadores de amostra */}
                <div className="flex justify-center gap-2">
                    {Array.from({ length: maxAllowed }).map((_, i) => (
                        <div
                            key={i}
                            className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold
                ${i < currentSamples
                                    ? 'bg-primary text-primary-foreground'
                                    : i === currentSamples
                                        ? 'bg-primary/20 border-2 border-primary text-primary animate-pulse'
                                        : 'bg-background/30 border border-border/50 text-muted-foreground'
                                }`}
                        >
                            {i < currentSamples ? '✓' : i + 1}
                        </div>
                    ))}
                </div>

                {/* Mensagem de status */}
                <div className="text-center">
                    <p className="text-sm text-muted-foreground">{statusMessage}</p>
                </div>

                {/* Botão de gravar */}
                <div className="flex justify-center">
                    <motion.button
                        whileTap={{ scale: 0.95 }}
                        onClick={handleRecordSample}
                        disabled={isRecording || currentSamples >= maxAllowed}
                        className={`w-20 h-20 rounded-full flex items-center justify-center shadow-lg transition-all
              ${isRecording
                                ? 'bg-red-500 animate-pulse shadow-red-500/30'
                                : currentSamples >= maxAllowed
                                    ? 'bg-muted cursor-not-allowed'
                                    : 'bg-primary hover:bg-primary/90 shadow-primary/30'
                            }`}
                    >
                        {isRecording
                            ? <MicOff className="h-8 w-8 text-white" />
                            : <Mic className="h-8 w-8 text-white" />
                        }
                    </motion.button>
                </div>

                {isRecording && (
                    <p className="text-center text-xs text-red-400 animate-pulse">
                        Gravando... Diga a frase com clareza
                    </p>
                )}

                {/* Ações */}
                <div className="flex gap-3">
                    <Button
                        variant="outline"
                        className="flex-1 h-11"
                        onClick={handleCancelEnrollment}
                        disabled={isRecording}
                    >
                        <XCircle className="h-4 w-4 mr-2" />
                        Cancelar
                    </Button>
                    <Button
                        className="flex-1 h-11 bg-primary hover:bg-primary/90 text-primary-foreground"
                        onClick={handleFinishEnrollment}
                        disabled={!canFinish || isRecording || isProcessing}
                    >
                        {isProcessing ? (
                            <Loader2 className="h-4 w-4 animate-spin mr-2" />
                        ) : (
                            <CheckCircle className="h-4 w-4 mr-2" />
                        )}
                        Finalizar
                    </Button>
                </div>
            </motion.div>
        );
    };

    // ========== Render: Etapa de Conclusão ==========

    const renderCompletedStep = () => (
        <motion.div
            initial={{ opacity: 0, scale: 0.95 }}
            animate={{ opacity: 1, scale: 1 }}
            className="card-glass-dark rounded-2xl p-8 text-center space-y-4"
        >
            <motion.div
                initial={{ scale: 0 }}
                animate={{ scale: 1 }}
                transition={{ type: 'spring', stiffness: 200, delay: 0.1 }}
            >
                <CheckCircle className="h-16 w-16 text-muted-foreground mx-auto" />
            </motion.div>
            <h2 className="text-xl font-bold text-foreground">Frase Cadastrada!</h2>
            <p className="text-sm text-muted-foreground">{statusMessage}</p>
            <Button
                className="w-full h-11 bg-primary hover:bg-primary/90 text-primary-foreground"
                onClick={() => {
                    setStep('select');
                    setSelectedPhrase(null);
                    setCurrentSamples(0);
                    setStatusMessage('');
                }}
            >
                Cadastrar outra frase
            </Button>
        </motion.div>
    );

    // ========== Render Principal ==========

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
                    <h1 className="text-lg font-semibold">Minhas Frases de Segurança</h1>
                </div>
            </motion.div>

            {/* Conteúdo */}
            <div className="flex-1 overflow-auto px-4 py-4 space-y-4">
                <AnimatePresence mode="wait">
                    {step === 'select' && renderSelectStep()}
                    {step === 'recording' && renderRecordingStep()}
                    {step === 'completed' && renderCompletedStep()}
                </AnimatePresence>
            </div>

            {/* Bottom back button */}
            <div
                className="border-t border-border/70 bg-background/70 backdrop-blur-md px-4 pt-3"
                style={{ paddingBottom: 'calc(env(safe-area-inset-bottom) + 0.75rem)' }}
            >
                <Button
                    variant="outline"
                    className="w-full h-11"
                    onClick={() => step === 'select' ? navigate(-1) : handleCancelEnrollment()}
                >
                    <ArrowLeft className="h-4 w-4 mr-2" />
                    {step === 'select' ? 'Voltar' : 'Cancelar e Voltar'}
                </Button>
            </div>
        </div>
    );
}
