import { Fragment } from 'react';
import { Alert, Box, Button, Paper, Stack, Typography } from '@mui/material';
import { AutoAwesome, VerifiedUser } from '@mui/icons-material';
import { Link as RouterLink } from 'react-router-dom';
import type { AiResponse, AiResponseType, AiSuggestion, ChatEntry } from '@/types/ai';
import { useAiStore } from '@/store/aiStore';
import { colors, gradients } from '@/design/tokens';
import ProductResultCards from '@/features/ai/ProductResultCards';
import ProductSelection from '@/features/ai/ProductSelection';
import ComparisonTable from '@/features/ai/ComparisonTable';
import OrderTimeline from '@/features/ai/OrderTimeline';
import ReturnOptions from '@/features/ai/ReturnOptions';
import { ConfirmationCard, ResponseError, SuggestionChips } from '@/features/ai/ResponseExtras';
import { SupportChatBlock } from '@/features/support/SupportChatBlock';
import { SupportFeedback } from '@/features/support/SupportFeedback';
import { CartCard, OrderCards, PaymentCards, RefundCards, ReviewsCard } from '@/features/ai/OrderStatusCard';
import SourcesList from '@/features/ai/SourcesList';
import { RecommendationBlock } from '@/components/products/RecommendationSection';

/** Renders **bold** and keeps line breaks; everything else is plain text (no HTML from the model is ever injected). */
const RichText = ({ text }: { text: string }) => (
  <Typography variant="body2" component="div" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', lineHeight: 1.6 }}>
    {text.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
      part.startsWith('**') && part.endsWith('**') ? <strong key={i}>{part.slice(2, -2)}</strong> : <Fragment key={i}>{part}</Fragment>)}
  </Typography>
);

/** The structured type; older/other responses without one are classified from what they hold (a frontend adapter, not a guess at text). */
export const responseKind = (r: AiResponse): AiResponseType => {
  if (r.type) return r.type;
  if (r.status === 'error' || r.status === 'ai_unavailable') return 'ERROR';
  if (r.actions.length || r.actionResult) return 'CONFIRMATION';
  if (r.comparison) return 'PRODUCT_COMPARISON';
  if (r.tracking) return 'ORDER_TRACKING';
  if (r.eligibility) return 'RETURN_OPTIONS';
  if (r.products.length) return 'PRODUCT_LIST';
  if (r.orders.length) return 'ORDER_LIST';
  return 'TEXT';
};

/** Types whose facts are shown as components; their deterministic sentence is demoted to a quiet summary line. */
const COMPONENT_FIRST = new Set<AiResponseType>(['RECOMMENDATIONS', 'PRODUCT_LIST', 'PRODUCT_SELECTION', 'RETURN_PRODUCT_SELECTION', 'ORDER_TRACKING', 'ORDER_LIST', 'PRODUCT_COMPARISON', 'CART']);

const VerifiedBlock = ({ children }: { children: React.ReactNode }) => (
  <Paper variant="outlined" data-testid="ai-verified" sx={{ p: 1.25, borderColor: 'success.light', bgcolor: 'rgba(22, 163, 74, 0.04)' }}>
    <Stack direction="row" spacing={0.5} alignItems="center" sx={{ mb: 0.75 }}>
      <VerifiedUser fontSize="small" color="success" />
      <Typography variant="caption" fontWeight={800} color="success.dark" sx={{ letterSpacing: '.04em' }}>VERIFIED FROM DSPORTS</Typography>
    </Stack>
    <Stack spacing={1.25}>{children}</Stack>
  </Paper>
);

/** `wide` is the page layout (results area); the drawer uses the narrow one. */
export const AssistantMessage = ({ response, onNavigate, wide = false }: { response: AiResponse; onNavigate: () => void; wide?: boolean }) => {
  const send = useAiStore((s) => s.send);
  const retry = useAiStore((s) => s.retry);
  const sending = useAiStore((s) => s.sending);
  const kind = responseKind(response);
  const ai = response.messageSource === 'ai';
  const choose = (choiceId: string, label: string) => void send(label, choiceId);
  const pick = (s: AiSuggestion) => void (s.choiceId ? send(s.label, s.choiceId) : send(s.message ?? s.label));
  const returnAction = response.suggestions?.find((s) => s.choiceId && s.label === 'Return this item');
  // An investigation is shown as structured evidence; its written summary stays visible but quiet. Case cards keep their sentence at full strength.
  const investigation = response.support?.kind === 'INVESTIGATION';
  const quietText = !ai && ((COMPONENT_FIRST.has(kind) && kind !== 'CART') || investigation);
  const hasRefunds = response.refunds.some((g) => g.refunds.length > 0);

  const verified: React.ReactNode[] = [];
  if (kind === 'PRODUCT_COMPARISON' && response.comparison) verified.push(<ComparisonTable key="cmp" comparison={response.comparison} />);
  else if (response.products.length > 0) verified.push(<ProductResultCards key="prod" products={response.products} onNavigate={onNavigate} grid={wide} />);
  if (response.orders.length > 0) verified.push(<OrderCards key="orders" orders={response.orders} onNavigate={onNavigate} />);
  if (response.tracking) verified.push(<OrderTimeline key="track" tracking={response.tracking} onNavigate={onNavigate} />);
  if (response.cart) verified.push(<CartCard key="cart" cart={response.cart} />);
  if (response.payments.length > 0) verified.push(<PaymentCards key="pay" payments={response.payments} />);
  if (hasRefunds) verified.push(<RefundCards key="ref" refunds={response.refunds} />);
  if (response.reviews) verified.push(<ReviewsCard key="rev" reviews={response.reviews} />);
  if (response.recommendations) verified.push(<RecommendationBlock key="rec" data={response.recommendations} compact={!wide} />);
  if (response.eligibility) verified.push(<ReturnOptions key="elig" eligibility={response.eligibility} action={returnAction} onAction={pick} disabled={sending} />);

  return (
    <Box sx={{ alignSelf: 'flex-start', maxWidth: wide ? '100%' : '96%', width: '100%' }} data-testid="ai-assistant-message" data-response-type={kind}>
      {kind === 'ERROR' && response.status !== 'ok' && <Box sx={{ mb: 1 }}><ResponseError response={response} onRetry={() => void retry()} /></Box>}
      {response.selection && <Box sx={{ mb: 1.25 }}><ProductSelection selection={response.selection} onChoose={choose} disabled={sending} /></Box>}
      {verified.length > 0 && <Box sx={{ mb: 1.25 }}><VerifiedBlock>{verified}</VerifiedBlock></Box>}
      {response.support && <Box sx={{ mb: 1.25 }} data-testid="ai-support-block"><SupportChatBlock support={response.support} /><Box sx={{ mt: 0.75 }}><SupportFeedback conversationId={response.conversationId} /></Box></Box>}
      {(response.actions.length > 0 || response.actionResult) && <Box sx={{ mb: 1.25 }}><ConfirmationCard response={response} /></Box>}

      {!response.selection && kind !== 'CONFIRMATION' && (quietText && (verified.length > 0 || investigation) ? (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 0.5, whiteSpace: 'pre-wrap' }} data-testid="ai-summary">{response.message}</Typography>
      ) : (
        <Paper variant="outlined" sx={{ p: 1.5, borderColor: ai ? 'secondary.light' : colors.line, background: ai ? 'linear-gradient(135deg, rgba(255,122,26,0.06), rgba(255,255,255,0))' : undefined }}>
          <Stack direction="row" spacing={0.5} alignItems="center" sx={{ mb: 0.5 }}>
            {ai ? <AutoAwesome fontSize="small" color="secondary" /> : <VerifiedUser fontSize="small" color="disabled" />}
            <Typography variant="caption" fontWeight={800} color={ai ? 'secondary.dark' : 'text.secondary'} sx={{ letterSpacing: '.04em' }}>
              {ai ? 'AI EXPLANATION' : 'FROM DSPORTS DATA'}
            </Typography>
          </Stack>
          <RichText text={response.message} />
          {response.sources.length > 0 && <SourcesList sources={response.sources} />}
          {response.status === 'needs_login' && (
            <Button component={RouterLink} to="/login" size="small" variant="contained" sx={{ mt: 1 }} onClick={onNavigate}>Sign in</Button>
          )}
        </Paper>
      ))}
      {quietText && response.sources.length > 0 && <SourcesList sources={response.sources} />}

      {response.disclaimers.map((d) => (
        <Typography key={d} variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>{d}</Typography>
      ))}
      {response.status === 'refused' && <Alert severity="warning" sx={{ mt: 0.75 }} icon={false}>That request was declined for safety.</Alert>}
      {kind !== 'CONFIRMATION' && <SuggestionChips suggestions={(response.suggestions ?? []).filter((s) => !(s === returnAction && response.eligibility))} onPick={pick} disabled={sending} />}
    </Box>
  );
};

const MessageList = ({ entries, onNavigate }: { entries: ChatEntry[]; onNavigate: () => void }) => {
  const retry = useAiStore((s) => s.retry);
  return (
    <Stack spacing={1.75} sx={{ p: 1.5 }} role="log" aria-live="polite" data-testid="ai-messages">
      {entries.map((e) => {
        if (e.role === 'user') {
          return (
            <Paper key={e.id} sx={{ alignSelf: 'flex-end', maxWidth: '85%', p: 1.25, color: '#fff', background: gradients.aiPanel, borderRadius: '16px 16px 4px 16px' }} data-testid="ai-user-message">
              <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{e.text}</Typography>
            </Paper>
          );
        }
        if (e.role === 'error') {
          return (
            <Alert key={e.id} severity={e.unavailable ? 'warning' : 'error'} data-testid="ai-error" action={<Button color="inherit" size="small" onClick={() => void retry()}>Try again</Button>}>
              {e.text}
            </Alert>
          );
        }
        return <AssistantMessage key={e.id} response={e.response} onNavigate={onNavigate} />;
      })}
    </Stack>
  );
};

export default MessageList;
