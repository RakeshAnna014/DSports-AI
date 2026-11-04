import { useEffect, useRef, useState } from 'react';
import { Alert, Box, Chip, CircularProgress, IconButton, Paper, Stack, TextField, Typography } from '@mui/material';
import SendIcon from '@mui/icons-material/Send';
import { inventoryAiApi } from '@/api/inventoryAi';
import { colors, gradients } from '@/design/tokens';
import type { InvBlock, InvCopilotResponse } from '@/types/inventoryAi';
import { BarList, ForecastChart } from './charts';
import { ContainBoundary } from './ErrorBoundary';
import { ClearanceTable, EventView, OverstockTable, RegionView, ReplenishTable, RiskTable, ScenarioView } from './blocks';
import { DataBadge, ErrorState, inr, Kpi, num, RecommendationBadge } from './ui';
import Grid from '@mui/material/Grid2';

const STARTERS = [
  'Which products are likely to stock out next month?',
  'What should we replenish this week?',
  'Do we have enough cricket inventory for the tournament?',
  'Which products should go on clearance?',
  'What happens if we give 30% discount?',
  'Summarize inventory health for management.',
];

interface Msg { id: number; role: 'user' | 'assistant'; text: string; response?: InvCopilotResponse; error?: unknown }

const Block = ({ block }: { block: InvBlock }) => {
  const d = block.data;
  const wrap = (children: React.ReactNode) => (
    <Box data-testid={`copilot-block-${block.type}`} sx={{ mt: 1.25, p: 1.25, borderRadius: 2, border: `1px solid ${colors.line}`, bgcolor: '#fff', overflow: 'hidden' }}>
      <Typography variant="caption" fontWeight={800} color="text.secondary" sx={{ display: 'block', mb: 0.75 }}>{block.title}</Typography>
      {children}
    </Box>
  );
  switch (block.type) {
    case 'overview':
      return wrap(<Grid container spacing={1}>{[['SKUs', d.total], ['Healthy', d.healthy], ['High risk', d.stockout_high], ['Overstock', d.overstock], ['Slow moving', d.slow_moving], ['> 90 days', d.aging_over_90]].map(([k, v]) => (
        <Grid key={k as string} size={{ xs: 4 }}><Kpi label={k as string} value={num(v as number)} /></Grid>))}</Grid>);
    case 'risk_table': return wrap(<RiskTable rows={d.risks.slice(0, 6)} testId="copilot-risk-table" />);
    case 'replenishment_table': return wrap(<ReplenishTable rows={d.recommendations.slice(0, 6)} testId="copilot-replenishment-table" />);
    case 'overstock_table': return wrap(<OverstockTable rows={d.products.slice(0, 6)} testId="copilot-overstock-table" />);
    case 'clearance_table': return wrap(<ClearanceTable rows={d.candidates.slice(0, 6)} testId="copilot-clearance-table" />);
    case 'forecast_chart': return wrap(<ForecastChart history={d.history} daily={d.daily} testId="copilot-forecast-chart" />);
    case 'scenario_comparison': return wrap(<ScenarioView data={d} />);
    case 'event_readiness': return wrap(<EventView data={{ ...d, readiness: d.readiness.slice(0, 6) }} />);
    case 'region_table': return wrap(<RegionView data={d} />);
    case 'sources':
      return wrap(<Stack spacing={0.75} data-testid="copilot-sources">{d.chunks.map((c: { chunk_id: string; title: string; section: string | null; text: string }) => (
        <Box key={c.chunk_id}><Chip size="small" label={`${c.title}${c.section ? ` · ${c.section}` : ''}`} sx={{ mb: 0.3 }} /><Typography variant="caption" color="text.secondary" component="div">{c.text.slice(0, 260)}{c.text.length > 260 ? '…' : ''}</Typography></Box>))}
        <Typography variant="caption" color="text.secondary">{d.note}</Typography></Stack>);
    case 'quality': return wrap(<BarList items={[{ label: 'Selected model (held-out daily WAPE %)', value: d.model.metrics.holdout[d.model.metrics.selected].wape * 100 }, { label: 'Baseline', value: d.model.metrics.holdout[d.model.metrics.baseline].wape * 100, color: '#9AA6BD' }]} format={(v) => `${num(v, 1)}%`} />);
    case 'notice': return wrap(<Alert severity="info" sx={{ py: 0 }}>{d.message}</Alert>);
    default: return null;
  }
};

const Assistant = ({ m, onAsk }: { m: Msg; onAsk: (q: string) => void }) => {
  const r = m.response;
  if (m.error) return <ErrorState error={m.error} />;
  if (!r) return null;
  return (
    <Box data-testid="copilot-message-assistant" data-intent={r.intent} data-status={r.status}>
      <Stack direction="row" spacing={0.75} sx={{ mb: 0.75 }} flexWrap="wrap" useFlexGap>
        {r.recommendationLabel && <RecommendationBadge />}
        <DataBadge source={r.dataSource} />
        {r.status === 'INSUFFICIENT_DATA' && <Chip size="small" color="info" label="Insufficient data" />}
        {r.status === 'REFUSED' && <Chip size="small" color="warning" label="Decision support only" />}
      </Stack>
      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }} data-testid="copilot-text">{r.message}</Typography>
      {r.blocks.map((b, i) => <ContainBoundary key={i} label="This table or chart could not be displayed."><Block block={b} /></ContainBoundary>)}
      {r.warnings.map((w) => <Alert key={w} severity="info" sx={{ mt: 1, py: 0 }}>{w}</Alert>)}
      <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.75 }} data-testid="copilot-trace">
        {r.tools.length > 0 ? `Numbers from: ${[...new Set(r.tools.map((t) => t.tool))].join(' · ')}` : 'No data lookup was needed'}
        {r.provenance?.run_id ? ` · model ${r.provenance.model_name} ${r.provenance.model_version}, run ${r.provenance.run_id.slice(0, 8)}, as of ${r.provenance.as_of}, POC synthetic history` : ''}
        {r.llm.used ? ` · explained by ${r.llm.model} (${num(r.llm.tokensIn)} in / ${num(r.llm.tokensOut)} out tokens${r.llm.grounding_fallback ? ', wording replaced by verified summary' : ''})` : ' · model not used'}
      </Typography>
      {r.suggestions.length > 0 && (
        <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>{r.suggestions.map((s) => <Chip key={s} size="small" label={s} clickable variant="outlined" onClick={() => onAsk(s)} data-testid="copilot-suggestion" />)}</Stack>
      )}
    </Box>
  );
};

/** The inventory copilot: available on every tab. Every number in an answer comes from a tool; the model only explains. */
export const Copilot = () => {
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [cid, setCid] = useState<string | null>(null);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => { end.current?.scrollIntoView({ block: 'end' }); }, [msgs, busy]);

  const ask = async (q: string) => {
    const message = q.trim();
    if (!message || busy) return;
    setText('');
    setBusy(true);
    setMsgs((m) => [...m, { id: Date.now(), role: 'user', text: message }]);
    try {
      const r = await inventoryAiApi.copilot(message, cid);
      setCid(r.conversationId);
      setMsgs((m) => [...m, { id: Date.now() + 1, role: 'assistant', text: r.message, response: r }]);
    } catch (e) {
      setMsgs((m) => [...m, { id: Date.now() + 1, role: 'assistant', text: '', error: e }]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Paper elevation={0} data-testid="inventory-copilot" sx={{ display: 'flex', flexDirection: 'column', height: '100%', borderRadius: 3, border: `1px solid ${colors.line}`, overflow: 'hidden', bgcolor: '#F7F9FC' }}>
      <Box sx={{ p: 1.5, background: gradients.aiPanel, color: '#fff' }}>
        <Typography fontWeight={900}>AI Inventory Copilot</Typography>
        <Typography variant="caption" sx={{ opacity: 0.85 }}>Ask about inventory, demand, replenishment, events and promotions. Answers come from the forecasting models; the AI explains, it does not calculate.</Typography>
      </Box>
      <Box sx={{ flex: 1, overflowY: 'auto', p: 1.5 }} data-testid="copilot-messages">
        {msgs.length === 0 && (
          <Stack spacing={1}>
            <Typography variant="body2" color="text.secondary">Try one of these:</Typography>
            {STARTERS.map((s) => <Chip key={s} label={s} clickable onClick={() => ask(s)} variant="outlined" data-testid="copilot-starter" sx={{ justifyContent: 'flex-start', height: 'auto', py: 0.6, '& .MuiChip-label': { whiteSpace: 'normal' } }} />)}
          </Stack>
        )}
        <Stack spacing={1.75}>
          {msgs.map((m) => m.role === 'user' ? (
            <Box key={m.id} sx={{ alignSelf: 'flex-end', maxWidth: '88%', ml: 'auto', bgcolor: colors.navy800, color: '#fff', px: 1.5, py: 0.9, borderRadius: 3 }} data-testid="copilot-message-user"><Typography variant="body2">{m.text}</Typography></Box>
          ) : <ContainBoundary key={m.id} label="This answer could not be displayed."><Assistant m={m} onAsk={ask} /></ContainBoundary>)}
          {busy && <Stack direction="row" spacing={1} alignItems="center" data-testid="copilot-busy"><CircularProgress size={16} /><Typography variant="caption" color="text.secondary">Reading the forecasts and explaining…</Typography></Stack>}
        </Stack>
        <div ref={end} />
      </Box>
      <Stack direction="row" spacing={1} sx={{ p: 1, borderTop: `1px solid ${colors.line}`, bgcolor: '#fff' }}>
        <TextField fullWidth size="small" placeholder="Ask about inventory, demand, replenishment…" value={text} onChange={(e) => setText(e.target.value)}
          inputProps={{ 'data-testid': 'copilot-input', 'aria-label': 'Ask the inventory copilot', maxLength: 600 }}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ask(text); } }} />
        <IconButton onClick={() => ask(text)} disabled={busy || !text.trim()} data-testid="copilot-send" aria-label="Send" sx={{ bgcolor: colors.green, color: '#fff', '&:hover': { bgcolor: colors.greenDark } }}><SendIcon fontSize="small" /></IconButton>
      </Stack>
    </Paper>
  );
};

export { inr };
