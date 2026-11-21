// Regras de negócio (portadas do app Android). As chaves das categorias/status são as mesmas do enum Kotlin,
// o que mantém o backup .ppci.zip compatível entre o app Android e o webapp.
export const CATEGORIES = {
  SIGN: { label: 'Placa de Sinalização', short: 'Placa', subtypes: ['Saída', 'Rota de fuga (direita)', 'Rota de fuga (esquerda)', 'Rota de fuga (em frente)', 'Escada', 'Extintor de incêndio', 'Hidrante', 'Alarme de incêndio', 'Proibido fumar', 'Porta corta-fogo'] },
  EMERGENCY_LIGHT: { label: 'Luminária de Emergência', short: 'Luminária', subtypes: ['Bloco autônomo de LEDs', 'Bloco autônomo c/ 2 faroletes', 'Aclaramento', 'Balizamento (saída)', 'Balizamento (direita)', 'Balizamento (esquerda)'] },
  EXTINGUISHER: { label: 'Extintor de Incêndio', short: 'Extintor', subtypes: ['PQS BC', 'PQS ABC', 'CO2 BC', 'Água pressurizada (AP) A', 'Espuma (ESP) AB', 'Sobre rodas (carreta)'] },
  HYDRANT: { label: 'Hidrante / Mangotinho', short: 'Hidrante', subtypes: ['Hidrante 1 saída', 'Hidrante 2 saídas', 'Hidrante c/ mangotinho', 'Mangotinho', 'Registro de recalque', 'Bomba de pressurização'] },
  SPRINKLER: { label: 'SPK (Sprinkler)', short: 'SPK', subtypes: ['Pendente 68°C', 'Upright 68°C', 'Sidewall'] },
  ALARM: { label: 'Alarme / Acionador Manual', short: 'Alarme', subtypes: ['Botoeira quebra-vidro', 'Sirene', 'Sensor de fumaça', 'Sensor de temperatura', 'Central de alarme'] },
  OTHER: { label: 'Outros', short: 'Outros', subtypes: ['Barra antipânico', 'Porta corta-fogo', 'Válvula de bloqueio', 'Reserva técnica', 'Pintura de piso'] },
};
export const CATEGORY_KEYS = Object.keys(CATEGORIES);

/** Status que decide se o item entra no orçamento. */
export const STATUS = {
  MISSING: { label: 'NÃO TEM', service: 'Fornecimento + instalação', budgeted: true, color: '#d92d20', ink: '#ffffff' },
  IRREGULAR: { label: 'IRREGULAR', service: 'Manutenção / troca', budgeted: true, color: '#f5a400', ink: '#1a1200' },
  OK: { label: 'TEM / OK', service: 'Existente (não orçar)', budgeted: false, color: '#15803d', ink: '#ffffff' },
};
export const STATUS_KEYS = Object.keys(STATUS);

export function counts(markers) {
  const missing = markers.filter((m) => m.status === 'MISSING').length;
  const irregular = markers.filter((m) => m.status === 'IRREGULAR').length;
  const ok = markers.filter((m) => m.status === 'OK').length;
  return { missing, irregular, ok, toBudget: missing + irregular, existing: ok };
}
