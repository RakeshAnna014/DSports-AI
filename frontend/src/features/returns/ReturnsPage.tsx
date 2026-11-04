import { useQuery } from '@tanstack/react-query';
import { Box, Button, Chip, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { AutoAwesome, KeyboardReturn } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { returnsApi } from '@/api/returns';
import { useAuthStore } from '@/store/authStore';
import { useAiStore } from '@/store/aiStore';
import { EmptyState, ErrorState } from '@/components/common/States';
import { formatDate } from '@/lib/productUtils';
import ProductImage from '@/components/common/ProductImage';
import { colors } from '@/design/tokens';

const STATUS: Record<string, { color: 'warning' | 'success' | 'error' | 'default'; hint: string }> = {
  REQUESTED: { color: 'warning', hint: 'Waiting for DSports to review it' },
  APPROVED: { color: 'success', hint: 'Approved. Any refund follows the refund process' },
  REJECTED: { color: 'error', hint: 'Not approved' },
};

/** Your return requests exactly as DSports holds them. Starting one goes through the AI (pick the product, check eligibility, confirm). */
const ReturnsPage = () => {
  const navigate = useNavigate();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const send = useAiStore((s) => s.send);
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['returns'], queryFn: returnsApi.list, enabled: isAuthenticated });
  const start = () => { void send('I want to return an item'); navigate('/ai'); };

  if (!isAuthenticated) {
    return <EmptyState icon={<KeyboardReturn sx={{ fontSize: 56 }} />} title="Sign in to see your returns" body="Your return requests are private to your account."
      action={<Button variant="contained" onClick={() => navigate('/login')}>Sign in</Button>} />;
  }
  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1} sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h3" component="h1">Returns</Typography>
          <Typography variant="body2" color="text.secondary">Start a return in a few taps, then follow it here.</Typography>
        </Box>
        <Button variant="contained" color="secondary" startIcon={<AutoAwesome />} onClick={start} data-testid="start-return">Start a return with DSports AI</Button>
      </Stack>
      {isError && <ErrorState body="We couldn't load your returns right now." onRetry={() => void refetch()} />}
      {isLoading && <Stack spacing={1.5}>{[0, 1].map((i) => <Skeleton key={i} variant="rounded" height={96} />)}</Stack>}
      {!isLoading && !isError && data?.length === 0 && (
        <EmptyState icon={<KeyboardReturn sx={{ fontSize: 56 }} />} title="No returns yet" body="If something is not right with a delivered order, DSports AI can check whether it can be returned." />
      )}
      <Stack spacing={1.5}>
        {data?.map((r) => {
          const s = STATUS[r.status] ?? { color: 'default' as const, hint: '' };
          return (
            <Paper key={r.id} variant="outlined" data-testid="return-card" sx={{ p: 2, display: 'flex', gap: 1.5, borderColor: colors.line }}>
              <ProductImage productId={r.productId} alt={r.productName} sx={{ width: 72, flexShrink: 0, borderRadius: 2 }} />
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Stack direction="row" justifyContent="space-between" spacing={1}>
                  <Typography variant="subtitle1" fontWeight={800}>{r.productName}</Typography>
                  <Chip size="small" color={s.color} label={r.status} />
                </Stack>
                <Typography variant="caption" color="text.secondary" display="block">Qty {r.quantity} · requested {formatDate(r.createdAt)}</Typography>
                <Typography variant="body2" sx={{ mt: 0.5 }}>{s.hint}{r.decisionNote ? ` · ${r.decisionNote}` : ''}</Typography>
                <Button size="small" sx={{ mt: 0.5 }} onClick={() => navigate(`/orders/${r.orderId}`)}>View order</Button>
              </Box>
            </Paper>
          );
        })}
      </Stack>
    </Box>
  );
};

export default ReturnsPage;
