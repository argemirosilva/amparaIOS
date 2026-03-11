import { useEffect } from 'react';
import { Geolocation } from '@capacitor/geolocation';
import { Capacitor } from '@capacitor/core';
import { LocalNotifications } from '@capacitor/local-notifications';
import BatteryOptimization from '@/plugins/batteryOptimization';
import AlarmPermission from '@/plugins/alarmPermission';
import KeepAlive from '@/plugins/keepAlive';
import { getDeviceId } from '@/lib/deviceId';

/**
 * Componente que solicita permissões automaticamente
 * quando o app inicia (após bypass da tela de permissões)
 * Sequência: localização → iniciar KeepAlive → notificações → bateria → alarmes
 */
export function LocationPermissionRequest() {
  useEffect(() => {
    console.log('[PermissionRequest] 🚀 Componente montado, aguardando para solicitar permissões...');

    const requestPermissions = async () => {
      if (!Capacitor.isNativePlatform()) {
        console.log('[PermissionRequest] Web platform, skipping auto-request');
        return;
      }

      const isAndroid = Capacitor.getPlatform() === 'android';
      let locationGranted = false;

      // 1. Solicitar permissão de localização
      try {
        const status = await Geolocation.checkPermissions();
        console.log('[PermissionRequest] 📊 Localização status:', status.location);

        if (status.location === 'granted') {
          console.log('[PermissionRequest] ✅ Já tem permissão de localização');
          locationGranted = true;
        } else if (status.location === 'denied') {
          console.log('[PermissionRequest] ❌ Localização negada anteriormente');
        } else {
          console.log('[PermissionRequest] 📍 Solicitando permissão de localização...');
          const result = await Geolocation.requestPermissions();
          console.log('[PermissionRequest] 📊 Localização resultado:', result.location);
          locationGranted = result.location === 'granted';
        }
      } catch (error) {
        console.error('[PermissionRequest] ❌ Erro ao solicitar localização:', error);
      }

      // 1.1 Iniciar KeepAliveService se localização foi concedida (Android)
      // Garante que o serviço de pings inicie mesmo quando a permissão
      // é concedida após o initServices do App.tsx
      if (isAndroid && locationGranted) {
        try {
          const deviceId = getDeviceId();
          console.log('[PermissionRequest] 🚀 Iniciando KeepAliveService...');
          await KeepAlive.start({ deviceId });
          console.log('[PermissionRequest] ✅ KeepAliveService iniciado com sucesso');
        } catch (error) {
          console.error('[PermissionRequest] ❌ Erro ao iniciar KeepAliveService:', error);
        }
      }


      // 2. Solicitar permissão de notificações
      try {
        const notifStatus = await LocalNotifications.checkPermissions();
        console.log('[PermissionRequest] 🔔 Notificações status:', notifStatus.display);

        if (notifStatus.display === 'granted') {
          console.log('[PermissionRequest] ✅ Já tem permissão de notificações');
        } else if (notifStatus.display === 'denied') {
          console.log('[PermissionRequest] ❌ Notificações negada anteriormente');
        } else {
          console.log('[PermissionRequest] 🔔 Solicitando permissão de notificações...');
          const result = await LocalNotifications.requestPermissions();
          console.log('[PermissionRequest] 🔔 Notificações resultado:', result.display);
        }
      } catch (error) {
        console.error('[PermissionRequest] ❌ Erro ao solicitar notificações:', error);
      }

      // 3. Solicitar sem restrição de bateria (apenas Android)
      if (isAndroid) {
        try {
          const batteryResult = await BatteryOptimization.isIgnoringBatteryOptimizations();
          console.log('[PermissionRequest] 🔋 Bateria status:', batteryResult.isIgnoring ? 'sem restrição' : 'com restrição');

          if (batteryResult.isIgnoring) {
            console.log('[PermissionRequest] ✅ Já está sem restrição de bateria');
          } else {
            console.log('[PermissionRequest] 🔋 Solicitando sem restrição de bateria...');
            await BatteryOptimization.requestIgnoreBatteryOptimizations();
            console.log('[PermissionRequest] 🔋 Solicitação de bateria concluída');
          }
        } catch (error) {
          console.error('[PermissionRequest] ❌ Erro ao solicitar bateria:', error);
        }

        // 4. Solicitar alarmes exatos (apenas Android 12+)
        try {
          const alarmResult = await AlarmPermission.canScheduleExactAlarms();
          console.log('[PermissionRequest] ⏰ Alarmes exatos status:', alarmResult.canSchedule ? 'permitido' : 'não permitido');

          if (alarmResult.canSchedule) {
            console.log('[PermissionRequest] ✅ Já tem permissão de alarmes exatos');
          } else {
            console.log('[PermissionRequest] ⏰ Solicitando permissão de alarmes exatos...');
            await AlarmPermission.requestScheduleExactAlarms();
            console.log('[PermissionRequest] ⏰ Solicitação de alarmes concluída');
          }
        } catch (error) {
          console.error('[PermissionRequest] ❌ Erro ao solicitar alarmes:', error);
        }
      }
    };

    // Aguardar 1 segundo após o app carregar para solicitar
    const timer = setTimeout(requestPermissions, 1000);

    return () => clearTimeout(timer);
  }, []);

  // Componente não renderiza nada
  return null;
}

