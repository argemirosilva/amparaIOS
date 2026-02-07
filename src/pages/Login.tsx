import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Eye, EyeOff, Lock, Mail, ExternalLink } from 'lucide-react';
import orizonLogo from '@/assets/orizon-tech-logo.png';
import amparaCircleLogo from '@/assets/ampara-circle-logo.png';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Logo, LogoWithText } from '@/components/Logo';
import { useToast } from '@/hooks/use-toast';
import { useAuth } from '@/hooks/useAuth';

interface LoginPageProps {
  onLoginSuccess: () => void;
  onLogout?: () => void;
}

export function LoginPage({ onLoginSuccess, onLogout }: LoginPageProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConnecting, setShowConnecting] = useState(false);
  const { toast } = useToast();
  const auth = useAuth();

  // Auto-redirect if already logged in
  useEffect(() => {
    if (auth.isAuthenticated) {
      console.log('[Login] User already authenticated, redirecting to home...');
      onLoginSuccess();
    }
  }, [auth.isAuthenticated, onLoginSuccess]);

  const handleLogout = () => {
    if (onLogout) {
      onLogout();
    }
    // Note: Token cleanup is now handled by App.tsx and api.ts
    toast({
      title: 'Sessão encerrada',
      description: 'Você foi desconectado.',
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (!email || !password) {
      toast({
        title: 'Campos obrigatórios',
        description: 'Por favor, preencha todos os campos.',
        variant: 'destructive',
      });
      return;
    }

    // Show connecting screen
    setShowConnecting(true);

    const result = await auth.login(email, password);

    if (!result.success) {
      setShowConnecting(false);
      toast({
        title: 'Erro ao entrar',
        description: result.error || 'Credenciais inválidas. Tente novamente.',
        variant: 'destructive',
      });
      return;
    }

    // Note: If isCoercion is true, we DON'T show any visual feedback
    // The silent alert was already triggered by the API
    
    toast({
      title: 'Bem-vinda!',
      description: 'Login realizado com sucesso.',
    });

    // Small delay to show success before transitioning
    setTimeout(() => {
      onLoginSuccess();
    }, 500);
  };

  const handleForgotPassword = () => {
    toast({
      title: 'Recuperação de senha',
      description: (
        <span>
          Para recuperar sua senha acesse{' '}
          <a 
            href="https://amparamulher.com.br" 
            target="_blank" 
            rel="noopener noreferrer"
            className="text-primary underline font-medium inline-flex items-center gap-1"
          >
            amparamulher.com.br
            <ExternalLink className="w-3 h-3" />
          </a>
        </span>
      ),
    });
  };

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-6 safe-area-inset-top safe-area-inset-bottom bg-[hsl(210,20%,98%)]">
      {/* Background gradient effect - subtle for ice white */}
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        <div className="absolute top-0 left-1/2 -translate-x-1/2 w-[500px] h-[500px] bg-gradient-to-br from-primary/8 to-secondary/6 blur-[100px] rounded-full" />
      </div>

      <AnimatePresence mode="wait">
        {showConnecting ? (
          /* Connecting Screen - Centered container with logo background */
          <motion.div
            key="connecting"
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.95 }}
            transition={{ duration: 0.3, ease: 'easeOut' }}
            className="relative z-10 flex items-center justify-center"
          >
            {/* Container with logo background */}
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.4 }}
              className="relative w-[800px] h-[800px] flex items-center justify-center"
            >
              {/* Logo as background */}
              <motion.img
                src={amparaCircleLogo}
                alt=""
                initial={{ opacity: 0, scale: 0.8 }}
                animate={{ 
                  opacity: [0.25, 0.4, 0.25], 
                  scale: [1, 1.02, 1] 
                }}
                transition={{ 
                  duration: 2.5, 
                  ease: 'easeInOut',
                  repeat: Infinity,
                }}
                className="absolute inset-0 w-full h-full object-contain"
              />
              
              {/* Connecting text centered over logo */}
              <span className="relative z-10 text-base font-medium text-muted-foreground">Conectando...</span>
            </motion.div>
          </motion.div>
        ) : (
          /* Login Card */
          <motion.div
            key="login"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.4 }}
            className="relative z-10 w-full max-w-xs bg-white rounded-2xl shadow-lg p-5"
          >
        {/* Logo with entrance animation */}
        <div className="flex justify-center mb-3">
          <LogoWithText size="md" />
        </div>

        {/* Login Form */}
        <form onSubmit={handleSubmit} className="space-y-3">
          {/* Email Field */}
          <motion.div 
            initial={{ opacity: 0, x: -10 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ delay: 0.3, duration: 0.3 }}
            className="relative"
          >
            <Mail className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
            <Input
              type="email"
              placeholder="E-mail"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="pl-10 h-11 bg-muted/50 border-border text-foreground placeholder:text-muted-foreground rounded-lg text-sm"
              autoComplete="email"
              disabled={auth.isLoading}
            />
          </motion.div>

          {/* Password Field */}
          <motion.div 
            initial={{ opacity: 0, x: -10 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ delay: 0.4, duration: 0.3 }}
            className="relative"
          >
            <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
            <Input
              type={showPassword ? 'text' : 'password'}
              placeholder="Senha"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="pl-10 pr-10 h-11 bg-muted/50 border-border text-foreground placeholder:text-muted-foreground rounded-lg text-sm"
              autoComplete="current-password"
              disabled={auth.isLoading}
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors"
              disabled={auth.isLoading}
            >
              {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
            </button>
          </motion.div>

          {/* Forgot Password */}
          <motion.div 
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.5, duration: 0.3 }}
            className="flex justify-end"
          >
            <button
              type="button"
              onClick={handleForgotPassword}
              className="text-xs text-primary hover:underline"
              disabled={auth.isLoading}
            >
              Esqueci minha senha
            </button>
          </motion.div>

          {/* Submit Button */}
          <motion.div 
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.6, duration: 0.3 }}
          >
            <Button
              type="submit"
              disabled={auth.isLoading}
              className="w-full h-11 bg-primary hover:bg-primary/90 text-primary-foreground rounded-lg font-medium text-sm shadow-sm"
            >
              {auth.isLoading ? 'Entrando...' : 'Entrar'}
            </Button>
          </motion.div>
        </form>

        {/* Footer */}
        <motion.div 
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.8, duration: 0.4 }}
          className="mt-6 flex items-center justify-center gap-2 text-xs text-muted-foreground/60"
        >
          <span>Powered by</span>
          <img 
            src={orizonLogo} 
            alt="Orizon Tech" 
            className="h-3 opacity-60"
          />
        </motion.div>
      </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
