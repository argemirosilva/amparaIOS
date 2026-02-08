import React from 'react';
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, FileText, HelpCircle, ExternalLink } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
// Logo removed from settings pages

export default function AboutPage() {
  const navigate = useNavigate();

  return (
    <div className="min-h-screen bg-background flex flex-col">
      {/* iOS SafeArea spacer */}
      <div className="h-[env(safe-area-inset-top)] bg-background shrink-0" />
      {/* Header */}
      <motion.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="sticky top-0 z-10 bg-background/95 backdrop-blur-sm border-b border-border px-4 py-3"
        style={{ paddingTop: '1rem' }}
      >
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => navigate('/')}
            className="h-9 w-9"
          >
            <ArrowLeft className="h-5 w-5" />
          </Button>
          <h1 className="text-lg font-semibold">Sobre</h1>
        </div>
      </motion.div>

      {/* Content */}
      <div className="flex-1 overflow-auto px-4 py-6">
        <Tabs defaultValue="terms" className="w-full">
          <TabsList className="grid w-full grid-cols-2 mb-6">
            <TabsTrigger value="terms" className="flex items-center gap-2">
              <FileText className="h-4 w-4" />
              Termos de Uso
            </TabsTrigger>
            <TabsTrigger value="help" className="flex items-center gap-2">
              <HelpCircle className="h-4 w-4" />
              Ajuda
            </TabsTrigger>
          </TabsList>

          {/* Terms of Use Tab */}
          <TabsContent value="terms" className="space-y-6">
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              className="bg-card border border-border rounded-lg p-6 space-y-4"
            >
              <h2 className="text-xl font-semibold text-foreground">Termos de Uso do AMPARA</h2>
              
              <div className="space-y-4 text-sm text-muted-foreground">
                <section>
                  <h3 className="font-semibold text-foreground mb-2">📱 O que é o AMPARA?</h3>
                  <p>
                    O AMPARA é uma ferramenta de segurança pessoal que monitora situações de risco e envia alertas para sua rede de proteção. 
                    <strong className="text-amber-600"> Importante: o AMPARA não substitui serviços de segurança pública como polícia ou emergência médica.</strong> 
                    É uma camada adicional de proteção, mas não garante proteção total em todas as situações.
                  </p>
                </section>

                <section>
                  <h3 className="font-semibold text-foreground mb-2">📍 Por que pedimos Localização?</h3>
                  <p>
                    A permissão de localização permite que o AMPARA:
                  </p>
                  <ul className="list-disc list-inside ml-2 space-y-1 mt-2">
                    <li>Envie sua localização exata para sua rede de proteção em caso de alerta</li>
                    <li>Melhore o contexto dos registros de segurança</li>
                    <li>Funcione corretamente em segundo plano (requisito do Android)</li>
                  </ul>
                  <p className="mt-2 text-xs text-amber-600">
                    Sua localização é usada apenas para sua segurança e não é compartilhada com terceiros sem sua autorização.
                  </p>
                </section>

                <section>
                  <h3 className="font-semibold text-foreground mb-2">🎤 Por que pedimos Microfone?</h3>
                  <p>
                    A permissão de microfone permite que o AMPARA:
                  </p>
                  <ul className="list-disc list-inside ml-2 space-y-1 mt-2">
                    <li>Monitore sons que possam indicar situações de risco (como discussões ou gritos)</li>
                    <li>Grave áudio quando necessário para documentar situações de emergência</li>
                    <li>Identifique automaticamente quando você pode estar em perigo</li>
                  </ul>
                  <p className="mt-2 text-xs text-amber-600">
                    O áudio é processado localmente no seu celular. Gravações são enviadas apenas quando há um alerta ou você aciona manualmente.
                  </p>
                </section>

                <section>
                  <h3 className="font-semibold text-foreground mb-2">🔋 App Online 24 Horas</h3>
                  <p>
                    O AMPARA foi projetado para funcionar continuamente, mesmo com a tela desligada e o celular no bolso. Isso significa que:
                  </p>
                  <ul className="list-disc list-inside ml-2 space-y-1 mt-2">
                    <li>O app usa recursos do celular (bateria, processamento, internet) para manter a proteção ativa</li>
                    <li>Você pode configurar períodos específicos de monitoramento para economizar bateria</li>
                    <li>O app envia "pings" regulares ao servidor para garantir que está funcionando</li>
                  </ul>
                  <p className="mt-2 text-xs">
                    Recomendamos manter o celular carregado ou com bateria suficiente quando o monitoramento estiver ativo.
                  </p>
                </section>

                <section>
                  <h3 className="font-semibold text-foreground mb-2">🤖 Uso de Dados para Melhorar a IA</h3>
                  <p>
                    As informações geradas pelo AMPARA (como padrões de áudio, horários de alerta e contexto de uso) podem ser usadas para:
                  </p>
                  <ul className="list-disc list-inside ml-2 space-y-1 mt-2">
                    <li>Melhorar a precisão da detecção de situações de risco</li>
                    <li>Reduzir falsos alertas</li>
                    <li>Desenvolver novos recursos de segurança</li>
                  </ul>
                  <p className="mt-2 text-xs text-amber-600">
                    <strong>Privacidade:</strong> Seus dados são tratados com confidencialidade. Informações pessoais identificáveis não são compartilhadas publicamente. 
                    No entanto, dados agregados e anonimizados podem ser usados para pesquisa e melhoria do serviço.
                  </p>
                </section>

                <section>
                  <h3 className="font-semibold text-foreground mb-2">📄 Política Completa</h3>
                  <p>
                    Para mais detalhes sobre como tratamos seus dados e seus direitos, acesse nossa política de privacidade completa:
                  </p>
                  <a
                    href="https://amparamulher.com.br/privacidade"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 text-primary hover:underline mt-2"
                  >
                    amparamulher.com.br/privacidade
                    <ExternalLink className="h-3 w-3" />
                  </a>
                </section>

                <section className="pt-4 border-t border-border">
                  <p className="text-xs text-muted-foreground">
                    Ao usar o AMPARA, você concorda com estes termos. Se tiver dúvidas, entre em contato através do site 
                    <a href="https://amparamulher.com.br" target="_blank" rel="noopener noreferrer" className="text-primary hover:underline mx-1">
                      amparamulher.com.br
                    </a>
                  </p>
                </section>
              </div>
            </motion.div>
          </TabsContent>

          {/* Help Tab */}
          <TabsContent value="help" className="space-y-6">
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              className="bg-card border border-border rounded-lg p-6"
            >
              <h2 className="text-xl font-semibold text-foreground mb-4">Manual de Uso</h2>

              {/* Manual estático */}
              <div className="prose prose-sm max-w-none text-muted-foreground">
                  {/* Manual de Uso */}
                  <section className="space-y-6">
                    {/* Introdução */}
                    <div className="bg-primary/5 border border-primary/20 rounded-lg p-4">
                      <h3 className="text-lg font-semibold text-primary mb-2">💜 Bem-vinda ao Ampara!</h3>
                      <p className="text-sm">
                        Este é o seu guia para usar o aplicativo e se sentir mais segura. Vamos te mostrar como cada parte funciona, passo a passo!
                      </p>
                    </div>

                    {/* Botão de Pânico */}
                    <div>
                      <h3 className="font-semibold text-foreground mb-3">🚨 O Botão de Pânico: Seu Alerta de Segurança</h3>
                      <p className="mb-3">
                        O botão de pânico é a principal ferramenta para pedir ajuda de forma rápida e silenciosa.
                      </p>
                      
                      <h4 className="font-semibold text-foreground text-sm mb-2">Como usar:</h4>
                      <ol className="list-decimal list-inside ml-2 space-y-2 text-sm">
                        <li><strong>Pressione e segure</strong> o botão vermelho grande no centro da tela.</li>
                        <li>Mantenha o dedo pressionado por <strong>2 segundos</strong>.</li>
                        <li>Você verá um círculo se completando ao redor do botão. Isso mostra que o alarme está sendo ativado.</li>
                        <li>Após os 2 segundos, o botão mudará para <strong>"ENVIANDO..."</strong>. Pronto! Seus guardiões e a central de monitoramento foram acionados.</li>
                      </ol>

                      <h4 className="font-semibold text-foreground text-sm mb-2 mt-3">O que acontece depois?</h4>
                      <ul className="list-disc list-inside ml-2 space-y-1 text-sm">
                        <li>Seus guardiões recebem um alerta com sua localização.</li>
                        <li>O aplicativo começa a gravar o som ambiente para usar como prova, se necessário.</li>
                        <li>A central de monitoramento (se contratada) entra em ação.</li>
                      </ul>

                      <p className="text-xs mt-3 text-amber-600 bg-amber-50 dark:bg-amber-950/20 p-2 rounded">
                        ⚠️ <strong>Importante:</strong> Use o botão de pânico apenas em situações de emergência real. Ele foi feito para ser seu socorro rápido e silencioso.
                      </p>
                    </div>

                    {/* Disfarce do App */}
                    <div>
                      <h3 className="font-semibold text-foreground mb-3">🎨 Disfarce do Aplicativo: Sua Privacidade em Primeiro Lugar</h3>
                      <p className="mb-3">
                        Sabemos que sua privacidade é fundamental. Por isso, você pode mudar a aparência do ícone do Ampara na tela do seu celular.
                      </p>
                      
                      <h4 className="font-semibold text-foreground text-sm mb-2">Como mudar o ícone:</h4>
                      <ol className="list-decimal list-inside ml-2 space-y-2 text-sm">
                        <li>Na tela principal, toque no ícone de <strong>Menu</strong> (três linhas no canto superior direito).</li>
                        <li>Escolha a opção <strong>"Disfarce do App"</strong> (ícone de paleta de pintura 🎨).</li>
                        <li>Você verá várias opções de ícones, como apps de ginástica, jogos ou beleza.</li>
                        <li>Toque no ícone que você preferir. Ele será aplicado imediatamente!</li>
                      </ol>

                      <p className="text-xs mt-3 bg-blue-50 dark:bg-blue-950/20 p-2 rounded">
                        💡 <strong>Dica:</strong> Escolha um ícone que não chame a atenção no seu celular. Assim, só você saberá que o Ampara está ali, pronto para te proteger.
                      </p>
                    </div>

                    {/* Monitoramento de Áudio */}
                    <div>
                      <h3 className="font-semibold text-foreground mb-3">🎤 Monitoramento de Áudio: O App que Ouve por Você</h3>
                      <p className="mb-3">
                        O Ampara pode ouvir o ambiente para identificar discussões ou situações de risco, mesmo com o celular guardado.
                      </p>
                      
                      <h4 className="font-semibold text-foreground text-sm mb-2">Como funciona:</h4>
                      <ul className="list-disc list-inside ml-2 space-y-2 text-sm">
                        <li>O monitoramento de áudio <strong>só fica ativo nos horários que você configurou</strong> no seu perfil (ex: à noite, ou aos fins de semana).</li>
                        <li>Quando o monitoramento está ligado, você verá um <strong>círculo colorido</strong> na tela principal, mostrando a intensidade do som.</li>
                        <li>Se o app detectar um som de discussão, ele pode iniciar uma gravação automática para sua segurança.</li>
                      </ul>

                      <p className="text-xs mt-3 bg-emerald-50 dark:bg-emerald-950/20 p-2 rounded">
                        ✅ <strong>Fique tranquila:</strong> O app só monitora o som nos períodos que você definiu. Fora desses horários, ele não ouve nem grava nada.
                      </p>
                    </div>

                    {/* Configurações */}
                    <div>
                        <h3 className="font-semibold text-foreground mb-3">⚙️ Configurações: Deixe o App do Seu Jeito</h3>
                        <p className="mb-3">
                          No menu, você encontra a tela de <strong>Configurações</strong>. Lá você pode:
                        </p>
                        <ul className="list-disc list-inside ml-2 space-y-2">
                          <li>
                            <strong>Alterar sua senha:</strong> Mantenha sua conta segura.
                          </li>
                          <li>
                            <strong>Gerenciar Períodos de Monitoramento:</strong> Escolha exatamente os dias e horários em que o Ampara deve ficar ativo. Você pode adicionar, editar ou remover períodos para cada dia da semana.
                          </li>
                        </ul>
                    </div>

                    {/* Proteção Contra Coação */}
                    <div>
                        <h3 className="font-semibold text-foreground mb-3">🛡️ Proteção Contra Coação: Sua Segurança Secreta</h3>
                        <p className="mb-3">
                          O Ampara tem uma camada de segurança especial para situações em que você pode ser forçada a desativar o aplicativo.
                        </p>
                        <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-4 text-sm">
                          <p className="font-semibold text-amber-600 mb-2">Como funciona?</p>
                          <p>
                            Ao acessar <strong>Configurações</strong> ou tentar <strong>Sair (Logout)</strong>, o app pedirá sua senha. Se você digitar sua <strong>senha de coação</strong> (definida com sua rede de apoio), o Ampara entrará em modo de simulação:
                          </p>
                          <ul className="list-disc list-inside ml-2 space-y-1 mt-2">
                            <li>
                              <strong>Nas Configurações:</strong> O app fingirá que salvou as alterações, mas nada será realmente modificado. Seus períodos de monitoramento e sua senha continuarão os mesmos.
                            </li>
                            <li>
                              <strong>No Logout:</strong> O app fingirá que fechou, mas na verdade ele apenas irá para segundo plano. Você continuará logada e protegida.
                            </li>
                          </ul>
                          <p className="mt-3">
                            Isso engana o agressor, fazendo-o pensar que conseguiu desativar sua proteção, enquanto você continua segura.
                          </p>
                        </div>
                    </div>

                    {/* Período de Monitoramento */}
                    <div>
                      <h3 className="font-semibold text-foreground mb-3">🗓️ Período de Monitoramento: Quando o App te Protege?</h3>
                      <p className="mb-3">
                        Você decide em quais dias e horários o Ampara deve ficar de prontidão. Fora desses períodos, o app economiza bateria e não monitora o ambiente.
                      </p>
                      
                      <h4 className="font-semibold text-foreground text-sm mb-2">Como saber o status do monitoramento?</h4>
                      <ul className="list-disc list-inside ml-2 space-y-2 text-sm">
                        <li><strong className="text-emerald-600">Ativo (verde):</strong> O app está te protegendo agora.</li>
                        <li><strong className="text-primary">Próximo (azul):</strong> O app está aguardando o próximo horário agendado para hoje.</li>
                        <li><strong className="text-muted-foreground">Sem monitoramento (cinza):</strong> Não há mais horários de proteção para hoje.</li>
                      </ul>

                      <p className="text-xs mt-3 bg-blue-50 dark:bg-blue-950/20 p-2 rounded">
                        💡 <strong>Dica:</strong> Para ajustar os horários, vá em <strong>Menu → Configurações → Períodos de Monitoramento</strong>.
                      </p>
                    </div>

                    {/* Permissões Essenciais */}
                    <div>
                      <h3 className="font-semibold text-foreground mb-3">⚙️ Permissões Essenciais para sua Segurança</h3>
                      <p className="mb-3">
                        Para que o Ampara funcione corretamente, ele precisa de algumas permissões. Sem elas, não conseguimos te proteger.
                      </p>
                      
                      <ul className="space-y-3">
                        <li className="flex items-start gap-3">
                          <div className="w-8 h-8 flex-shrink-0 flex items-center justify-center rounded-full bg-primary/10 text-primary">🎤</div>
                          <div>
                            <h4 className="font-semibold text-foreground text-sm">Microfone</h4>
                            <p className="text-xs">Para ouvir o ambiente e identificar discussões ou gritos, mesmo com o celular no bolso.</p>
                          </div>
                        </li>
                        <li className="flex items-start gap-3">
                          <div className="w-8 h-8 flex-shrink-0 flex items-center justify-center rounded-full bg-primary/10 text-primary">📍</div>
                          <div>
                            <h4 className="font-semibold text-foreground text-sm">Localização</h4>
                            <p className="text-xs">Para enviar sua localização exata aos guardiões quando você pede ajuda.</p>
                          </div>
                        </li>
                        <li className="flex items-start gap-3">
                          <div className="w-8 h-8 flex-shrink-0 flex items-center justify-center rounded-full bg-primary/10 text-primary">🔔</div>
                          <div>
                            <h4 className="font-semibold text-foreground text-sm">Notificações</h4>
                            <p className="text-xs">Para te avisar sobre o status do app de forma silenciosa e discreta.</p>
                          </div>
                        </li>
                        <li className="flex items-start gap-3">
                          <div className="w-8 h-8 flex-shrink-0 flex items-center justify-center rounded-full bg-primary/10 text-primary">🔋</div>
                          <div>
                            <h4 className="font-semibold text-foreground text-sm">Bateria</h4>
                            <p className="text-xs">É <strong>essencial</strong> que você desative a otimização de bateria para o Ampara. Isso impede que o Android feche o app para economizar energia.</p>
                          </div>
                        </li>
                      </ul>

                      <p className="text-xs mt-3 text-amber-600 bg-amber-50 dark:bg-amber-950/20 p-2 rounded">
                        ⚠️ <strong>Como permitir:</strong> Vá em <strong>Configurações do Celular → Apps → Ampara → Permissões</strong> e ative todas. Para a bateria, vá em <strong>Configurações → Apps → Ampara → Bateria</strong> e selecione <strong>"Sem restrições"</strong>.
                      </p>
                    </div>

                    {/* Rodapé */}
                    <div className="pt-4 border-t border-border">
                      <p className="text-sm text-center text-muted-foreground italic">
                        Lembre-se: o Ampara é seu aliado. Explore as funções e sinta-se mais segura todos os dias.
                      </p>
                      <p className="text-xs text-center text-muted-foreground mt-3">
                        Ainda tem dúvidas? Entre em contato através do site{' '}
                        <a href="https://amparamulher.com.br" target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">
                          amparamulher.com.br
                        </a>
                      </p>
                    </div>
                  </section>
                </div>
            </motion.div>
          </TabsContent>
        </Tabs>
      </div>
    </div>
  );
}
