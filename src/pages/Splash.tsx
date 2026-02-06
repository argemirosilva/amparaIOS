import { useEffect } from 'react';
import { motion } from 'framer-motion';
import { Logo } from '@/components/Logo';

interface SplashPageProps {
  onComplete: () => void;
}

export function SplashPage({ onComplete }: SplashPageProps) {
  useEffect(() => {
    // Auto-advance after 4 seconds
    const timer = setTimeout(() => {
      onComplete();
    }, 4000);

    return () => clearTimeout(timer);
  }, [onComplete]);

  return (
    <div 
      className="min-h-screen bg-background flex flex-col items-center justify-center px-8"
      style={{ 
        paddingTop: 'env(safe-area-inset-top)', 
        paddingBottom: 'env(safe-area-inset-bottom)' 
      }}
    >
      <motion.div
        initial={{ opacity: 0, scale: 0.8 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.8, ease: 'easeOut' }}
        className="flex flex-col items-center gap-8"
      >
        {/* Logo Ampara */}
        <Logo size="xl" />
        
        {/* Frase */}
        <motion.p
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.5, duration: 0.8 }}
          className="text-xl text-center text-foreground/80 font-light max-w-md"
        >
          Você não precisa mais estar sozinha.
        </motion.p>
      </motion.div>
    </div>
  );
}
