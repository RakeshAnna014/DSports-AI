import type { ReactNode } from 'react';
import { Box, Button, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { AutoAwesome, ErrorOutline, InboxOutlined } from '@mui/icons-material';
import { keyframes } from '@mui/material/styles';
import { colors, gradients } from '@/design/tokens';

export const EmptyState = ({ icon, title, body, action }: { icon?: ReactNode; title: string; body?: string; action?: ReactNode }) => (
  <Paper variant="outlined" sx={{ p: { xs: 3, md: 5 }, textAlign: 'center', borderStyle: 'dashed', bgcolor: 'transparent' }} data-testid="empty-state">
    <Stack alignItems="center" spacing={1.25}>
      <Box sx={{ color: 'text.disabled', display: 'flex' }}>{icon ?? <InboxOutlined sx={{ fontSize: 48 }} />}</Box>
      <Typography variant="h6">{title}</Typography>
      {body && <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 420 }}>{body}</Typography>}
      {action}
    </Stack>
  </Paper>
);

/** A friendly error: never the raw backend exception. */
export const ErrorState = ({ title = 'Something went wrong', body, onRetry }: { title?: string; body: string; onRetry?: () => void }) => (
  <Paper variant="outlined" sx={{ p: 2.5, borderColor: 'error.light', bgcolor: 'rgba(211,47,47,0.04)' }} role="alert" data-testid="error-state">
    <Stack direction="row" spacing={1.5} alignItems="flex-start">
      <ErrorOutline color="error" />
      <Box sx={{ flexGrow: 1 }}>
        <Typography variant="subtitle1" fontWeight={700}>{title}</Typography>
        <Typography variant="body2" color="text.secondary">{body}</Typography>
        {onRetry && <Button size="small" variant="outlined" sx={{ mt: 1 }} onClick={onRetry}>Try again</Button>}
      </Box>
    </Stack>
  </Paper>
);

const pulse = keyframes`0%,100%{opacity:.35;transform:scale(.85)}50%{opacity:1;transform:scale(1)}`;

/** "DSports AI is thinking..." with three pulsing dots (respects reduced motion through the global theme rule). */
export const Thinking = ({ label = 'DSports AI is thinking…' }: { label?: string }) => (
  <Stack direction="row" spacing={1.25} alignItems="center" data-testid="ai-thinking"
    sx={{ px: 1.5, py: 1, borderRadius: 3, background: gradients.glass, border: `1px solid ${colors.line}`, width: 'fit-content', bgcolor: 'background.paper' }}>
    <AutoAwesome fontSize="small" color="secondary" />
    <Typography variant="body2" color="text.secondary">{label}</Typography>
    <Stack direction="row" spacing={0.5}>
      {[0, 1, 2].map((i) => <Box key={i} sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: 'secondary.main', animation: `${pulse} 1.2s ${i * 0.18}s infinite` }} />)}
    </Stack>
  </Stack>
);

export const ProductRowSkeleton = ({ count = 4 }: { count?: number }) => (
  <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, 1fr)', md: `repeat(${count}, 1fr)` }, gap: 2 }} aria-busy="true" aria-label="Loading products">
    {Array.from({ length: count }).map((_, i) => (
      <Box key={i}><Skeleton variant="rounded" height={190} /><Skeleton width="60%" sx={{ mt: 1 }} /><Skeleton width="40%" /></Box>
    ))}
  </Box>
);
