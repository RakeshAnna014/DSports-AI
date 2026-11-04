import { Box, Paper, Stack, Typography } from '@mui/material';
import { CheckCircleOutline, HelpOutline, Lightbulb, WarningAmber } from '@mui/icons-material';
import type { AiSupport } from '@/types/support';

type Investigation = Extract<AiSupport, { kind: 'INVESTIGATION' }>;

const Group = ({ icon, title, tone, testId, children }: { icon: React.ReactNode; title: string; tone: string; testId: string; children: React.ReactNode }) => (
  <Box data-testid={testId}>
    <Stack direction="row" spacing={0.5} alignItems="center" sx={{ mb: 0.4 }}>
      {icon}
      <Typography variant="caption" fontWeight={800} sx={{ color: tone, letterSpacing: '.04em' }}>{title}</Typography>
    </Stack>
    <Stack component="ul" spacing={0.35} sx={{ m: 0, pl: 2.25 }}>{children}</Stack>
  </Box>
);

/**
 * Verified facts, possibilities and what could not be verified are three different things and are never mixed. A fact names the DSports record
 * it came from; a possibility says what it rests on; "can't verify" says why that matters.
 */
export const InvestigationEvidence = ({ s }: { s: Investigation }) => {
  const { verified, inferences, missing, unverified_lookups } = s.evidence;
  return (
    <Paper variant="outlined" data-testid="support-evidence" sx={{ p: 1.5 }}>
      <Stack spacing={1.25}>
        {verified.length > 0 && (
          <Group icon={<CheckCircleOutline fontSize="small" color="success" />} title="VERIFIED IN YOUR DSPORTS RECORDS" tone="#067647" testId="support-verified">
            {verified.map((f) => <Typography key={f.statement} component="li" variant="body2">{f.statement}</Typography>)}
          </Group>
        )}
        {missing.length > 0 && (
          <Group icon={<HelpOutline fontSize="small" color="warning" />} title="COULD NOT BE VERIFIED" tone="#B54708" testId="support-missing">
            {missing.map((m) => <Typography key={m.item} component="li" variant="body2"><b>{m.item}.</b> {m.why}.</Typography>)}
          </Group>
        )}
        {inferences.length > 0 && (
          <Group icon={<Lightbulb fontSize="small" color="disabled" />} title="POSSIBLE EXPLANATIONS · NOT CONFIRMED" tone="#475467" testId="support-inferences">
            {inferences.map((i) => <Typography key={i.statement} component="li" variant="body2" color="text.secondary">{i.statement}</Typography>)}
          </Group>
        )}
        {unverified_lookups.length > 0 && (
          <Group icon={<WarningAmber fontSize="small" color="error" />} title="LOOKUPS THAT FAILED" tone="#B42318" testId="support-unverified">
            {unverified_lookups.map((u) => <Typography key={u} component="li" variant="body2">I could not check {u}.</Typography>)}
          </Group>
        )}
        {s.policy.length > 0 && (
          <Box data-testid="support-policy">
            <Typography variant="caption" fontWeight={800} sx={{ color: '#475467', letterSpacing: '.04em' }}>FROM THE KNOWLEDGE BASE</Typography>
            {s.policy.map((p) => (
              <Typography key={p.source} variant="body2" color="text.secondary"><b>{p.title}</b>: {p.snippet}</Typography>
            ))}
          </Box>
        )}
      </Stack>
    </Paper>
  );
};
